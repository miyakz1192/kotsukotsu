package com.kotsukotsu.core.data

import android.content.Context
import android.content.SharedPreferences
import com.kotsukotsu.core.api.ApiJson
import com.kotsukotsu.core.api.Summary
import com.kotsukotsu.core.notify.NotifySettings
import com.kotsukotsu.core.points.PointRules
import java.util.UUID

/** 端末に保存する設定とサーバ値のキャッシュ。 */
interface GamePrefs {
    var baseUrl: String?
    val deviceId: String
    var deviceToken: String?
    var userId: String?
    var displayName: String?
    var rules: PointRules?
    var summary: Summary?
    /** summary を受け取った時刻（端末時刻 millis） */
    var summarySyncedAt: Long
    var soundOn: Boolean
    /** サーバ確定値がローカル計算と違った（次回起動時に1回だけ知らせる） */
    var pointsUpdatedNotice: Boolean
    /** おしらせ通知の設定（サーバから受け取った最後の値、docs/06 1.7） */
    var notifySettings: NotifySettings?
    /** 最後にプレイした日（日本時間、未送信を含む）。"2026-11-17" */
    var lastPlayDate: String?
    /** 通知を出した回数（ポイント入りの頻度に使う） */
    var notifyCounter: Int
    var lastNotifyMessageId: String?
    /** 通知の許可を一度尋ねたか */
    var notifyPermissionAsked: Boolean

    /** 登録解除（401）時：トークンだけ消す。未送信キューと「誰の端末か」は残す */
    fun clearRegistration() {
        deviceToken = null
    }
}

class SharedGamePrefs(context: Context) : GamePrefs {
    private val p: SharedPreferences = context.getSharedPreferences("kotsukotsu_game", Context.MODE_PRIVATE)

    private fun str(k: String) = p.getString(k, null)
    private fun put(k: String, v: String?) = p.edit().apply { if (v == null) remove(k) else putString(k, v) }.apply()

    override var baseUrl: String? get() = str("base_url"); set(v) = put("base_url", v)
    override val deviceId: String
        get() = str("device_id") ?: UUID.randomUUID().toString().also { p.edit().putString("device_id", it).commit() }
    override var deviceToken: String? get() = str("device_token"); set(v) { p.edit().apply { if (v == null) remove("device_token") else putString("device_token", v) }.commit() }
    override var userId: String? get() = str("user_id"); set(v) = put("user_id", v)
    override var displayName: String? get() = str("display_name"); set(v) = put("display_name", v)
    override var rules: PointRules?
        get() = str("rules")?.let { runCatching { ApiJson.decodeFromString(PointRules.serializer(), it) }.getOrNull() }
        set(v) = put("rules", v?.let { ApiJson.encodeToString(PointRules.serializer(), it) })
    override var summary: Summary?
        get() = str("summary")?.let { runCatching { ApiJson.decodeFromString(Summary.serializer(), it) }.getOrNull() }
        set(v) = put("summary", v?.let { ApiJson.encodeToString(Summary.serializer(), it) })
    override var summarySyncedAt: Long get() = p.getLong("summary_synced_at", 0); set(v) = p.edit().putLong("summary_synced_at", v).apply()
    override var soundOn: Boolean get() = p.getBoolean("sound_on", true); set(v) = p.edit().putBoolean("sound_on", v).apply()
    override var pointsUpdatedNotice: Boolean get() = p.getBoolean("points_updated", false); set(v) = p.edit().putBoolean("points_updated", v).apply()
    override var notifySettings: NotifySettings?
        get() = str("notify_settings")?.let { runCatching { ApiJson.decodeFromString(NotifySettings.serializer(), it) }.getOrNull() }
        set(v) = put("notify_settings", v?.let { ApiJson.encodeToString(NotifySettings.serializer(), it) })
    override var lastPlayDate: String? get() = str("last_play_date"); set(v) = put("last_play_date", v)
    override var notifyCounter: Int get() = p.getInt("notify_counter", 0); set(v) = p.edit().putInt("notify_counter", v).apply()
    override var lastNotifyMessageId: String? get() = str("last_notify_message"); set(v) = put("last_notify_message", v)
    override var notifyPermissionAsked: Boolean get() = p.getBoolean("notify_asked", false); set(v) = p.edit().putBoolean("notify_asked", v).apply()
}
