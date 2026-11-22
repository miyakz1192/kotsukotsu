package com.kotsukotsu.core

import com.kotsukotsu.core.api.ApiResult
import com.kotsukotsu.core.api.Awarded
import com.kotsukotsu.core.sync.SyncOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime
import java.time.ZoneId

class SyncTest {
    private var now = ZonedDateTime.of(2026, 12, 1, 18, 0, 0, 0, ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli()
    private val server = FakeServer { now }
    private val store = MemoryStore()
    private val prefs = MemoryPrefs()
    private var ids = 0
    private val repo = GameRepository(prefs, store, { server }, { now }, { "play-${++ids}" })

    private fun answers(dan: Int, correct: Int) = (1..9).map { b -> AnsweredQuestion(dan, b, if (b <= correct) dan * b else 0, 1000) }

    private suspend fun registered() {
        val r = repo.register("http://server/", server.children[0], "kuku-challenge")
        assertTrue(r is ApiResult.Ok)
    }

    private suspend fun play(dan: Int = 3, correct: Int = 9) =
        repo.recordPlay("training_select", dan, answers(dan, correct), now - 60_000, now).also { now += 600_000 }

    @Test
    fun threeOfflinePlaysAreSentOldestFirstWhenOnline() = runTest {
        registered()
        server.mode = FakeServer.Mode.OFFLINE
        repeat(3) { i ->
            play(dan = i + 1)
            assertEquals(SyncOutcome.NETWORK, repo.sync.flush()) // その場で送ろうとして失敗
        }
        assertEquals(3, store.pending().size)
        assertEquals(27, repo.localView().todayStudyPoints) // ローカル計算で表示は進む
        server.mode = FakeServer.Mode.OK
        assertEquals(SyncOutcome.DONE, repo.syncAll())
        assertEquals(listOf("play-1", "play-2", "play-3"), server.received.map { it.playId })
        assertEquals(listOf(1, 2, 3), server.received.map { it.dan })
        assertTrue(server.received.all { it.wasOffline })
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun onlinePlayIsSentWithWasOfflineFalse() = runTest {
        registered()
        play()
        assertEquals(SyncOutcome.DONE, repo.sync.flush())
        assertFalse(server.received.single().wasOffline)
    }

    @Test
    fun crashAfterServerAcceptedDoesNotDoubleCount() = runTest {
        registered()
        play()
        // サーバは受け付けたが、端末は応答を受け取る前に強制終了した（キューから消えていない）
        server.postPlay(com.kotsukotsu.core.sync.SyncManager.toRequest(store.pending().single()))
        // 再起動
        repo.onAppStart()
        assertEquals(SyncOutcome.DONE, repo.syncAll())
        assertEquals(2, server.received.size)               // 同じ play_id で2回届く
        assertEquals(setOf("play-1"), server.received.map { it.playId }.toSet())
        assertEquals(1, server.ledgerByPlay.size)            // 台帳は1件
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun unauthorizedKeepsQueueAndReturnsToRegistration() = runTest {
        registered()
        server.mode = FakeServer.Mode.REVOKED_401
        play(); play()
        assertEquals(SyncOutcome.UNAUTHORIZED, repo.syncAll())
        assertNull(prefs.deviceToken)
        assertFalse(repo.isRegistered)
        assertEquals(2, store.pending().size) // 未送信分は保持
        // 再登録：キューがあるあいだは前の子供だけが選べる
        server.mode = FakeServer.Mode.OK
        val kids = (repo.fetchChildren("http://server/") as ApiResult.Ok).value
        assertEquals(listOf("c1"), kids.map { it.userId })
        repo.register("http://server/", kids[0], "kuku-challenge")
        assertEquals(SyncOutcome.DONE, repo.syncAll())
        assertEquals(2, server.ledgerByPlay.size)
    }

    @Test
    fun serverBusyKeepsQueue() = runTest {
        registered()
        play()
        server.mode = FakeServer.Mode.BUSY_503
        assertEquals(SyncOutcome.SERVER_BUSY, repo.sync.flush())
        assertEquals(1, store.pending().size)
        assertTrue(store.pending().single().wasOffline)
    }

    @Test
    fun rejectedPlayIsKeptAndDoesNotBlockOthers() = runTest {
        registered()
        play(); play()
        server.rejectPlayIds += "play-1"
        assertEquals(SyncOutcome.DONE, repo.sync.flush())
        assertEquals(listOf("play-2"), server.received.map { it.playId })
        assertEquals(1, store.rows.size) // 拒否分も消さずに残す
    }

    @Test
    fun serverValueOverridesLocalAndSetsNotice() = runTest {
        registered()
        play()
        server.awardOverride = Awarded(5, 0, 0) // サーバでは別の結果
        repo.sync.flush()
        assertTrue(prefs.pointsUpdatedNotice)
        assertEquals(5, repo.localView().balance)
    }

    @Test
    fun localViewAppliesCapsAndStreak() = runTest {
        registered()
        server.mode = FakeServer.Mode.OFFLINE
        val first = play(dan = 2)
        assertEquals(9, first.result.study)
        assertEquals(1, first.result.streakBonus)
        play(dan = 3); play(dan = 4); play(dan = 5); play(dan = 6)
        val v = repo.localView()
        assertEquals(40, v.todayStudyPoints) // 1日40ptで頭打ち
        assertEquals(45, v.todayQuestions)
        assertEquals(1, v.streakDays)
        assertEquals(41, v.balance)
    }
}
