package com.kotsukotsu.core.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/**
 * 未送信キューの1件（1プレイ＝1モード分）。プレイ結果はまず必ずここに保存してから送る（docs/06 1.5）。
 * localId の昇順＝古い順に送る。送信に成功したら削除する。
 */
@Entity(tableName = "pending_plays", indices = [Index(value = ["playId"], unique = true)])
data class PendingPlay(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val playId: String,
    val mode: String,
    val dan: Int?,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    /** List<AnswerDto> の JSON */
    val answersJson: String,
    val wasOffline: Boolean,
    val clientRuleVersion: Int?,
    /** 演出に使ったローカル計算の結果（サーバ確定値との食い違い検出用） */
    val localStudy: Int,
    val localStreakBonus: Int,
    val localSelfBestBonus: Int,
    /** PENDING / REJECTED（サーバが 4xx で受け付けなかったもの。捨てずに残す） */
    val status: String = STATUS_PENDING,
    val rejectReason: String? = null,
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_REJECTED = "REJECTED"
    }
}

/** 未送信キューの保存先。Room 実装とテスト用のメモリ実装がある。 */
interface PlayStore {
    suspend fun insert(play: PendingPlay)
    suspend fun pending(): List<PendingPlay>
    suspend fun delete(playId: String)
    suspend fun markOffline(playId: String)
    suspend fun markAllOffline()
    suspend fun markRejected(playId: String, reason: String)
}

@Dao
interface PendingPlayDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(play: PendingPlay)
    @Query("SELECT * FROM pending_plays WHERE status = 'PENDING' ORDER BY localId") suspend fun pending(): List<PendingPlay>
    @Query("DELETE FROM pending_plays WHERE playId = :playId") suspend fun delete(playId: String)
    @Query("UPDATE pending_plays SET wasOffline = 1 WHERE playId = :playId") suspend fun markOffline(playId: String)
    @Query("UPDATE pending_plays SET wasOffline = 1 WHERE status = 'PENDING'") suspend fun markAllOffline()
    @Query("UPDATE pending_plays SET status = 'REJECTED', rejectReason = :reason WHERE playId = :playId")
    suspend fun markRejected(playId: String, reason: String)
}

@Database(entities = [PendingPlay::class], version = 1, exportSchema = false)
abstract class CoreDatabase : RoomDatabase() {
    abstract fun pendingPlays(): PendingPlayDao
}

class RoomPlayStore(private val dao: PendingPlayDao) : PlayStore {
    override suspend fun insert(play: PendingPlay) = dao.insert(play)
    override suspend fun pending() = dao.pending()
    override suspend fun delete(playId: String) = dao.delete(playId)
    override suspend fun markOffline(playId: String) = dao.markOffline(playId)
    override suspend fun markAllOffline() = dao.markAllOffline()
    override suspend fun markRejected(playId: String, reason: String) = dao.markRejected(playId, reason)
}
