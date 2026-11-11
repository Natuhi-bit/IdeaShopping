package com.koga.qloverplayer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "playback_state")

/** 最後に再生したもの・位置・正規化設定を保存する。アプリ再起動後にここから再開する。 */
data class LastPlayback(
    val playlistId: Long? = null,
    val trackId: Long? = null,
    val positionMs: Long = 0,
    val normalizationEnabled: Boolean = true,
    /** 目標ラウドネス。放送・配信の標準に近い -14 LUFS を初期値にする */
    val targetLufs: Float = -14f,
    /** 追加要件: モバイルデータ節約 */
    val wifiOnly: Boolean = false,
    val lowBitrate: Boolean = false,
)

class PlaybackStateRepository(private val context: Context) {
    private object Keys {
        val PLAYLIST_ID = longPreferencesKey("last_playlist_id")
        val TRACK_ID = longPreferencesKey("last_track_id")
        val POSITION_MS = longPreferencesKey("last_position_ms")
        val NORMALIZATION = booleanPreferencesKey("normalization_enabled")
        val TARGET_LUFS = floatPreferencesKey("target_lufs")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val LOW_BITRATE = booleanPreferencesKey("low_bitrate")
    }

    val lastPlayback: Flow<LastPlayback> = context.dataStore.data.map { p ->
        LastPlayback(
            playlistId = p[Keys.PLAYLIST_ID],
            trackId = p[Keys.TRACK_ID],
            positionMs = p[Keys.POSITION_MS] ?: 0,
            normalizationEnabled = p[Keys.NORMALIZATION] ?: true,
            targetLufs = p[Keys.TARGET_LUFS] ?: -14f,
            wifiOnly = p[Keys.WIFI_ONLY] ?: false,
            lowBitrate = p[Keys.LOW_BITRATE] ?: false,
        )
    }

    suspend fun savePosition(playlistId: Long, trackId: Long, positionMs: Long) {
        context.dataStore.edit {
            it[Keys.PLAYLIST_ID] = playlistId
            it[Keys.TRACK_ID] = trackId
            it[Keys.POSITION_MS] = positionMs
        }
    }

    suspend fun saveNormalization(enabled: Boolean, targetLufs: Float) {
        context.dataStore.edit {
            it[Keys.NORMALIZATION] = enabled
            it[Keys.TARGET_LUFS] = targetLufs
        }
    }

    suspend fun saveDataSaver(wifiOnly: Boolean, lowBitrate: Boolean) {
        context.dataStore.edit {
            it[Keys.WIFI_ONLY] = wifiOnly
            it[Keys.LOW_BITRATE] = lowBitrate
        }
    }
}
