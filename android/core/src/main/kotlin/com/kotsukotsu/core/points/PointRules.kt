package com.kotsukotsu.core.points

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** ポイントルールの設定値（docs/03 1章）。サーバの GET /settings をそのまま受け取る。値をコードに埋め込まない。 */
@Serializable
data class PointRules(
    val version: Int = 0,
    val timezone: String = "Asia/Tokyo",
    @SerialName("points_per_correct") val pointsPerCorrect: Map<String, Int>,
    @SerialName("streak_bonus_per_day") val streakBonusPerDay: Int,
    @SerialName("daily_point_cap") val dailyPointCap: Int,
    @SerialName("daily_question_cap") val dailyQuestionCap: Int,
    @SerialName("monthly_cap") val monthlyCap: Int,
    @SerialName("self_best") val selfBest: SelfBest,
    /** 卒業した段（docs/03 5.2.1）。null なら卒業なし（古い版）。 */
    val graduation: Graduation? = null,
)

@Serializable
data class Graduation(
    val modes: List<String>,
    val rate: Int,
    @SerialName("points_per_correct") val pointsPerCorrect: Int,
)

@Serializable
data class SelfBest(val window: Int, val thresholds: List<Threshold>)

@Serializable
data class Threshold(val rate: Int, val bonus: Int)

object Modes {
    const val SEQUENTIAL = "training_sequential"
    const val SELECT = "training_select"
    const val CHALLENGE = "challenge"
}
