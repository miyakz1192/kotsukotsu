package com.kotsukotsu.core

import com.kotsukotsu.core.notify.NotifyMessage
import com.kotsukotsu.core.notify.NotifySettings
import com.kotsukotsu.core.notify.ReminderPlanner
import com.kotsukotsu.core.notify.ReminderValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.random.Random

/** おしらせ通知の判定（docs/06 1.7、REQ-GAME-19〜21）。日付は Asia/Tokyo。 */
class ReminderPlannerTest {
    private val tokyo = java.time.ZoneId.of("Asia/Tokyo")
    private fun at(y: Int, m: Int, d: Int, hh: Int, mm: Int) = ZonedDateTime.of(y, m, d, hh, mm, 0, 0, tokyo).toInstant()
    private val base = NotifySettings(
        enabled = true, inactiveDays = 1, weekdayTimes = listOf("07:15", "17:00", "20:00"), holidayTimes = listOf("10:00", "20:00"),
        pointMessageEvery = 3,
        messages = listOf(
            NotifyMessage("e1", "encourage", "{name}、やってみよう"), NotifyMessage("e2", "encourage", "がんばろう"),
            NotifyMessage("p1", "points", "いま {balance}ポイント、あと {today_left}"),
        ),
        holidays = listOf("2026-11-23", "2027-01-01"), holidaysUntil = "2027-02-15",
    )

    // ---------- 判定：今日を含めて N 日やっていない ----------

    @Test fun inactiveCountsTodayInclusive() {
        val today = LocalDate.of(2026, 11, 18)
        assertTrue(ReminderPlanner.shouldNotify(today, null, 1)) // 一度もやっていない
        assertTrue(ReminderPlanner.shouldNotify(today, today.minusDays(1), 1)) // 昨日やった、今日まだ
        assertFalse(ReminderPlanner.shouldNotify(today, today, 1)) // 今日やった
        assertFalse(ReminderPlanner.shouldNotify(today, today.minusDays(1), 2)) // N=2：昨日やったので出さない
        assertTrue(ReminderPlanner.shouldNotify(today, today.minusDays(2), 2)) // 昨日も今日もやっていない
        assertFalse(ReminderPlanner.shouldNotify(today, today, 2))
    }

    @Test fun lastPlayDateUsesTokyoDayBoundary() {
        // 23:59 にやったプレイは その日、0:00 は翌日
        assertEquals(LocalDate.of(2026, 11, 17), ReminderPlanner.tokyoDateOf(at(2026, 11, 17, 23, 59)))
        assertEquals(LocalDate.of(2026, 11, 18), ReminderPlanner.tokyoDateOf(at(2026, 11, 18, 0, 0)))
        assertEquals(LocalDate.of(2026, 11, 18), ReminderPlanner.latest(LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 2)))
        assertEquals(LocalDate.of(2026, 11, 2), ReminderPlanner.latest(null, LocalDate.of(2026, 11, 2)))
    }

    // ---------- 次の時刻：平日と土日祝 ----------

    @Test fun nextFireOnWeekday() {
        // 2026-11-18 は水曜
        assertEquals(at(2026, 11, 18, 7, 15), ReminderPlanner.nextFire(at(2026, 11, 18, 6, 0), base))
        assertEquals(at(2026, 11, 18, 17, 0), ReminderPlanner.nextFire(at(2026, 11, 18, 7, 15), base)) // ちょうどの時刻は過ぎた扱い
        assertEquals(at(2026, 11, 19, 7, 15), ReminderPlanner.nextFire(at(2026, 11, 18, 20, 30), base))
    }

    @Test fun nextFireOnWeekendAndHoliday() {
        // 2026-11-20(金) 20:30 → 11/21(土) 10:00
        assertEquals(at(2026, 11, 21, 10, 0), ReminderPlanner.nextFire(at(2026, 11, 20, 20, 30), base))
        // 11/23(月) は勤労感謝の日 → 土日祝の時刻
        assertEquals(at(2026, 11, 23, 10, 0), ReminderPlanner.nextFire(at(2026, 11, 22, 21, 0), base))
        assertTrue(ReminderPlanner.isHoliday(LocalDate.of(2026, 11, 23), base))
        assertFalse(ReminderPlanner.isHoliday(LocalDate.of(2026, 11, 24), base))
    }

    @Test fun holidayListExpiredMeansWeekendOnly() {
        val s = base.copy(holidays = listOf("2027-03-22"), holidaysUntil = "2027-02-15")
        assertFalse(ReminderPlanner.isHoliday(LocalDate.of(2027, 3, 22), s)) // 期限切れのリストは使わない
        assertTrue(ReminderPlanner.isHoliday(LocalDate.of(2027, 3, 20), s)) // 土曜
    }

    @Test fun emptySlotsAndDisabled() {
        val noHoliday = base.copy(holidayTimes = emptyList())
        // 金曜の夜 → 土日は枠なし → 月曜の朝
        assertEquals(at(2026, 11, 24, 7, 15), ReminderPlanner.nextFire(at(2026, 11, 20, 20, 30), noHoliday))
        assertNull(ReminderPlanner.nextFire(at(2026, 11, 20, 20, 30), base.copy(weekdayTimes = emptyList(), holidayTimes = emptyList())))
        assertNull(ReminderPlanner.nextFire(at(2026, 11, 20, 20, 30), base.copy(enabled = false)))
        assertNull(ReminderPlanner.nextFire(at(2026, 11, 20, 20, 30), base.copy(weekdayTimes = listOf("bad"), holidayTimes = emptyList())))
    }

    // ---------- 文言 ----------

    private val vals = ReminderValues(name = "はると", balance = 120, todayLeft = 40, monthLeft = 900, streak = 3, nextStreak = 4, streakBonus = 4)

    @Test fun everyThirdIsPointsMessage() {
        val kinds = (0 until 6).map { n -> ReminderPlanner.pickMessage(base, vals, n, null, Random(1)).kind }
        assertEquals(listOf("encourage", "encourage", "points", "encourage", "encourage", "points"), kinds)
        val never = (0 until 6).map { n -> ReminderPlanner.pickMessage(base.copy(pointMessageEvery = 0), vals, n, null, Random(1)).kind }
        assertTrue(never.all { it == "encourage" })
        val always = (0 until 3).map { n -> ReminderPlanner.pickMessage(base.copy(pointMessageEvery = 1), vals, n, null, Random(1)).kind }
        assertTrue(always.all { it == "points" })
    }

    @Test fun pointsFallsBackWhenCapReached() {
        val s = base.copy(pointMessageEvery = 1)
        assertEquals("encourage", ReminderPlanner.pickMessage(s, vals.copy(todayLeft = 0), 0, null, Random(1)).kind)
        assertEquals("encourage", ReminderPlanner.pickMessage(s, vals.copy(monthLeft = 0), 0, null, Random(1)).kind)
    }

    @Test fun avoidsRepeatingLastMessage() {
        repeat(20) { seed ->
            val m = ReminderPlanner.pickMessage(base, vals, 0, "e1", Random(seed))
            assertEquals("e2", m.messageId)
        }
        // 候補が1つだけなら同じでもよい
        val one = base.copy(messages = base.messages.filter { it.messageId != "e2" })
        assertEquals("e1", ReminderPlanner.pickMessage(one, vals, 0, "e1", Random(1)).messageId)
    }

    @Test fun fallbacksWhenNoMessages() {
        val onlyPoints = base.copy(messages = base.messages.filter { it.kind == "points" })
        assertEquals("p1", ReminderPlanner.pickMessage(onlyPoints, vals, 0, null, Random(1)).messageId) // 励ましがなければポイント文
        val none = base.copy(messages = emptyList())
        assertTrue(ReminderPlanner.pickMessage(none, vals, 0, null, Random(1)).text.isNotBlank()) // 組み込みの1文
        // ポイント文しかなく上限到達 → 組み込みの励まし
        assertEquals("encourage", ReminderPlanner.pickMessage(onlyPoints, vals.copy(todayLeft = 0), 0, null, Random(1)).kind)
    }

    @Test fun renderPlaceholders() {
        assertEquals("はると、いま 120ポイント、あと 40 / 900 / 3 / 4 / 4",
            ReminderPlanner.render("{name}、いま {balance}ポイント、あと {today_left} / {month_left} / {streak} / {next_streak} / {streak_bonus}", vals))
        assertEquals("{unknown} そのまま", ReminderPlanner.render("{unknown} そのまま", vals))
    }

    @Test fun valuesFromPlayedDates() {
        // 昨日までの連続3日 → きょう やると4日目、ボーナス4×設定値
        val today = LocalDate.of(2026, 11, 18)
        val played = setOf(today.minusDays(1), today.minusDays(2), today.minusDays(3))
        val v = ReminderPlanner.values("はると", today, played, balance = 50, todayLeft = 40, monthLeft = 100, streakBonusPerDay = 2)
        assertEquals(3, v.streak)
        assertEquals(4, v.nextStreak)
        assertEquals(8, v.streakBonus)
        // 月初はリセット（12/1 は 1日目）
        val dec1 = LocalDate.of(2026, 12, 1)
        val v2 = ReminderPlanner.values("はると", dec1, setOf(dec1.minusDays(1)), 0, 40, 1500, 2)
        assertEquals(1, v2.nextStreak)
        // ボーナスは月の上限の残りで切り詰める
        assertEquals(5, ReminderPlanner.values("x", today, played, 0, 40, 5, 2).streakBonus)
    }
}
