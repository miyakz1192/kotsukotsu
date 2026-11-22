package com.kotsukotsu.core.sync

import com.kotsukotsu.core.api.AnswerDto
import com.kotsukotsu.core.api.ApiJson
import com.kotsukotsu.core.api.Summary
import com.kotsukotsu.core.data.PendingPlay
import com.kotsukotsu.core.points.AnswerIn
import com.kotsukotsu.core.points.Modes
import com.kotsukotsu.core.points.PlayInput
import com.kotsukotsu.core.points.PointEngine
import com.kotsukotsu.core.points.PointRules
import com.kotsukotsu.core.points.PriorState
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

val TOKYO: ZoneId = ZoneId.of("Asia/Tokyo")

fun tokyoDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(TOKYO).toLocalDate()

/** 画面に出す値。「最後に同期したサーバの値＋未送信分のローカル計算」（docs/06 1.5）。 */
data class LocalView(
    val today: LocalDate,
    val streakDays: Int,
    val cumulativeAccuracy: Double,
    val balance: Int,
    val todayQuestions: Int,
    val todayStudyPoints: Int,
    val monthEarned: Int,
    val nextSequentialDan: Int,
    val pendingCount: Int,
    /** 今日これからプレイするときの PriorState */
    val prior: PriorState,
    val rules: PointRules,
) {
    val questionsLeft: Int get() = maxOf(0, rules.dailyQuestionCap - todayQuestions)
    val todayPointsLeft: Int get() = maxOf(0, rules.dailyPointCap - todayStudyPoints)
    val monthLeft: Int get() = maxOf(0, rules.monthlyCap - monthEarned)

    /** mode で卒業済みの段（docs/03 5.2.1）。卒業が無い設定・対象外のモードなら空。 */
    fun graduatedDans(mode: String): Set<Int> {
        val g = rules.graduation ?: return emptySet()
        if (mode !in g.modes) return emptySet()
        return prior.achieved.filter { it.second == g.rate }.map { it.first }.toSet()
    }
}

fun decodeAnswers(json: String): List<AnswerDto> = ApiJson.decodeFromString(ListSerializer(AnswerDto.serializer()), json)

object LocalCalculator {
    fun build(rules: PointRules, summary: Summary?, pending: List<PendingPlay>, today: LocalDate): LocalView {
        val dayQ = HashMap<LocalDate, Int>()
        val dayPts = HashMap<LocalDate, Int>()
        val month = HashMap<YearMonth, Int>()
        val played = HashSet<LocalDate>()
        var state = PriorState()
        var balance = 0
        var accuracy = 0.0
        var nextSeq = 1

        if (summary != null) {
            val sd = runCatching { Instant.parse(summary.asOf).atZone(TOKYO).toLocalDate() }.getOrDefault(today)
            dayQ[sd] = summary.today.questions
            dayPts[sd] = summary.today.studyPoints
            month[YearMonth.from(sd)] = summary.month.earned
            // 連続日数から、プレイ済みの日を復元する（連続日数ボーナスの計算に必要な範囲だけ）
            val anchor = if (summary.today.firstPlayDone) sd else sd.minusDays(1)
            for (k in 0 until summary.streakDays) played += anchor.minusDays(k.toLong())
            if (summary.today.firstPlayDone) played += sd
            state = PriorState(
                danHistory = summary.danRecent.mapKeys { it.key.toInt() },
                achieved = summary.selfBestAchieved.map { it.dan to it.threshold }.toSet(),
            )
            balance = summary.balance
            accuracy = summary.cumulativeAccuracy
            nextSeq = summary.nextSequentialDan
        }

        var pendCorrect = 0
        var pendTotal = 0
        for (p in pending) {
            val d = tokyoDate(p.endedAtMillis)
            val answers = decodeAnswers(p.answersJson).sortedBy { it.seq }.map { AnswerIn(it.a, it.b, it.answer == it.a * it.b) }
            val prior = state.copy(
                dayQuestions = dayQ[d] ?: 0, dayStudyPoints = dayPts[d] ?: 0,
                monthEarned = month[YearMonth.from(d)] ?: 0, playedDates = played.toSet(),
            )
            val input = PlayInput(p.mode, d, answers)
            val r = PointEngine.processPlay(rules, prior, input)
            state = PointEngine.advance(prior, input, r)
            played += d
            dayQ[d] = (dayQ[d] ?: 0) + r.overCap.count { !it }
            dayPts[d] = (dayPts[d] ?: 0) + r.study
            month[YearMonth.from(d)] = (month[YearMonth.from(d)] ?: 0) + r.total
            balance += r.total
            if (p.mode == Modes.SEQUENTIAL && p.dan != null) nextSeq = p.dan % 9 + 1
            answers.forEachIndexed { i, a -> if (!r.overCap[i]) { pendTotal++; if (a.isCorrect) pendCorrect++ } }
        }
        if (summary == null && pendTotal > 0) accuracy = pendCorrect.toDouble() / pendTotal

        val prior = state.copy(
            dayQuestions = dayQ[today] ?: 0, dayStudyPoints = dayPts[today] ?: 0,
            monthEarned = month[YearMonth.from(today)] ?: 0, playedDates = played.toSet(),
        )
        return LocalView(
            today = today,
            streakDays = PointEngine.computeCurrentStreak(today, played),
            cumulativeAccuracy = accuracy,
            balance = balance,
            todayQuestions = prior.dayQuestions,
            todayStudyPoints = prior.dayStudyPoints,
            monthEarned = prior.monthEarned,
            nextSequentialDan = nextSeq,
            pendingCount = pending.size,
            prior = prior,
            rules = rules,
        )
    }
}
