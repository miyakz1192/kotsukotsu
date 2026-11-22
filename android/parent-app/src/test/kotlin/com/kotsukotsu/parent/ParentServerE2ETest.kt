package com.kotsukotsu.parent

import com.kotsukotsu.core.api.ApiResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

/**
 * 実サーバを相手にした通し（フェーズ4の完了条件）。環境変数があるときだけ動く：
 * KOTSU_E2E_URL（例 http://127.0.0.1:8000/）、KOTSU_E2E_LOGIN、KOTSU_E2E_PASSWORD（パスワード変更済みの親）。
 * サーバには子供が1人以上いること。外出先（サーバ不通）を「通信不可を返すラッパ」で再現する。
 */
class ParentServerE2ETest {
    private val url = System.getenv("KOTSU_E2E_URL")
    private val loginId = System.getenv("KOTSU_E2E_LOGIN")
    private val password = System.getenv("KOTSU_E2E_PASSWORD")

    /** offline=外出先。afterCommit=サーバには届いたが応答が返らない（タイムアウト）。 */
    private class Switchable(val real: ParentApiPort) : ParentApiPort by real {
        var offline = false
        var afterCommit = false
        private fun <T> off(): ApiResult<T> = ApiResult.NetworkError(IOException("offline"))
        private suspend fun <T> tx(call: suspend () -> ApiResult<T>): ApiResult<T> = when {
            offline -> off()
            afterCommit -> { call(); off() }
            else -> call()
        }
        override suspend fun withdraw(req: WithdrawalRequest) = tx { real.withdraw(req) }
        override suspend fun adjust(req: AdjustmentRequest) = tx { real.adjust(req) }
        override suspend fun childrenSummary() = if (offline) off() else real.childrenSummary()
    }

    @Test
    fun outingThenHomeAgainstRealServer() = runBlocking {
        assumeTrue("KOTSU_E2E_URL などが未設定のためスキップ", !url.isNullOrBlank() && !loginId.isNullOrBlank() && !password.isNullOrBlank())
        val store = MemoryStore()
        var sw: Switchable? = null
        val outingTime = Instant.now().minusSeconds(3 * 3600) // 3時間前に外出先で操作した
        var clock = outingTime
        // 通信の切り替え口は1つにまとめ、トークンは常に保存領域から読む（ログイン時の呼び出しはトークンなし）
        val repo = ParentRepository(store, { u, _ -> sw ?: Switchable(HttpParentApi(u, { store.token })).also { sw = it } }, now = { clock })
        assertEquals(LoginOutcome.Ok(false), repo.login(url!!, loginId!!, password!!))
        assertEquals(ParentRepository.RefreshOutcome.Ok, repo.refresh())
        val child = store.children.first()
        val start = child.balance

        // 外出先：残高を超える引き出し、ふやす、とりけし
        sw!!.offline = true
        assertEquals(RecordOutcome.Queued, repo.record(child, OpKind.WITHDRAWAL, start + 1000, "おもちゃ"))
        assertEquals(RecordOutcome.Queued, repo.record(child, OpKind.ADD, 70, "お手伝い"))
        assertEquals(RecordOutcome.Queued, repo.record(child, OpKind.SUBTRACT, 30, "まちがい"))
        assertEquals(RecordOutcome.Queued, repo.cancelPending(store.ops.last()))
        assertEquals(start - (start + 1000) + 70, provisionalBalance(child, store.ops))

        // 帰宅：1件目だけ「届いたが応答なし」にしてから、普通に同期
        clock = Instant.now()
        sw!!.offline = false
        sw!!.afterCommit = true
        repo.flush()
        sw!!.afterCommit = false
        assertEquals(ParentRepository.RefreshOutcome.Ok, repo.refresh())
        assertTrue(store.ops.isEmpty())
        val expected = start - (start + 1000) + 70 - 30 + 30
        assertEquals(expected, store.children.first { it.userId == child.userId }.balance)
        assertTrue(expected < 0) // サーバ残高が負になる

        // 二重にならない（もう一度同期しても同じ）、外出先の時刻で台帳に入る
        repo.refresh()
        val ledger = (HttpParentApi(url, { store.token }).ledger(child.userId) as ApiResult.Ok).value
        assertEquals(expected, ledger.balance)
        assertEquals("おもちゃ", ledger.entries.first { it.type == "withdrawal" }.note) // 引き出しのメモも残る
        val mine = ledger.entries.filter { e -> e.note in setOf("おもちゃ", "お手伝い", "まちがい", "とりけし：へらす まちがい") }
            .filter { Instant.parse(it.occurredAt) >= outingTime.minusSeconds(1) }
        assertEquals(4, mine.size)
        assertTrue(mine.all { Instant.parse(it.occurredAt).isBefore(outingTime.plusSeconds(60)) })
    }
}
