package com.kotsukotsu.core.api

import com.kotsukotsu.core.points.PointRules
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 共通API v1 のデータ形式（docs/05、spec/openapi.yaml）。 */
@Serializable
data class Child(@SerialName("user_id") val userId: String, @SerialName("display_name") val displayName: String)

@Serializable
data class ChildrenResponse(val children: List<Child>)

@Serializable
data class RegistrationRequest(
    @SerialName("game_title_id") val gameTitleId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("device_id") val deviceId: String,
)

@Serializable
data class RegistrationResponse(
    @SerialName("registration_id") val registrationId: String,
    @SerialName("device_token") val deviceToken: String,
    val settings: PointRules,
)

@Serializable
data class Today(
    val questions: Int,
    @SerialName("question_cap") val questionCap: Int,
    @SerialName("study_points") val studyPoints: Int,
    @SerialName("point_cap") val pointCap: Int,
    @SerialName("first_play_done") val firstPlayDone: Boolean,
)

@Serializable
data class Month(val earned: Int, val cap: Int)

@Serializable
data class DanThreshold(val dan: Int, val threshold: Int)

@Serializable
data class Summary(
    @SerialName("as_of") val asOf: String,
    @SerialName("streak_days") val streakDays: Int,
    @SerialName("cumulative_accuracy") val cumulativeAccuracy: Double,
    val balance: Int,
    val today: Today,
    val month: Month,
    @SerialName("dan_recent") val danRecent: Map<String, List<Boolean>> = emptyMap(),
    @SerialName("self_best_achieved") val selfBestAchieved: List<DanThreshold> = emptyList(),
    @SerialName("next_sequential_dan") val nextSequentialDan: Int = 1,
    @SerialName("settings_version") val settingsVersion: Int = 0,
)

@Serializable
data class AnswerDto(
    val seq: Int,
    val a: Int,
    val b: Int,
    val answer: Int?,
    @SerialName("is_correct") val isCorrect: Boolean,
    @SerialName("elapsed_ms") val elapsedMs: Long? = null,
)

@Serializable
data class PlaySessionRequest(
    @SerialName("play_id") val playId: String,
    val mode: String,
    val dan: Int?,
    @SerialName("started_at_device") val startedAtDevice: String,
    @SerialName("ended_at_device") val endedAtDevice: String,
    @SerialName("was_offline") val wasOffline: Boolean,
    @SerialName("client_rule_version") val clientRuleVersion: Int?,
    val answers: List<AnswerDto>,
)

@Serializable
data class Awarded(
    val study: Int,
    @SerialName("streak_bonus") val streakBonus: Int,
    @SerialName("self_best_bonus") val selfBestBonus: Int,
) {
    val total: Int get() = study + streakBonus + selfBestBonus
}

@Serializable
data class PlaySessionResponse(
    @SerialName("play_id") val playId: String,
    @SerialName("play_date") val playDate: String,
    val awarded: Awarded,
    @SerialName("self_best_new") val selfBestNew: List<DanThreshold> = emptyList(),
    val summary: Summary,
)

@Serializable
data class ErrorBody(val error: ErrorDetail)

@Serializable
data class ErrorDetail(val code: String, val message: String)
