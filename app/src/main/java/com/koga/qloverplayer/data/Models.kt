package com.koga.qloverplayer.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * 1曲 = 1つの配信URL / ローカルファイル。
 * QloveR には公式の公開ストリームURLがないため、URLは
 * ユーザーが自分のアカウントで聴ける配信のURL、または
 * 手持ちの音声ファイルのURIを入れる設計にしている。
 * qlover.jp/agplayer はHTMLページなので直接再生できない。
 */
@Entity(tableName = "tracks")
data class Track(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val title: String,
    /** http(s) の音声ストリームURL、または content:// のローカルファイルURI */
    val url: String,
    val sortOrder: Int,
    /** この曲の実測ラウドネス (LUFS)。未測定なら null。測定済みなら正規化に使う */
    val loudnessLufs: Float? = null,
    /** 手動のゲイン補正 (dB)。-12〜+12 */
    val gainDb: Float = 0f,
)

@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface PlayerDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt ASC")
    fun playlists(): Flow<List<Playlist>>

    @Query("SELECT * FROM tracks WHERE playlistId = :playlistId ORDER BY sortOrder ASC")
    fun tracks(playlistId: Long): Flow<List<Track>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun trackById(id: Long): Track?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: Playlist): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrack(track: Track): Long

    @Delete
    suspend fun deleteTrack(track: Track)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun trackCount(): Int
}

@Database(entities = [Playlist::class, Track::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): PlayerDao
}
