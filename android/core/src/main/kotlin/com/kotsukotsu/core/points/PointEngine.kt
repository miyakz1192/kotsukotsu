package com.kotsukotsu.core.points

import java.time.LocalDate

/**
 * ポイント計算（docs/03 4〜5章）。サーバ app/domain/points/engine.py と同じロジック。
 * ゲームでは演出用の即時計算に使い、確定値は常にサーバの値を正とする。
 */
data class AnswerIn(val a: Int, val b: Int, val isCorrect: Boolean)

data class PlayInput(val mode: String, val playDate: LocalDate, val answers: List<AnswerIn>)

data class PriorState(
    val dayQuestions: Int = 0,
    val dayStudyPoints: Int = 0,
    val monthEarned: Int = 0,
    val playedDates: Set<LocalDate> = emptySet(),
    /** 段ごとの回答履歴（回答順、over_cap 除く）。直近 window 件以上を含めること */
    val danHistory: Map<Int, List<Boolean>> = emptyMap(),
    /** (段, 到達点rate) */
    val achieved: Set<Pair<Int, Int>> = emptySet(),
)

data class SelfBestAward(val dan: Int, val threshold: Int, val bonus: Int)

data class PlayResult(
    val study: Int,
    val streakBonus: Int,
    val selfBest: List<SelfBestAward>,
    val overCap: List<Boolean>,
    val isFirstPlayOfDay: Boolean,
    val streakDays: Int,
) {
    val selfBestTotal: Int get() = selfBest.sumOf { it.bonus }
    val total: Int get() = study + streakBonus + selfBestTotal
}

object PointEngine {
    /** playDate を含み、同じ月の中で連続する日数（毎月1日にリセット）。 */
    fun computeStreak(playDate: LocalDate, playedDates: Set<LocalDate>): Int {
        var n = 1
        var d = playDate.minusDays(1)
        while (d.monthValue == playDate.monthValue && d in playedDates) {
            n++
            d = d.minusDays(1)
        }
        return n
    }

    /** 表示用：今日プレイ済みなら今日まで、未プレイなら昨日まで（同じ月のみ）。 */
    fun computeCurrentStreak(today: LocalDate, playedDates: Set<LocalDate>): Int {
        if (today in playedDates) return computeStreak(today, playedDates)
        val y = today.minusDays(1)
        if (y.monthValue == today.monthValue && y in playedDates) return computeStreak(y, playedDates)
        return 0
    }

    fun processPlay(rules: PointRules, prior: PriorState, play: PlayInput): PlayResult {
        val perMode = rules.pointsPerCorrect.getValue(play.mode)
        val grad = rules.graduation
        // 5.2.1 プレイの処理前に卒業済みの段
        val graduated: Set<Int> = if (grad != null && play.mode in grad.modes) {
            prior.achieved.filter { it.second == grad.rate }.map { it.first }.toSet()
        } else emptySet()
        var dayQ = prior.dayQuestions
        var dayPts = prior.dayStudyPoints
        var month = prior.monthEarned

        // 5.1 問題数上限 / 5.2 正解ポイント
        val overCap = ArrayList<Boolean>(play.answers.size)
        var study = 0
        for (ans in play.answers) {
            if (dayQ >= rules.dailyQuestionCap) {
                overCap += true
                continue
            }
            overCap += false
            dayQ++
            if (ans.isCorrect) {
                val per = if (grad != null && ans.a in graduated) grad.pointsPerCorrect else perMode
                val roomDay = rules.dailyPointCap - dayPts
                val roomMonth = rules.monthlyCap - month
                val pts = maxOf(0, minOf(per, roomDay, roomMonth))
                dayPts += pts
                month += pts
                study += pts
            }
        }

        // 5.3 連続日数ボーナス
        val first = play.playDate !in prior.playedDates
        var streakDays = 0
        var streakBonus = 0
        if (first) {
            streakDays = computeStreak(play.playDate, prior.playedDates)
            streakBonus = maxOf(0, minOf(streakDays * rules.streakBonusPerDay, rules.monthlyCap - month))
            month += streakBonus
        }

        // 5.4 自己ベストボーナス
        val counted = play.answers.filterIndexed { i, _ -> !overCap[i] }
        val window = rules.selfBest.window
        val awards = mutableListOf<SelfBestAward>()
        for (dan in counted.map { it.a }.toSortedSet()) {
            val hist = prior.danHistory[dan].orEmpty() + counted.filter { it.a == dan }.map { it.isCorrect }
            if (hist.size < window) continue
            val correct = hist.takeLast(window).count { it }
            for (th in rules.selfBest.thresholds) {
                if (correct * 100 >= th.rate * window && (dan to th.rate) !in prior.achieved) {
                    val bonus = maxOf(0, minOf(th.bonus, rules.monthlyCap - month))
                    month += bonus
                    awards += SelfBestAward(dan, th.rate, bonus)
                }
            }
        }
        return PlayResult(study, streakBonus, awards, overCap, first, streakDays)
    }

    /** 処理後の状態（次のプレイの PriorState）。月・日が変わる場合は呼び出し側で作り直す。 */
    fun advance(prior: PriorState, play: PlayInput, r: PlayResult): PriorState {
        val hist = prior.danHistory.toMutableMap()
        play.answers.forEachIndexed { i, a ->
            if (!r.overCap[i]) hist[a.a] = hist[a.a].orEmpty() + a.isCorrect
        }
        return prior.copy(
            dayQuestions = prior.dayQuestions + r.overCap.count { !it },
            dayStudyPoints = prior.dayStudyPoints + r.study,
            monthEarned = prior.monthEarned + r.total,
            playedDates = prior.playedDates + play.playDate,
            danHistory = hist,
            achieved = prior.achieved + r.selfBest.map { it.dan to it.threshold },
        )
    }
}
