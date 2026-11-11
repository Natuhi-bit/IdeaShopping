package com.koga.qloverplayer.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.room.Room
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.koga.qloverplayer.data.AppDatabase
import com.koga.qloverplayer.data.PlaybackStateRepository
import com.koga.qloverplayer.data.Playlist
import com.koga.qloverplayer.data.Track
import com.koga.qloverplayer.playback.QloveRPlayerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val db = Room.databaseBuilder(this, AppDatabase::class.java, "qlover-player.db").build()
        val repo = PlaybackStateRepository(applicationContext)
        val token = SessionToken(this, ComponentName(this, QloveRPlayerService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync().apply {
            addListener({ /* controller は onStart で取得する */ }, MoreExecutors.directExecutor())
        }
        setContent { QloveRPlayerScreen(db, repo, controllerFuture) }
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}

private fun formatMs(ms: Long): String {
    val s = (ms / 1000).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun QloveRPlayerScreen(
    db: AppDatabase,
    repo: PlaybackStateRepository,
    controllerFuture: ListenableFuture<MediaController>?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playlists by db.dao().playlists().collectAsState(initial = emptyList())
    val last by repo.lastPlayback.collectAsState(initial = null)
    var selectedPlaylistId by remember { mutableStateOf<Long?>(null) }
    val tracks by remember(selectedPlaylistId) {
        if (selectedPlaylistId == null) kotlinx.coroutines.flow.flowOf(emptyList<Track>())
        else db.dao().tracks(selectedPlaylistId!!)
    }.collectAsState(initial = emptyList())

    var controller by remember { mutableStateOf<MediaController?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentTitle by remember { mutableStateOf("未再生") }
    var showAddTrack by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var newTitle by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }
    var newLufs by remember { mutableStateOf("") }
    var newGain by remember { mutableStateOf(0f) }
    var editingTrackId by remember { mutableStateOf<Long?>(null) }
    var editLufs by remember { mutableStateOf("") }
    var editGain by remember { mutableStateOf(0f) }

    // 起動時にコントローラーを取得し、最後の状態を復元表示する
    LaunchedEffect(Unit) {
        scope.launch {
            val built = withContext(Dispatchers.IO) { runCatching { controllerFuture?.get() }.getOrNull() }
            built?.let { c ->
                controller = c
                c.repeatMode = Player.REPEAT_MODE_ALL // 連続再生
                c.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
                    override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                        currentTitle = item?.mediaMetadata?.title?.toString() ?: "未再生"
                    }
                })
            }
            // 初回だけデフォルトのプレイリストを作る
            if (db.dao().trackCount() == 0 && playlists.isEmpty()) {
                db.dao().insertPlaylist(Playlist(name = "QloveR Streaming"))
            }
        }
    }
    LaunchedEffect(playlists) {
        if (selectedPlaylistId == null && playlists.isNotEmpty()) selectedPlaylistId = playlists[0].id
    }

    fun playTracks(list: List<Track>, startIndex: Int, resumeMs: Long) {
        val c = controller ?: return
        val items = list.map { t ->
            MediaItem.Builder().setUri(t.url).setMediaId(t.id.toString())
                .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist("QloveR").build())
                .build()
        }
        c.setMediaItems(items, startIndex, resumeMs)
        c.prepare()
        c.play()
    }

    fun resumeLast() {
        val l = last ?: return
        scope.launch {
            val track = l.trackId?.let { db.dao().trackById(it) } ?: return@launch
            val list = db.dao().tracks(track.playlistId).first()
            val idx = list.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            // ライブ配信はシークできないことがあるため、末尾付近なら先頭からにする
            val pos = if (l.positionMs > 0) l.positionMs else 0L
            playTracks(list, idx, pos)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("QloveR Radio Player", style = MaterialTheme.typography.headlineMedium)
            Text(
                "QloveR公式には公開の直接ストリームURLがありません。聴きたい番組の音声URL（自分が利用権を持つ配信・手持ちファイル）を登録して使います。公式ページは下のボタンから開けます。",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://qlover.jp/agplayer")))
            }) { Text("QloveR Streaming をブラウザで開く") }
        }

        // 前回の続き
        item {
            val l = last
            if (l?.trackId != null && l.positionMs > 0) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("前回の続きがあります（${formatMs(l.positionMs)} から）")
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = ::resumeLast) { Text("前回の続きから再開") }
                    }
                }
            }
        }

        // 再生バー
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(currentTitle, style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { controller?.seekToPreviousMediaItem() }) { Text("⏮") }
                        Button(onClick = {
                            controller?.let { if (it.isPlaying) it.pause() else it.play() }
                        }) { Text(if (isPlaying) "一時停止" else "再生") }
                        IconButton(onClick = { controller?.seekToNextMediaItem() }) { Text("⏭") }
                    }
                    Text("連続再生: ON（プレイリスト末尾まで行くと先頭に戻ります）", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // プレイリスト作成
        item {
            Text("プレイリスト", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newPlaylistName, onValueChange = { newPlaylistName = it },
                    label = { Text("新しいプレイリスト名") }, modifier = Modifier.weight(1f),
                )
                Button(onClick = {
                    if (newPlaylistName.isNotBlank()) scope.launch {
                        val id = db.dao().insertPlaylist(Playlist(name = newPlaylistName))
                        newPlaylistName = ""
                        selectedPlaylistId = id
                    }
                }) { Text("作成") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                playlists.forEach { p ->
                    OutlinedButton(onClick = { selectedPlaylistId = p.id }) { Text(p.name) }
                }
            }
        }

        // 曲一覧
        items(tracks) { t ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(t.title)
                            Text(t.url, style = MaterialTheme.typography.bodySmall)
                            if (t.loudnessLufs != null || t.gainDb != 0f) {
                                Text(
                                    "音量: 実測 ${t.loudnessLufs?.let { "$it LUFS" } ?: "未測定"} ・ ゲイン ${if (t.gainDb >= 0) "+" else ""}${"%.1f".format(t.gainDb)} dB",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        Button(onClick = {
                            val idx = tracks.indexOf(t)
                            playTracks(tracks, idx, 0)
                        }) { Text("再生") }
                        IconButton(onClick = { scope.launch { db.dao().deleteTrack(t) } }) { Text("×") }
                    }
                    OutlinedButton(onClick = {
                        if (editingTrackId == t.id) editingTrackId = null
                        else {
                            editingTrackId = t.id
                            editGain = t.gainDb
                            editLufs = t.loudnessLufs?.toString() ?: ""
                        }
                    }) { Text(if (editingTrackId == t.id) "音量調整を閉じる" else "音量調整") }
                    if (editingTrackId == t.id) {
                        OutlinedTextField(
                            value = editLufs, onValueChange = { editLufs = it },
                            label = { Text("実測LUFS（例: -18、空欄なら未測定）") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("手動ゲイン: ${if (editGain >= 0) "+" else ""}${"%.1f".format(editGain)} dB（-12〜+12）")
                        Slider(
                            value = editGain,
                            onValueChange = { editGain = it },
                            valueRange = -12f..12f,
                            steps = 23,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                scope.launch {
                                    db.dao().insertTrack(
                                        t.copy(
                                            loudnessLufs = editLufs.toFloatOrNull()?.coerceIn(-60f, 0f),
                                            gainDb = editGain.coerceIn(-12f, 12f),
                                        ),
                                    )
                                    editingTrackId = null
                                }
                            }) { Text("保存") }
                            OutlinedButton(onClick = { editingTrackId = null }) { Text("キャンセル") }
                        }
                        Text("保存した値は次にこの曲が再生されるときから反映されます。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // 曲の追加
        item {
            if (showAddTrack) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = newTitle, onValueChange = { newTitle = it }, label = { Text("番組・曲名") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = newUrl, onValueChange = { newUrl = it }, label = { Text("音声URL または content:// ファイルURI") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = newLufs, onValueChange = { newLufs = it }, label = { Text("実測LUFS（任意、例: -18）") }, modifier = Modifier.fillMaxWidth())
                    Text("手動ゲイン: ${if (newGain >= 0) "+" else ""}${"%.1f".format(newGain)} dB（-12〜+12、任意）")
                    Slider(value = newGain, onValueChange = { newGain = it }, valueRange = -12f..12f, steps = 23)
                    Button(onClick = {
                        val pid = selectedPlaylistId ?: return@Button
                        if (newTitle.isNotBlank() && newUrl.isNotBlank()) scope.launch {
                            db.dao().insertTrack(
                                Track(
                                    playlistId = pid, title = newTitle, url = newUrl, sortOrder = tracks.size,
                                    loudnessLufs = newLufs.toFloatOrNull()?.coerceIn(-60f, 0f),
                                    gainDb = newGain.coerceIn(-12f, 12f),
                                ),
                            )
                            newTitle = ""; newUrl = ""; newLufs = ""; newGain = 0f; showAddTrack = false
                        }
                    }) { Text("プレイリストに追加") }
                }
            } else {
                OutlinedButton(onClick = { showAddTrack = true }) { Text("曲・配信URLを追加") }
            }
        }

        // 音量ノーマライゼーション
        item {
            val l = last
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("音量ノーマライゼーション", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("有効にする", Modifier.weight(1f))
                        Switch(
                            checked = l?.normalizationEnabled ?: true,
                            onCheckedChange = { v -> scope.launch { repo.saveNormalization(v, l?.targetLufs ?: -14f) } },
                        )
                    }
                    Text("目標: ${l?.targetLufs ?: -14f} LUFS（放送・配信の標準に近い値）")
                    Slider(
                        value = l?.targetLufs ?: -14f,
                        onValueChange = { v -> scope.launch { repo.saveNormalization(l?.normalizationEnabled ?: true, v) } },
                        valueRange = -23f..-10f,
                    )
                    Text("曲ごとに実測LUFSとの差をゲインで補正し、LoudnessEnhancer で曲間の音量差を均します。実測LUFSと手動ゲインは、各曲の「音量調整」または追加時に入力できます。ライブ配信は LoudnessEnhancer のみが効きます。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // モバイルデータ節約（追加要件）
        item {
            val l = last
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("モバイルデータ節約", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Wi-Fiのときだけ再生する", Modifier.weight(1f))
                        Switch(
                            checked = l?.wifiOnly ?: false,
                            onCheckedChange = { v -> scope.launch { repo.saveDataSaver(v, l?.lowBitrate ?: false) } },
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("低ビットレートを優先する（上限96kbps）", Modifier.weight(1f))
                        Switch(
                            checked = l?.lowBitrate ?: false,
                            onCheckedChange = { v -> scope.launch { repo.saveDataSaver(l?.wifiOnly ?: false, v) } },
                        )
                    }
                    Text("Wi-FiのみONのとき、モバイル回線では再生を開始せず、再生中なら一時停止します。低ビットレートONのとき、対応する配信は96kbps以下の音声を選びます。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
