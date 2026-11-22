package com.kotsukotsu.parent

import android.content.Context
import com.kotsukotsu.core.api.ApiJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer

/**
 * 端末への保存（SharedPreferences）。未送信の操作は失うと困るので commit() で書く。
 * トークンはアプリ専用の領域に置く（端末の保護は画面ロックに任せる。REQ-APP-12）。
 */
class ParentPrefs(context: Context) : ParentStore {
    private val sp = context.getSharedPreferences("parent", Context.MODE_PRIVATE)

    private fun str(key: String) = sp.getString(key, null)
    private fun put(key: String, v: String?, sync: Boolean = false) {
        val e = sp.edit().putString(key, v)
        if (sync) e.commit() else e.apply()
    }

    override var baseUrl: String?
        get() = str("base_url")
        set(v) = put("base_url", v)
    override var loginId: String?
        get() = str("login_id")
        set(v) = put("login_id", v)
    override var token: String?
        get() = str("token")
        set(v) = put("token", v, sync = true)
    override var children: List<ChildCard>
        get() = decode("children", ListSerializer(ChildCard.serializer())) ?: emptyList()
        set(v) = put("children", ApiJson.encodeToString(ListSerializer(ChildCard.serializer()), v))
    override var syncedAt: String?
        get() = str("synced_at")
        set(v) = put("synced_at", v)
    override var ops: List<PendingOp>
        get() = decode("ops", ListSerializer(PendingOp.serializer())) ?: emptyList()
        set(v) = put("ops", ApiJson.encodeToString(ListSerializer(PendingOp.serializer()), v), sync = true)
    override var cancelled: Set<String>
        get() = decode("cancelled", SetSerializer(String.serializer())) ?: emptySet()
        set(v) = put("cancelled", ApiJson.encodeToString(SetSerializer(String.serializer()), v), sync = true)

    override fun ledger(childUserId: String): List<LedgerEntry>? = decode("ledger_$childUserId", ListSerializer(LedgerEntry.serializer()))
    override fun setLedger(childUserId: String, entries: List<LedgerEntry>) =
        put("ledger_$childUserId", ApiJson.encodeToString(ListSerializer(LedgerEntry.serializer()), entries))

    private fun <T> decode(key: String, ser: kotlinx.serialization.KSerializer<T>): T? =
        str(key)?.let { runCatching { ApiJson.decodeFromString(ser, it) }.getOrNull() }
}
