package com.kotsukotsu.core.sync

import com.kotsukotsu.core.api.ApiResult
import com.kotsukotsu.core.api.GameApiPort
import com.kotsukotsu.core.api.PlaySessionRequest
import com.kotsukotsu.core.data.GamePrefs
import com.kotsukotsu.core.data.PendingPlay
import com.kotsukotsu.core.data.PlayStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

enum class SyncOutcome {
    /** キューが空になった */
    DONE,
    /** 通信不可・タイムアウト。キューに残して後で再送 */
    NETWORK,
    /** 503 などサーバ側の一時的な異常。キューに残して後で再送 */
    SERVER_BUSY,
    /** 401 登録解除。キューを保持したまま初回登録画面へ */
    UNAUTHORIZED,
    /** 426 アプリの更新が必要 */
    UPDATE_REQUIRED,
    /** 未登録（トークンなし） */
    NOT_REGISTERED,
}

/**
 * 未送信キューを古い順に1件ずつ送る（docs/06 1.5）。
 * 送信に成功したものだけを削除する。送信中に強制終了しても、同じ play_id で再送されるだけなので
 * サーバ側の冪等性（原則3）により二重計上にならない。
 */
class SyncManager(
    private val api: () -> GameApiPort?,
    private val store: PlayStore,
    private val prefs: GamePrefs,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    suspend fun flush(): SyncOutcome = mutex.withLock {
        val client = api()
        if (client == null || prefs.deviceToken == null) return SyncOutcome.NOT_REGISTERED
        for (p in store.pending()) {
            when (val r = client.postPlay(toRequest(p))) {
                is ApiResult.Ok -> {
                    val a = r.value.awarded
                    if (a.study != p.localStudy || a.streakBonus != p.localStreakBonus || a.selfBestBonus != p.localSelfBestBonus) {
                        prefs.pointsUpdatedNotice = true // サーバ値で上書きし、次回起動時に知らせる（docs/06 1.6）
                    }
                    prefs.summary = r.value.summary
                    prefs.summarySyncedAt = clock()
                    store.delete(p.playId)
                }
                is ApiResult.NetworkError -> {
                    store.markOffline(p.playId)
                    return SyncOutcome.NETWORK
                }
                is ApiResult.HttpError -> when {
                    r.status == 401 -> {
                        prefs.clearRegistration()
                        return SyncOutcome.UNAUTHORIZED
                    }
                    r.status == 426 -> return SyncOutcome.UPDATE_REQUIRED
                    r.status >= 500 || r.status < 0 -> {
                        store.markOffline(p.playId)
                        return SyncOutcome.SERVER_BUSY
                    }
                    else -> store.markRejected(p.playId, "${r.status} ${r.code}: ${r.message}") // 捨てずに残し、後続は送る
                }
            }
        }
        SyncOutcome.DONE
    }

    companion object {
        fun toRequest(p: PendingPlay) = PlaySessionRequest(
            playId = p.playId,
            mode = p.mode,
            dan = p.dan,
            startedAtDevice = Instant.ofEpochMilli(p.startedAtMillis).toString(),
            endedAtDevice = Instant.ofEpochMilli(p.endedAtMillis).toString(),
            wasOffline = p.wasOffline,
            clientRuleVersion = p.clientRuleVersion,
            answers = decodeAnswers(p.answersJson),
        )
    }
}
