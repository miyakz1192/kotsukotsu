package com.kotsukotsu.core

import com.kotsukotsu.core.api.ApiResult
import com.kotsukotsu.core.notify.NotifyMessage
import com.kotsukotsu.core.notify.NotifySettings
import com.kotsukotsu.core.sync.SyncOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

/** 通知の設定の受け取りと、時刻が来たときの判定（docs/06 1.7）。 */
class ReminderRepositoryTest {
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private fun t(d: Int, hh: Int, mm: Int = 0) = ZonedDateTime.of(2026, 12, d, hh, mm, 0, 0, tokyo).toInstant().toEpochMilli()
    private var now = t(1, 18)
    private val server = FakeServer { now }.apply {
        notify = NotifySettings(
            weekdayTimes = listOf("07:15", "17:00", "20:00"), holidayTimes = listOf("10:00"), pointMessageEvery = 2,
            messages = listOf(NotifyMessage("e1", "encourage", "{name}、やってみよう"),
                NotifyMessage("p1", "points", "いま {balance}ポイント、あと {today_left}")),
        )
    }
    private val store = MemoryStore()
    private val prefs = MemoryPrefs()
    private var ids = 0
    private val repo = GameRepository(prefs, store, { server }, { now }, { "play-${++ids}" })

    private suspend fun registered() = assertTrue(repo.register("http://server/", server.children[0], "kuku-challenge") is ApiResult.Ok)
    private suspend fun play() =
        repo.recordPlay("training_select", 3, (1..9).map { AnsweredQuestion(3, it, 3 * it, 1000) }, now - 60_000, now)

    @Test fun syncStoresSettingsAndKeepsThemOffline() = runTest {
        registered()
        var synced = 0
        repo.onSynced = { synced++ }
        assertNull(repo.nextReminderAt(now))
        assertEquals(SyncOutcome.DONE, repo.syncAll())
        assertEquals(1, synced)
        assertEquals(t(1, 20), repo.nextReminderAt(now)) // 12/1(火) 18:00 の次は 20:00
        server.mode = FakeServer.Mode.OFFLINE
        repo.syncAll()
        assertEquals(listOf("07:15", "17:00", "20:00"), prefs.notifySettings!!.weekdayTimes) // 前回の値のまま
    }

    @Test fun notifiesUntilPlayedThatDay() = runTest {
        registered()
        repo.syncAll()
        now = t(1, 7, 15)
        val first = repo.reminderText(now, Random(1))
        assertEquals("はると、やってみよう", first) // 1回目は励まし
        now = t(1, 17)
        assertEquals("いま 0ポイント、あと 40", repo.reminderText(now, Random(1))) // 2回に1回はポイント入り
        play()
        now = t(1, 20)
        assertNull(repo.reminderText(now, Random(1))) // 遊んだので その日の残りは出ない
        // 送信して未送信キューが空になり、サーバの summary にまだ反映がなくても、最後のプレイ日で判定できる
        repo.syncAll()
        assertTrue(store.pending().isEmpty())
        assertNull(repo.reminderText(now, Random(1)))
        now = t(2, 7, 15)
        assertNotNull(repo.reminderText(now, Random(1))) // 翌日（N=1）は また出る
    }

    @Test fun inactiveDaysTwoAndDisabled() = runTest {
        registered()
        server.notify = server.notify.copy(inactiveDays = 2)
        repo.syncAll()
        play()
        now = t(2, 17)
        assertNull(repo.reminderText(now)) // 昨日やった
        now = t(3, 17)
        assertNotNull(repo.reminderText(now)) // 昨日も今日もやっていない
        server.notify = server.notify.copy(enabled = false)
        repo.syncAll()
        assertNull(repo.reminderText(now))
        assertNull(repo.nextReminderAt(now))
    }
}
