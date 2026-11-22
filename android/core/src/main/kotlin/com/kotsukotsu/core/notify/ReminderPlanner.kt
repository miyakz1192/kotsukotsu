package com.kotsukotsu.core.notify

import com.kotsukotsu.core.points.PointEngine
import com.kotsukotsu.core.sync.TOKYO
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.random.Random

/** 文言の差し込みに使う値（docs/06 1.7）。 */
data class ReminderValues(
    val name: String,
    val balance: Int,
    val todayLeft: Int,
    val monthLeft: Int,
    val streak: Int,
    val nextStreak: Int,
    val streakBonus: Int,
)

/**
 * おしらせ通知の判定（純粋関数、テスト ReminderPlannerTest）。
 * 通知は端末内で予約して出す。サーバに通信できなくても、最後に受け取った設定で動く。
 */
object ReminderPlanner {
    const val ENCOURAGE = "encourage"
    const val POINTS = "points"
    private val FALLBACK = NotifyMessage("builtin", ENCOURAGE, "きょうも 九九 やってみよう！")
    private val PLACEHOLDER = Regex("\\{([a-z_]+)\\}")

    fun tokyoDateOf(t: Instant): LocalDate = t.atZone(TOKYO).toLocalDate()

    fun latest(a: LocalDate?, b: LocalDate?): LocalDate? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }

    /** 今日を含めて inactiveDays 日、プレイがないか（REQ-GAME-20）。 */
    fun shouldNotify(today: LocalDate, lastPlayDate: LocalDate?, inactiveDays: Int): Boolean {
        lastPlayDate ?: return true
        return lastPlayDate <= today.minusDays(inactiveDays.coerceAtLeast(1).toLong())
    }

    /** 土日、または配られた祝日（リストの期限内に限る）。 */
    fun isHoliday(d: LocalDate, s: NotifySettings): Boolean {
        if (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) return true
        val until = s.holidaysUntil?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return false
        return d <= until && d.toString() in s.holidays
    }

    private fun parseTime(v: String): LocalTime? = runCatching { LocalTime.parse(v.padStart(5, '0')) }.getOrNull()

    fun timesFor(d: LocalDate, s: NotifySettings): List<LocalTime> =
        (if (isHoliday(d, s)) s.holidayTimes else s.weekdayTimes).mapNotNull(::parseTime).sorted()

    /** now より後で最初の通知時刻。無効・時刻なしなら null。 */
    fun nextFire(now: Instant, s: NotifySettings): Instant? {
        if (!s.enabled) return null
        val today = tokyoDateOf(now)
        for (k in 0L..8L) {
            val d = today.plusDays(k)
            for (t in timesFor(d, s)) {
                val at = d.atTime(t).atZone(TOKYO).toInstant()
                if (at.isAfter(now)) return at
            }
        }
        return null
    }

    /** 差し込みの値。nextStreak は「きょう やると なる 連続日数」（月初リセット、docs/03 5.3）。 */
    fun values(name: String, today: LocalDate, playedDates: Set<LocalDate>, balance: Int, todayLeft: Int, monthLeft: Int,
               streakBonusPerDay: Int): ReminderValues {
        val next = PointEngine.computeStreak(today, playedDates)
        return ReminderValues(
            name = name, balance = balance, todayLeft = todayLeft, monthLeft = monthLeft,
            streak = PointEngine.computeCurrentStreak(today, playedDates), nextStreak = next,
            streakBonus = minOf(next * streakBonusPerDay, monthLeft),
        )
    }

    /**
     * counter 回目（0始まり）の文言。pointMessageEvery 回に1回はポイント入り。
     * 今日・今月の上限に達していれば「ふやせる」が成り立たないので励ましにする。前回と同じ文は避ける。
     */
    fun pickMessage(s: NotifySettings, v: ReminderValues, counter: Int, lastMessageId: String?, random: Random): NotifyMessage {
        val every = s.pointMessageEvery
        val wantPoints = every > 0 && counter % every == every - 1
        val pointsOk = v.todayLeft > 0 && v.monthLeft > 0
        val encourage = s.messages.filter { it.kind == ENCOURAGE }
        val points = if (pointsOk) s.messages.filter { it.kind == POINTS } else emptyList()
        val candidates = when {
            wantPoints && points.isNotEmpty() -> points
            encourage.isNotEmpty() -> encourage
            points.isNotEmpty() -> points
            else -> return FALLBACK
        }
        val fresh = candidates.filter { it.messageId != lastMessageId }.ifEmpty { candidates }
        return fresh[random.nextInt(fresh.size)]
    }

    fun render(text: String, v: ReminderValues): String = PLACEHOLDER.replace(text) { m ->
        when (m.groupValues[1]) {
            "name" -> v.name
            "balance" -> v.balance.toString()
            "today_left" -> v.todayLeft.toString()
            "month_left" -> v.monthLeft.toString()
            "streak" -> v.streak.toString()
            "next_streak" -> v.nextStreak.toString()
            "streak_bonus" -> v.streakBonus.toString()
            else -> m.value
        }
    }
}
