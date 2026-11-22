package com.kotsukotsu.core.api

import com.kotsukotsu.core.points.PointRules
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import java.io.IOException
import java.util.concurrent.TimeUnit

internal interface GameApiService {
    @GET("api/v1/family/children") suspend fun children(): Response<ChildrenResponse>
    @POST("api/v1/game-registrations") suspend fun register(@Body body: RegistrationRequest): Response<RegistrationResponse>
    @GET("api/v1/settings") suspend fun settings(): Response<PointRules>
    @GET("api/v1/players/me/summary") suspend fun summary(): Response<Summary>
    @POST("api/v1/play-sessions") suspend fun postPlay(@Body body: PlaySessionRequest): Response<PlaySessionResponse>
    @GET("api/v1/notification-settings") suspend fun notificationSettings(): Response<com.kotsukotsu.core.notify.NotifySettings>
}

val ApiJson = Json { ignoreUnknownKeys = true; explicitNulls = true; encodeDefaults = true }

/** 入力されたサーバのアドレスを正規化する（例 "192.168.1.10:8000" → "http://192.168.1.10:8000/"）。 */
fun normalizeBaseUrl(input: String): String? {
    var s = input.trim()
    if (s.isEmpty()) return null
    if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
    if (!s.endsWith("/")) s += "/"
    return s.toHttpUrlOrNull()?.toString()
}

class RetrofitGameApi(
    baseUrl: String,
    gameTitleId: String,
    appVersion: String,
    tokenProvider: () -> String?,
) : GameApiPort {
    private val service: GameApiService

    init {
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val b = chain.request().newBuilder()
                    .header("X-Game-Title-Id", gameTitleId)
                    .header("X-App-Version", appVersion)
                tokenProvider()?.let { b.header("Authorization", "Bearer $it") }
                chain.proceed(b.build())
            }
            .build()
        service = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(ApiJson.asConverterFactory("application/json; charset=UTF-8".toMediaType()))
            .build()
            .create(GameApiService::class.java)
    }

    private suspend fun <T> call(block: suspend () -> Response<T>): ApiResult<T> = try {
        val r = block()
        val body = r.body()
        if (r.isSuccessful && body != null) {
            ApiResult.Ok(body, r.code())
        } else {
            val err = r.errorBody()?.string()?.let { runCatching { ApiJson.decodeFromString(ErrorBody.serializer(), it) }.getOrNull() }
            ApiResult.HttpError(r.code(), err?.error?.code, err?.error?.message)
        }
    } catch (e: IOException) {
        ApiResult.NetworkError(e) // 通信不可・タイムアウト
    } catch (e: kotlinx.serialization.SerializationException) {
        ApiResult.HttpError(-1, "BAD_RESPONSE", e.message)
    }

    override suspend fun children() = call { service.children() }
    override suspend fun register(req: RegistrationRequest) = call { service.register(req) }
    override suspend fun settings() = call { service.settings() }
    override suspend fun summary() = call { service.summary() }
    override suspend fun postPlay(req: PlaySessionRequest) = call { service.postPlay(req) }
    override suspend fun notificationSettings() = call { service.notificationSettings() }
}
