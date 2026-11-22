package com.kotsukotsu.core.notify

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** GET /api/v1/notification-settings（docs/05、docs/06 1.7）。端末に保存し、オフライン中は最後の値で通知する。 */
@Serializable
data class NotifySettings(
    val enabled: Boolean = true,
    @SerialName("inactive_days") val inactiveDays: Int = 1,
    @SerialName("weekday_times") val weekdayTimes: List<String> = emptyList(),
    @SerialName("holiday_times") val holidayTimes: List<String> = emptyList(),
    @SerialName("point_message_every") val pointMessageEvery: Int = 0,
    val messages: List<NotifyMessage> = emptyList(),
    val holidays: List<String> = emptyList(),
    @SerialName("holidays_until") val holidaysUntil: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class NotifyMessage(
    @SerialName("message_id") val messageId: String,
    val kind: String, // encourage / points
    val text: String,
)
