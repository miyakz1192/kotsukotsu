package com.kotsukotsu.core.api

/** API 呼び出しの結果。通信不可・タイムアウトは NetworkError（docs/06 1.5）。 */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T, val status: Int) : ApiResult<T>
    data class HttpError(val status: Int, val code: String?, val message: String?) : ApiResult<Nothing>
    data class NetworkError(val cause: Throwable) : ApiResult<Nothing>
}

/** ゲームが使うAPI。テストではフェイクに差し替える。 */
interface GameApiPort {
    suspend fun children(): ApiResult<ChildrenResponse>
    suspend fun register(req: RegistrationRequest): ApiResult<RegistrationResponse>
    suspend fun settings(): ApiResult<com.kotsukotsu.core.points.PointRules>
    suspend fun summary(): ApiResult<Summary>
    suspend fun postPlay(req: PlaySessionRequest): ApiResult<PlaySessionResponse>
    suspend fun notificationSettings(): ApiResult<com.kotsukotsu.core.notify.NotifySettings>
}
