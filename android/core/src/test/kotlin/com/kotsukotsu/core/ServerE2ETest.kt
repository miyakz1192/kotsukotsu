package com.kotsukotsu.core

import com.kotsukotsu.core.api.ApiResult
import com.kotsukotsu.core.api.GameApiPort
import com.kotsukotsu.core.api.PlaySessionRequest
import com.kotsukotsu.core.api.RegistrationRequest
import com.kotsukotsu.core.api.RetrofitGameApi
import com.kotsukotsu.core.sync.SyncManager
import com.kotsukotsu.core.sync.SyncOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException

/**
 * 実サーバを相手にした通しテスト。環境変数 KOTSU_E2E_URL（例 http://127.0.0.1:8000/）があるときだけ動く。
 * サーバには家族と子供が1人以上登録済みで、その子供の kuku-challenge 登録がないこと。
 * 機内モードを「通信不可を返すラッパ」で再現する。
 */
class ServerE2ETest {
    private val url = System.getenv("KOTSU_E2E_URL")

    private class Switchable(val real: GameApiPort) : GameApiPort {
        var offline = false
        private fun <T> off(): ApiResult<T> = ApiResult.NetworkError(IOException("airplane mode"))
        override suspend fun children() = if (offline) off() else real.children()
        override suspend fun register(req: RegistrationRequest) = if (offline) off() else real.register(req)
        override suspend fun settings() = if (offline) off() else real.settings()
        override suspend fun summary() = if (offline) off() else real.summary()
        override suspend fun postPlay(req: PlaySessionRequest) = if (offline) off() else real.postPlay(req)
        override suspend fun notificationSettings() = if (offline) off() else real.notificationSettings()
    }

    @Test
    fun airplaneModeThreePlaysThenSyncAgainstRealServer() = runBlocking {
        assumeTrue("KOTSU_E2E_URL が未設定のためスキップ", !url.isNullOrBlank())
        val prefs = MemoryPrefs()
        val store = MemoryStore()
        var api: Switchable? = null
        val repo = GameRepository(prefs, store, { u ->
            Switchable(RetrofitGameApi(u, "kuku-challenge", "1.0.0") { prefs.deviceToken }).also { api = it }
        })
        val kids = (repo.fetchChildren(url!!) as ApiResult.Ok).value
        val reg = repo.register(url, kids.first(), "kuku-challenge")
        assertTrue("登録できること: $reg", reg is ApiResult.Ok)
        assertEquals(SyncOutcome.DONE, repo.syncAll())
        val before = repo.localView()

        // 機内モードで3回プレイ
        api!!.offline = true
        val dans = listOf(2, 5, 8)
        for (d in dans) {
            val now = System.currentTimeMillis()
            repo.recordPlay("training_select", d, (1..9).map { b -> AnsweredQuestion(d, b, if (b <= 7) d * b else 0, 900) }, now - 30_000, now)
            assertEquals(SyncOutcome.NETWORK, repo.sync.flush())
            Thread.sleep(20)
        }
        assertEquals(3, store.pending().size)
        val local = repo.localView()
        assertEquals(before.todayQuestions + 27, local.todayQuestions)

        // 機内モード解除 → 古い順に送信され、サーバに反映される
        api!!.offline = false
        val firstReq = SyncManager.toRequest(store.pending().first())
        assertEquals(SyncOutcome.DONE, repo.syncAll())
        assertTrue(store.pending().isEmpty())
        // おしらせ通知の設定も受け取れている（docs/06 1.7）
        val notify = prefs.notifySettings!!
        assertTrue(notify.messages.isNotEmpty() && notify.weekdayTimes.isNotEmpty())
        assertTrue(repo.nextReminderAt(System.currentTimeMillis()) != null)
        val server = prefs.summary!!
        assertEquals(local.todayQuestions, server.today.questions)
        assertEquals(local.balance, server.balance)            // ローカル計算とサーバ確定値が一致
        assertEquals(local.todayStudyPoints, server.today.studyPoints)
        assertFalse(prefs.pointsUpdatedNotice)
        assertEquals(dans, (1..9).filter { server.danRecent[it.toString()] != null }.filter { it in dans })

        // 同じ play_id を再送しても二重にならない（200 で最初の結果）
        val again = api!!.real.postPlay(firstReq)
        assertEquals(200, (again as ApiResult.Ok).status)
        assertEquals(server.balance, again.value.summary.balance)
    }
}
