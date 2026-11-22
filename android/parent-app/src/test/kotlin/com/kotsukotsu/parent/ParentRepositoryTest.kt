package com.kotsukotsu.parent

import com.kotsukotsu.core.api.ApiResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

class MemoryStore : ParentStore {
    override var baseUrl: String? = null
    override var loginId: String? = null
    override var token: String? = null
    override var children: List<ChildCard> = emptyList()
    override var syncedAt: String? = null
    override var ops: List<PendingOp> = emptyList()
    override var cancelled: Set<String> = emptySet()
    private val ledgers = mutableMapOf<String, List<LedgerEntry>>()
    override fun ledger(childUserId: String) = ledgers[childUserId]
    override fun setLedger(childUserId: String, entries: List<LedgerEntry>) { ledgers[childUserId] = entries }
}

/**
 * サーバを模したフェイク。台帳は ID で冪等（原則3）。mode で通信不可・503 などを起こす。
 * 「タイムアウト」は、サーバには届いて記録されたが応答が返らなかった場合を再現する。
 */
class FakeServer : ParentApiPort {
    enum class Mode { OK, OFFLINE, TIMEOUT_AFTER_COMMIT, FAIL_503, UNAUTHORIZED, BAD_REQUEST }
    var mode = Mode.OK
    val ledger = linkedMapOf<String, Pair<String, Int>>() // id → (child, points)
    val requests = mutableListOf<Any>()
    val notes = mutableMapOf<String, String?>()
    var balance0 = 0
    var token = "tok"

    fun balance(child: String) = balance0 + ledger.values.filter { it.first == child }.sumOf { it.second }

    private fun <T> tx(id: String, child: String, points: Int, ok: () -> T): ApiResult<T> = when (mode) {
        Mode.OFFLINE -> ApiResult.NetworkError(IOException("unreachable"))
        Mode.TIMEOUT_AFTER_COMMIT -> { ledger.putIfAbsent(id, child to points); ApiResult.NetworkError(IOException("timeout")) }
        Mode.FAIL_503 -> ApiResult.HttpError(503, "TRANSACTION_FAILED", "db locked")
        Mode.UNAUTHORIZED -> ApiResult.HttpError(401, "UNAUTHORIZED", "expired")
        Mode.BAD_REQUEST -> ApiResult.HttpError(400, "INVALID_REQUEST", "無効化された子供です")
        Mode.OK -> { val created = ledger.putIfAbsent(id, child to points) == null; ApiResult.Ok(ok(), if (created) 201 else 200) }
    }

    override suspend fun login(req: LoginRequest): ApiResult<LoginResponse> = when {
        mode == Mode.OFFLINE -> ApiResult.NetworkError(IOException())
        req.password == "pw" -> ApiResult.Ok(LoginResponse(token, mustChangePassword = false), 200)
        else -> ApiResult.HttpError(401, "UNAUTHORIZED", "bad")
    }
    override suspend fun changePassword(req: PasswordRequest) = ApiResult.Ok(Unit, 204)
    override suspend fun childrenSummary(): ApiResult<ChildrenSummary> = when (mode) {
        Mode.OK -> ApiResult.Ok(ChildrenSummary(listOf(ChildCard("c1", "そうちゃん", balance("c1"), 3, 100, 1500))), 200)
        Mode.UNAUTHORIZED -> ApiResult.HttpError(401, null, null)
        else -> ApiResult.NetworkError(IOException())
    }
    override suspend fun ledger(childUserId: String) = ApiResult.Ok(
        LedgerResponse(ledger.entries.reversed().map { (id, v) -> LedgerEntry(id, "adjustment", v.second, id, "2026-11-14T00:00:00Z", notes[id]) }, balance(childUserId)), 200)
    override suspend fun withdraw(req: WithdrawalRequest): ApiResult<TxResponse> {
        requests += req
        return tx(req.transactionId, req.childUserId, -req.points) { TxResponse(balance(req.childUserId)) }
    }
    override suspend fun adjust(req: AdjustmentRequest): ApiResult<TxResponse> {
        requests += req
        notes.putIfAbsent(req.adjustmentId, req.note)
        return tx(req.adjustmentId, req.childUserId, req.points) { TxResponse(balance(req.childUserId)) }
    }
    override suspend fun apps() = ApiResult.Ok(AppsList(listOf(AppInfo("parent-app", "parent", 2, "1.0.1"), AppInfo("launcher", "launcher", 9))), 200)
}

class ParentRepositoryTest {
    private val server = FakeServer().apply { balance0 = 420 }
    private val store = MemoryStore()
    private var n = 0
    private val repo = ParentRepository(store, { _, _ -> server }, now = { Instant.parse("2026-11-14T03:00:00Z") }, newId = { "id-${++n}" })
    private val child get() = store.children.first()

    private suspend fun loggedIn() {
        assertEquals(LoginOutcome.Ok(false), repo.login("http://192.168.0.2:8000/", "papa", "pw"))
        assertEquals(ParentRepository.RefreshOutcome.Ok, repo.refresh())
    }

    @Test fun onlineWithdrawalIsConfirmedImmediately() = runTest {
        loggedIn()
        val r = repo.record(child, OpKind.WITHDRAWAL, 500, "おかし")
        assertEquals(RecordOutcome.Sent(-80), r) // 残高を超えてもよい（REQ-PT-05）
        assertTrue(store.ops.isEmpty())
        assertEquals(-80, child.balance)
        val req = server.requests.single() as WithdrawalRequest
        assertEquals(500, req.points)
        assertEquals("おかし", req.note)
        assertFalse(req.wasOffline)
        assertEquals("2026-11-14T03:00:00Z", req.occurredAtDevice)
    }

    @Test fun immediate503IsAFailureAndNotRecorded() = runTest {
        loggedIn()
        server.mode = FakeServer.Mode.FAIL_503
        val r = repo.record(child, OpKind.WITHDRAWAL, 100, null)
        assertTrue(r is RecordOutcome.Rejected) // REQ-APP-06：親はまだ現金を渡していない
        assertTrue(store.ops.isEmpty())
        server.mode = FakeServer.Mode.OK
        assertEquals(0, repo.flush().sent)
        assertEquals(420, server.balance("c1"))
    }

    @Test fun offlineOperationsAreQueuedAndSentLaterWithSameIds() = runTest {
        loggedIn()
        server.mode = FakeServer.Mode.OFFLINE // 外出先
        assertEquals(RecordOutcome.Queued, repo.record(child, OpKind.WITHDRAWAL, 600, "おもちゃ"))
        assertEquals(RecordOutcome.Queued, repo.record(child, OpKind.ADD, 50, "お手伝い"))
        assertEquals(RecordOutcome.Queued, repo.record(child, OpKind.SUBTRACT, 10, null))
        assertEquals(3, store.ops.size)
        assertEquals(-560, pendingTotal(store.ops, "c1"))
        assertEquals(-140, provisionalBalance(child, store.ops)) // サーバ残高 420 ＋ 未送信 −560
        assertEquals(ParentRepository.RefreshOutcome.Offline, repo.refresh())
        assertEquals(3, store.ops.size)

        server.mode = FakeServer.Mode.OK // 帰宅
        assertEquals(ParentRepository.RefreshOutcome.Ok, repo.refresh())
        assertTrue(store.ops.isEmpty())
        assertEquals(-140, child.balance) // サーバ残高が負になる（フェーズ4の完了条件）
        val resent = server.requests.takeLast(3) // 外出先での refresh も1回試している
        assertEquals(listOf("id-1", "id-2", "id-3"), resent.map { if (it is WithdrawalRequest) it.transactionId else (it as AdjustmentRequest).adjustmentId })
        assertTrue(resent.all { if (it is WithdrawalRequest) it.wasOffline else (it as AdjustmentRequest).wasOffline })
        val add = resent[1] as AdjustmentRequest
        assertEquals(50, add.points); assertEquals("お手伝い", add.note); assertEquals("2026-11-14T03:00:00Z", add.occurredAtDevice)
        assertEquals("へらす", (resent[2] as AdjustmentRequest).note) // メモがなければ種類を入れる
        assertEquals(-10, (resent[2] as AdjustmentRequest).points)
    }

    @Test fun timeoutThenResendDoesNotDoubleWithdraw() = runTest {
        loggedIn()
        server.mode = FakeServer.Mode.TIMEOUT_AFTER_COMMIT // サーバには届いたが応答が返らない
        assertEquals(RecordOutcome.Queued, repo.record(child, OpKind.WITHDRAWAL, 300, null))
        server.mode = FakeServer.Mode.OK
        repo.refresh()
        assertEquals(1, server.ledger.size)
        assertEquals(120, child.balance)
        assertTrue(store.ops.isEmpty())
    }

    @Test fun queued503AndOfflineAreKeptForRetry() = runTest {
        loggedIn()
        server.mode = FakeServer.Mode.OFFLINE
        repo.record(child, OpKind.WITHDRAWAL, 100, null)
        repo.record(child, OpKind.WITHDRAWAL, 200, null)
        server.mode = FakeServer.Mode.FAIL_503
        val f = repo.flush()
        assertEquals(FlushOutcome(0, 2, offline = false, authRequired = false), f) // 捨てない（REQ-APP-07）
        server.mode = FakeServer.Mode.OK
        assertEquals(2, repo.flush().sent)
    }

    @Test fun expiredLoginKeepsQueueUntilReLogin() = runTest {
        loggedIn()
        server.mode = FakeServer.Mode.OFFLINE
        repo.record(child, OpKind.ADD, 100, null)
        server.mode = FakeServer.Mode.UNAUTHORIZED
        assertEquals(ParentRepository.RefreshOutcome.AuthRequired, repo.refresh())
        assertEquals(1, store.ops.size)
        server.mode = FakeServer.Mode.OK
        assertEquals(LoginOutcome.Ok(false), repo.login("http://192.168.0.2:8000/", "mama", "pw"))
        repo.refresh()
        assertTrue(store.ops.isEmpty())
        assertEquals(520, child.balance)
    }

    @Test fun rejectedQueuedOpIsKeptAndMarkedButOthersContinue() = runTest {
        loggedIn()
        server.mode = FakeServer.Mode.OFFLINE
        repo.record(child, OpKind.WITHDRAWAL, 100, null)
        server.mode = FakeServer.Mode.BAD_REQUEST
        repo.flush()
        assertEquals("無効化された子供です", store.ops.single().problem)
    }

    @Test fun cancelPendingAddsReverseAdjustmentInsteadOfDeleting() = runTest {
        loggedIn()
        server.mode = FakeServer.Mode.OFFLINE
        repo.record(child, OpKind.WITHDRAWAL, 300, "まちがい")
        val op = store.ops.single()
        assertEquals(RecordOutcome.Queued, repo.cancelPending(op))
        assertEquals(2, store.ops.size)
        val cancel = store.ops[1]
        assertEquals(OpKind.CANCEL, cancel.kind)
        assertEquals(300, cancel.points)
        assertEquals("とりけし：引き出し まちがい", cancel.note)
        assertEquals(setOf(op.id), store.cancelled)
        assertEquals(0, pendingTotal(store.ops, "c1"))
        server.mode = FakeServer.Mode.OK
        repo.refresh()
        assertEquals(420, child.balance) // 残高が元に戻る
        assertEquals(2, server.ledger.size) // 台帳は追記のみ（原則2）
    }

    @Test fun cancelSyncedLedgerEntry() = runTest {
        loggedIn()
        repo.record(child, OpKind.ADD, 100, "ごほうび")
        val entry = repo.loadLedger("c1")!!.first()
        assertTrue(entry.cancellable())
        assertEquals(RecordOutcome.Sent(420), repo.cancelEntry(child, entry))
        val req = server.requests.last() as AdjustmentRequest
        assertEquals(-100, req.points)
        assertEquals("とりけし：ごほうび", req.note)
        assertTrue(entry.transactionId in store.cancelled)
    }

    @Test fun rejectedCancelCanBeRetried() = runTest {
        loggedIn()
        server.mode = FakeServer.Mode.OFFLINE
        repo.record(child, OpKind.WITHDRAWAL, 300, null)
        val op = store.ops.single()
        server.mode = FakeServer.Mode.FAIL_503
        assertTrue(repo.cancelPending(op) is RecordOutcome.Rejected)
        assertTrue(store.cancelled.isEmpty()) // 失敗したとりけしはやり直せる
        assertEquals(1, store.ops.size)
    }

    @Test fun twoParentsOfflineBothReflected() = runTest {
        // 親2人の端末（別々の保存領域）で同時にオフライン引き出し → 両方とも反映される
        loggedIn()
        val store2 = MemoryStore()
        val repo2 = ParentRepository(store2, { _, _ -> server }, newId = { "other-${++n}" })
        repo2.login("http://192.168.0.2:8000/", "mama", "pw"); repo2.refresh()
        server.mode = FakeServer.Mode.OFFLINE
        repo.record(child, OpKind.WITHDRAWAL, 100, null)
        repo2.record(store2.children.first(), OpKind.WITHDRAWAL, 200, null)
        server.mode = FakeServer.Mode.OK
        repo.refresh(); repo2.refresh()
        assertEquals(120, server.balance("c1"))
        assertEquals(120, store2.children.first().balance)
    }

    @Test fun loginFailures() = runTest {
        assertTrue((repo.login("http://x/", "papa", "wrong") as LoginOutcome.Failed).message.contains("違います"))
        server.mode = FakeServer.Mode.OFFLINE
        assertTrue((repo.login("http://x/", "papa", "pw") as LoginOutcome.Failed).message.contains("つながりません"))
        assertNull(store.token)
    }

    @Test fun newerReleaseAndLogoutKeepsQueue() = runTest {
        loggedIn()
        assertEquals("1.0.1", repo.newerRelease(1)?.versionName)
        assertNull(repo.newerRelease(2))
        assertEquals("http://192.168.0.2:8000/apk/parent-app/download", repo.downloadUrl())
        server.mode = FakeServer.Mode.OFFLINE
        repo.record(child, OpKind.ADD, 10, null)
        repo.logout()
        assertFalse(repo.snapshot.value.loggedIn)
        assertEquals(1, store.ops.size)
        assertEquals(RecordOutcome.AuthRequired, repo.record(child, OpKind.ADD, 10, null)) // ログアウト中も保存はする
        assertEquals(2, store.ops.size)
    }
}
