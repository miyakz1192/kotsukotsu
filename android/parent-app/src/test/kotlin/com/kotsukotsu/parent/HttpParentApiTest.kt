package com.kotsukotsu.parent

import com.kotsukotsu.core.api.ApiResult
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** 実際の通信の形（server/app/api/v1/schemas.py と合わせる）。 */
class HttpParentApiTest {
    private lateinit var server: MockWebServer
    private var token: String? = "tok-1"
    private val api get() = HttpParentApi(server.url("/").toString(), { token },
        OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS).build())

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    @Test fun withdrawalRequestShapeAndBearer() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"transaction_id":"t1","recorded_at":"2026-11-14T03:00:00Z","balance":-80}"""))
        val r = api.withdraw(WithdrawalRequest("t1", "c1", 500, "2026-11-14T03:00:00Z", wasOffline = true))
        assertEquals(ApiResult.Ok(TxResponse(-80), 201), r)
        val req = server.takeRequest()
        assertEquals("/api/v1/withdrawals", req.path)
        assertEquals("Bearer tok-1", req.getHeader("Authorization"))
        val body = req.body.readUtf8()
        for (s in listOf("\"transaction_id\":\"t1\"", "\"child_user_id\":\"c1\"", "\"points\":500",
            "\"occurred_at_device\":\"2026-11-14T03:00:00Z\"", "\"was_offline\":true")) assertTrue(body, body.contains(s))
    }

    @Test fun adjustmentRequestShape() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"adjustment_id":"a1","recorded_at":"x","balance":5}"""))
        api.adjust(AdjustmentRequest("a1", "c1", -120, "おかし", "2026-11-14T03:00:00Z", wasOffline = false))
        val body = server.takeRequest().body.readUtf8()
        for (s in listOf("\"adjustment_id\":\"a1\"", "\"points\":-120", "\"note\":\"おかし\"", "\"was_offline\":false")) assertTrue(body, body.contains(s))
    }

    @Test fun errorsAreParsed() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":"TRANSACTION_FAILED","message":"もう一度"}}"""))
        assertEquals(ApiResult.HttpError(503, "TRANSACTION_FAILED", "もう一度"), api.adjust(AdjustmentRequest("a", "c", 1, null, "t", false)))
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>"))
        assertEquals(ApiResult.HttpError(502, null, null), api.childrenSummary())
    }

    @Test fun timeoutAndNonServerAreNetworkErrors() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) // REQ-APP-08
        assertTrue(api.withdraw(WithdrawalRequest("t", "c", 1, "t", false)) is ApiResult.NetworkError)
        server.enqueue(MockResponse().setBody("<html>Wi-Fi login</html>")) // 公衆Wi-Fiのログイン画面など
        assertTrue(api.childrenSummary() is ApiResult.NetworkError)
        server.shutdown()
        assertTrue(api.childrenSummary() is ApiResult.NetworkError)
    }

    @Test fun loginHasNoBearerAndParsesSummaryAndLedger() = runTest {
        token = null
        server.enqueue(MockResponse().setBody("""{"token":"T","must_change_password":true}"""))
        assertEquals(LoginResponse("T", true), (api.login(LoginRequest("papa", "pw")) as ApiResult.Ok).value)
        assertNull(server.takeRequest().getHeader("Authorization"))
        server.enqueue(MockResponse().setBody("""{"children":[{"user_id":"c1","display_name":"そうちゃん","is_active":true,"balance":420,
            "streak_days":3,"month_earned":100,"month_cap":1500,"month_remaining":1400,"cumulative_accuracy":0.9,"last_play_at":null,"as_of":"x"}]}"""))
        val c = (api.childrenSummary() as ApiResult.Ok).value.children.single()
        assertEquals(ChildCard("c1", "そうちゃん", 420, 3, 100, 1500, null), c)
        server.enqueue(MockResponse().setBody("""{"balance":420,"entries":[{"entry_id":"e1","type":"withdrawal","points":-80,"play_id":null,
            "transaction_id":"t1","actor_user_id":"p","occurred_at":"2026-11-14T03:00:00Z","recorded_at":"x","play_date":null,"note":null}]}"""))
        val e = (api.ledger("c1") as ApiResult.Ok).value.entries.single()
        assertTrue(e.cancellable())
        assertEquals("/api/v1/children/summary", server.takeRequest().path)
        assertEquals("/api/v1/children/c1/ledger", server.takeRequest().path)
    }
}
