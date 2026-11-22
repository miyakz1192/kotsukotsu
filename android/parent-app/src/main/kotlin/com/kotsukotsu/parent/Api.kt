package com.kotsukotsu.parent

import com.kotsukotsu.core.api.ApiJson
import com.kotsukotsu.core.api.ApiResult
import com.kotsukotsu.core.api.ErrorBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

// ---------- 共通API v1 の親向けの形（docs/05、spec/openapi.yaml） ----------

@Serializable
data class LoginRequest(@SerialName("login_id") val loginId: String, val password: String)

@Serializable
data class LoginResponse(val token: String, @SerialName("must_change_password") val mustChangePassword: Boolean)

@Serializable
data class PasswordRequest(@SerialName("current_password") val current: String, @SerialName("new_password") val new: String)

@Serializable
data class ChildCard(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    val balance: Int,
    @SerialName("streak_days") val streakDays: Int = 0,
    @SerialName("month_earned") val monthEarned: Int = 0,
    @SerialName("month_cap") val monthCap: Int = 0,
    @SerialName("last_play_at") val lastPlayAt: String? = null,
)

@Serializable
data class ChildrenSummary(val children: List<ChildCard>)

@Serializable
data class LedgerEntry(
    @SerialName("entry_id") val entryId: String,
    val type: String, // study / streak_bonus / self_best_bonus / withdrawal / adjustment
    val points: Int,
    @SerialName("transaction_id") val transactionId: String? = null,
    @SerialName("occurred_at") val occurredAt: String,
    val note: String? = null,
)

@Serializable
data class LedgerResponse(val entries: List<LedgerEntry>, val balance: Int)

@Serializable
data class WithdrawalRequest(
    @SerialName("transaction_id") val transactionId: String,
    @SerialName("child_user_id") val childUserId: String,
    val points: Int,
    @SerialName("occurred_at_device") val occurredAtDevice: String,
    @SerialName("was_offline") val wasOffline: Boolean,
    val note: String? = null,
)

@Serializable
data class AdjustmentRequest(
    @SerialName("adjustment_id") val adjustmentId: String,
    @SerialName("child_user_id") val childUserId: String,
    val points: Int,
    val note: String?,
    @SerialName("occurred_at_device") val occurredAtDevice: String,
    @SerialName("was_offline") val wasOffline: Boolean,
)

/** 引き出し・調整の応答（どちらも残高を返す）。 */
@Serializable
data class TxResponse(val balance: Int)

@Serializable
data class AppInfo(
    @SerialName("app_id") val appId: String,
    val kind: String,
    @SerialName("version_code") val versionCode: Long,
    @SerialName("version_name") val versionName: String? = null,
)

@Serializable
data class AppsList(val apps: List<AppInfo>)

/** 親用アプリが使うAPI。テストではフェイクに差し替える。 */
interface ParentApiPort {
    suspend fun login(req: LoginRequest): ApiResult<LoginResponse>
    suspend fun changePassword(req: PasswordRequest): ApiResult<Unit>
    suspend fun childrenSummary(): ApiResult<ChildrenSummary>
    suspend fun ledger(childUserId: String): ApiResult<LedgerResponse>
    suspend fun withdraw(req: WithdrawalRequest): ApiResult<TxResponse>
    suspend fun adjust(req: AdjustmentRequest): ApiResult<TxResponse>
    suspend fun apps(): ApiResult<AppsList>
}

/**
 * OkHttp による実装。外出先では家のサーバに届かないので、待たせないよう接続のタイムアウトを短くする。
 * 通信不可・タイムアウトは NetworkError（REQ-APP-08）。
 */
class HttpParentApi(
    baseUrl: String,
    private val tokenProvider: () -> String?,
    client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build(),
) : ParentApiPort {
    private val base = baseUrl.toHttpUrl()
    private val http = client
    private val jsonType = "application/json".toMediaType()

    override suspend fun login(req: LoginRequest) =
        call("POST", "api/v1/auth/login", ApiJson.encodeToString(LoginRequest.serializer(), req), LoginResponse.serializer())

    override suspend fun changePassword(req: PasswordRequest): ApiResult<Unit> =
        call<Unit>("POST", "api/v1/auth/password", ApiJson.encodeToString(PasswordRequest.serializer(), req), null)

    override suspend fun childrenSummary() = call("GET", "api/v1/children/summary", null, ChildrenSummary.serializer())

    override suspend fun ledger(childUserId: String) =
        call("GET", "api/v1/children/$childUserId/ledger", null, LedgerResponse.serializer())

    override suspend fun withdraw(req: WithdrawalRequest) =
        call("POST", "api/v1/withdrawals", ApiJson.encodeToString(WithdrawalRequest.serializer(), req), TxResponse.serializer())

    override suspend fun adjust(req: AdjustmentRequest) =
        call("POST", "api/v1/adjustments", ApiJson.encodeToString(AdjustmentRequest.serializer(), req), TxResponse.serializer())

    override suspend fun apps() = call("GET", "api/v1/apps", null, AppsList.serializer())

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> call(method: String, path: String, body: String?, ser: KSerializer<T>?): ApiResult<T> =
        withContext(Dispatchers.IO) {
            try {
                val b = Request.Builder().url(base.resolve(path)!!)
                tokenProvider()?.let { b.header("Authorization", "Bearer $it") }
                if (method == "POST") b.post((body ?: "{}").toRequestBody(jsonType)) else b.get()
                http.newCall(b.build()).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    if (r.isSuccessful) {
                        val value = if (ser == null) Unit as T else ApiJson.decodeFromString(ser, text)
                        ApiResult.Ok(value, r.code)
                    } else {
                        val err = runCatching { ApiJson.decodeFromString(ErrorBody.serializer(), text).error }.getOrNull()
                        ApiResult.HttpError(r.code, err?.code, err?.message)
                    }
                }
            } catch (e: IOException) {
                ApiResult.NetworkError(e)
            } catch (e: kotlinx.serialization.SerializationException) {
                ApiResult.NetworkError(e) // サーバでないものに繋がった（公衆Wi-Fiのログイン画面など）
            } catch (e: IllegalArgumentException) {
                ApiResult.NetworkError(e)
            }
        }
}
