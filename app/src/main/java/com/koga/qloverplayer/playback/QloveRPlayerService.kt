package com.koga.qloverplayer.playback

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.room.Room
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.koga.qloverplayer.data.AppDatabase
import com.koga.qloverplayer.data.PlaybackStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * バックグラウンド再生 + Android Auto の中核。
 *
 * - MediaLibraryService なので、Android Auto / Wear / 通知 / ロック画面から
 *   プレイリスト一覧が見え、操作できる。
 * - 曲が終われば自動で次へ (REPEAT_MODE_ALL + Concatenated playlist)。
 *   プレイリスト末尾まで行ったら先頭に戻って連続再生する。
 * - 30秒ごとに現在位置を DataStore に保存し、曲切替・終了時にも保存する。
 *   次回起動時は MainActivity がここから再開する。
 */
class QloveRPlayerService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private lateinit var trackSelector: DefaultTrackSelector
    private lateinit var normalizer: LoudnessNormalizer
    private lateinit var db: AppDatabase
    private lateinit var stateRepo: PlaybackStateRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        db = Room.databaseBuilder(this, AppDatabase::class.java, "qlover-player.db").build()
        stateRepo = PlaybackStateRepository(applicationContext)

        trackSelector = DefaultTrackSelector(this)
        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true) // イヤホンが抜けたら一時停止
            .build().apply {
                repeatMode = Player.REPEAT_MODE_ALL // プレイリスト連続再生
                playWhenReady = false
            }
        normalizer = LoudnessNormalizer(player)
        // audioSessionId が決まったタイミングで LoudnessEnhancer を付ける
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                normalizer.attach(audioSessionId)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                applyNormalization()
                savePosition()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) savePosition()
            }
        })

        mediaSession = MediaLibrarySession.Builder(this, player, LibrarySessionCallback())
            .setId("qlover-player-session")
            .build()

        // 30秒ごとの定期保存（アプリが落ちても再開位置が残る）
        scope.launch {
            while (true) {
                delay(30_000)
                if (player.isPlaying) savePosition()
            }
        }
    }

    /** モバイルデータ節約: Wi-Fiのみならモバイル回線では再生しない。低ビットレートなら上限96kbps */
    fun isWifiConnected(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun applyDataSaver(lowBitrate: Boolean) {
        val params = DefaultTrackSelector.Parameters.Builder()
            .setMaxAudioBitrate(if (lowBitrate) 96_000 else Int.MAX_VALUE)
            .build()
        trackSelector.parameters = params
    }

    private fun applyNormalization() {
        scope.launch {
            val last = stateRepo.lastPlayback.first()
            applyDataSaver(last.lowBitrate)
            if (last.wifiOnly && !isWifiConnected()) {
                // モバイル回線では再生開始しない（既に再生中なら止める）
                player.pause()
                return@launch
            }
            val track = player.currentMediaItem?.mediaId?.toLongOrNull()
                ?.let { db.dao().trackById(it) }
            normalizer.apply(track, last.normalizationEnabled, last.targetLufs)
        }
    }

    private fun savePosition() {
        scope.launch {
            val trackId = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return@launch
            val playlistId = db.dao().trackById(trackId)?.playlistId ?: return@launch
            // ライブ配信は position が伸び続けるので、末尾付近なら 0 に戻して保存する
            val pos = player.currentPosition.coerceAtLeast(0)
            stateRepo.savePosition(playlistId, trackId, pos)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    override fun onDestroy() {
        savePosition()
        mediaSession?.run { player.release(); release() }
        normalizer.release()
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // タスクから消されても再生を続ける（バックグラウンド再生）。止めたい場合は通知から停止する
        super.onTaskRemoved(rootIntent)
    }

    /** Android Auto / MediaBrowser にプレイリスト一覧を返す */
    private inner class LibrarySessionCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder()
                .setMediaId("root")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("プレイリスト")
                        .setIsBrowsable(true)
                        .build(),
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            // ルート直下はプレイリスト一覧、プレイリスト直下はその曲一覧を返す。
            // 曲には音声URLを含めて返すので、車載側が選んだ曲はそのまま再生できる。
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            scope.launch {
                runCatching {
                    val items = withContext(Dispatchers.IO) {
                        when {
                            parentId == "root" -> db.dao().playlists().first().map { p ->
                                MediaItem.Builder()
                                    .setMediaId("playlist:${p.id}")
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(p.name)
                                            .setIsBrowsable(true)
                                            .setIsPlayable(false)
                                            .build(),
                                    )
                                    .build()
                            }
                            parentId.startsWith("playlist:") -> {
                                val pid = parentId.removePrefix("playlist:").toLongOrNull() ?: 0L
                                db.dao().tracks(pid).first().map { t ->
                                    MediaItem.Builder()
                                        .setMediaId(t.id.toString())
                                        .setUri(t.url)
                                        .setMediaMetadata(
                                            MediaMetadata.Builder()
                                                .setTitle(t.title)
                                                .setArtist("QloveR")
                                                .setIsBrowsable(false)
                                                .setIsPlayable(true)
                                                .build(),
                                        )
                                        .build()
                                }
                            }
                            else -> emptyList()
                        }
                    }
                    future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
                }.onFailure { future.setException(it) }
            }
            return future
        }
    }
}
