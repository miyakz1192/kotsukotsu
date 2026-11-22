package com.kotsukotsu.parent

import com.kotsukotsu.core.api.ApiResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID

/** 画面に出すもの（端末に保存した写し）。 */
data class Snapshot(
    val loggedIn: Boolean,
    val loginId: String?,
    val baseUrl: String?,
    val children: List<ChildCard>,
    val syncedAt: String?,
    val ops: List<PendingOp>,
    val cancelled: Set<String>,
)

sealed interface LoginOutcome {
    data class Ok(val mustChangePassword: Boolean) : LoginOutcome
    data class Failed(val message: String) : LoginOutcome
}

sealed interface RecordOutcome {
    /** サーバで確定した。 */
    data class Sent(val balance: Int) : RecordOutcome
    /** 通信できないので端末に保存した。家のWi-Fiで自動で送る。 */
    data object Queued : RecordOutcome
    /** ログインし直しが必要（保存はしてある）。 */
    data object AuthRequired : RecordOutcome
    /** サーバが受け付けなかった（503 など）。記録していない。 */
    data class Rejected(val message: String) : RecordOutcome
}

data class FlushOutcome(val sent: Int, val remaining: Int, val offline: Boolean, val authRequired: Boolean)

/**
 * 引き出し・調整の記録と未送信キュー（docs/06 2.2）。
 * 操作はまずキューに保存してから送る。送信中にアプリが終わっても、同じIDで再送されるので二重にならない（原則3）。
 */
class ParentRepository(
    private val store: ParentStore,
    private val apiFactory: (baseUrl: String, token: () -> String?) -> ParentApiPort,
    private val now: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutex = Mutex() // 画面と WorkManager から同時に送らない
    private val _snapshot = MutableStateFlow(snap())
    val snapshot: StateFlow<Snapshot> = _snapshot

    private fun snap() = Snapshot(
        loggedIn = store.token != null, loginId = store.loginId, baseUrl = store.baseUrl, children = store.children,
        syncedAt = store.syncedAt, ops = store.ops, cancelled = store.cancelled,
    )

    private fun publish() { _snapshot.value = snap() }

    private fun api(): ParentApiPort? = store.baseUrl?.let { apiFactory(it) { store.token } }

    // ---------- ログイン ----------

    suspend fun login(baseUrl: String, loginId: String, password: String): LoginOutcome {
        val r = apiFactory(baseUrl) { null }.login(LoginRequest(loginId, password))
        return when (r) {
            is ApiResult.Ok -> {
                if (store.baseUrl != baseUrl) { // 別のサーバに切り替えた：前のサーバの写しを捨てる（未送信は残す）
                    store.children = emptyList(); store.syncedAt = null
                }
                store.baseUrl = baseUrl; store.loginId = loginId; store.token = r.value.token
                publish()
                LoginOutcome.Ok(r.value.mustChangePassword)
            }
            is ApiResult.HttpError -> LoginOutcome.Failed(
                if (r.status == 401) "ログインIDかパスワードが違います" else r.message ?: "ログインできませんでした（${r.status}）"
            )
            is ApiResult.NetworkError -> LoginOutcome.Failed("サーバにつながりません。家のWi-Fiとアドレスを確認してください")
        }
    }

    suspend fun changePassword(current: String, new: String): String? {
        val api = api() ?: return "ログインしてください"
        return when (val r = api.changePassword(PasswordRequest(current, new))) {
            is ApiResult.Ok -> null
            is ApiResult.HttpError -> r.message ?: "変更できませんでした（${r.status}）"
            is ApiResult.NetworkError -> "サーバにつながりません"
        }
    }

    /** ログアウト。未送信の操作は消さず、次にログインした親の操作として送る。 */
    fun logout() {
        store.token = null
        publish()
    }

    // ---------- 記録 ----------

    /**
     * 引き出し・ふやす・へらす。amount は正の数。
     * その場で送って、通信できなければキューに残す。503・入力の誤りは失敗として記録しない。
     */
    suspend fun record(child: ChildCard, kind: OpKind, amount: Int, note: String?): RecordOutcome {
        require(amount > 0) { "amount must be positive" }
        require(kind != OpKind.CANCEL)
        val points = if (kind == OpKind.ADD) amount else -amount
        return submit(newOp(child.userId, child.displayName, kind, points, note, cancels = null))
    }

    /** 未送信の操作のとりけし（削除ではなく逆向きの調整。REQ-APP-11）。 */
    suspend fun cancelPending(op: PendingOp): RecordOutcome =
        submit(newOp(op.childUserId, op.childName, OpKind.CANCEL, -op.points, "とりけし：${describe(op.kind, op.note)}", op.id))

    /** 送信済み（台帳の行）のとりけし。 */
    suspend fun cancelEntry(child: ChildCard, entry: LedgerEntry): RecordOutcome {
        require(entry.cancellable())
        val label = if (entry.type == "withdrawal") listOfNotNull(OpKind.WITHDRAWAL.label, entry.note).joinToString(" ") else entry.note ?: "調整"
        return submit(newOp(child.userId, child.displayName, OpKind.CANCEL, -entry.points, "とりけし：$label", entry.transactionId))
    }

    private fun describe(kind: OpKind, note: String?) = listOfNotNull(kind.label, note?.takeIf { it.isNotBlank() }).joinToString(" ")

    private fun newOp(childId: String, childName: String, kind: OpKind, points: Int, note: String?, cancels: String?) =
        PendingOp(newId(), kind, childId, childName, points, note?.trim()?.takeIf { it.isNotEmpty() }, now().toString(), cancels)

    private suspend fun submit(op: PendingOp): RecordOutcome = mutex.withLock {
        store.ops = store.ops + op
        op.cancels?.let { store.cancelled = store.cancelled + it }
        publish()
        val api = api() ?: return@withLock RecordOutcome.Queued
        if (store.token == null) return@withLock RecordOutcome.AuthRequired
        when (val r = send(api, op, wasOffline = false)) {
            is ApiResult.Ok -> {
                removeOp(op.id); applyBalance(op.childUserId, r.value.balance)
                RecordOutcome.Sent(r.value.balance)
            }
            is ApiResult.NetworkError -> RecordOutcome.Queued
            is ApiResult.HttpError -> when {
                r.status == 401 || r.status == 403 -> RecordOutcome.AuthRequired
                else -> { // 503（REQ-APP-06）・400・404：親はまだ現金を渡していないので記録しない
                    removeOp(op.id)
                    op.cancels?.let { store.cancelled = store.cancelled - it }
                    publish()
                    RecordOutcome.Rejected(
                        if (r.status == 503) "サーバで記録できませんでした。もう一度ためしてください"
                        else r.message ?: "記録できませんでした（${r.status}）"
                    )
                }
            }
        }
    }

    private suspend fun send(api: ParentApiPort, op: PendingOp, wasOffline: Boolean): ApiResult<TxResponse> =
        if (op.kind.isWithdrawal) {
            api.withdraw(WithdrawalRequest(op.id, op.childUserId, -op.points, op.occurredAt, wasOffline, op.note))
        } else {
            api.adjust(AdjustmentRequest(op.id, op.childUserId, op.points, op.note ?: op.kind.label, op.occurredAt, wasOffline))
        }

    private fun removeOp(id: String) {
        store.ops = store.ops.filterNot { it.id == id }
        publish()
    }

    private fun applyBalance(childId: String, balance: Int) {
        store.children = store.children.map { if (it.userId == childId) it.copy(balance = balance) else it }
        publish()
    }

    // ---------- 同期 ----------

    /**
     * 未送信を古い順に送る（was_offline=true）。
     * 通信不可・503 はそこで止めて次の機会に再試行、401 は再ログインを待つ（捨てない。REQ-APP-07）。
     * 400・404 は「送れない操作」として残し、次へ進む。
     */
    suspend fun flush(): FlushOutcome = mutex.withLock {
        val api = api()
        if (api == null || store.token == null) return@withLock FlushOutcome(0, store.ops.size, false, store.token == null)
        var sent = 0
        for (op in store.ops.toList()) {
            when (val r = send(api, op, wasOffline = true)) {
                is ApiResult.Ok -> { removeOp(op.id); applyBalance(op.childUserId, r.value.balance); sent++ }
                is ApiResult.NetworkError -> return@withLock FlushOutcome(sent, store.ops.size, offline = true, authRequired = false)
                is ApiResult.HttpError -> when (r.status) {
                    401, 403 -> return@withLock FlushOutcome(sent, store.ops.size, offline = false, authRequired = true)
                    400, 404, 409, 422 -> markProblem(op.id, r.message ?: "サーバが受け付けませんでした（${r.status}）")
                    else -> return@withLock FlushOutcome(sent, store.ops.size, offline = false, authRequired = false)
                }
            }
        }
        FlushOutcome(sent, store.ops.size, offline = false, authRequired = false)
    }

    private fun markProblem(id: String, message: String) {
        store.ops = store.ops.map { if (it.id == id) it.copy(problem = message) else it }
        publish()
    }

    sealed interface RefreshOutcome {
        data object Ok : RefreshOutcome
        data object Offline : RefreshOutcome
        data object AuthRequired : RefreshOutcome
    }

    /** 未送信を送ってから子供の一覧（残高）を取り直す（docs/06 2.4）。 */
    suspend fun refresh(): RefreshOutcome {
        val f = flush()
        if (f.authRequired) return RefreshOutcome.AuthRequired
        val api = api() ?: return RefreshOutcome.Offline
        return when (val r = api.childrenSummary()) {
            is ApiResult.Ok -> {
                store.children = r.value.children
                store.syncedAt = now().toString()
                publish()
                if (f.offline) RefreshOutcome.Offline else RefreshOutcome.Ok
            }
            is ApiResult.HttpError -> if (r.status == 401 || r.status == 403) RefreshOutcome.AuthRequired else RefreshOutcome.Offline
            is ApiResult.NetworkError -> RefreshOutcome.Offline
        }
    }

    fun cachedLedger(childUserId: String): List<LedgerEntry>? = store.ledger(childUserId)

    /** 台帳を取り直す（取れなければ前回の写しのまま）。 */
    suspend fun loadLedger(childUserId: String): List<LedgerEntry>? {
        val api = api() ?: return store.ledger(childUserId)
        val r = api.ledger(childUserId)
        if (r is ApiResult.Ok) {
            store.setLedger(childUserId, r.value.entries.take(LEDGER_KEEP))
            applyBalance(childUserId, r.value.balance)
        }
        return store.ledger(childUserId)
    }

    /** サーバに自分（親用アプリ）の新しい版があれば返す（REQ-APP-13）。 */
    suspend fun newerRelease(currentVersionCode: Long): AppInfo? {
        val api = api() ?: return null
        val r = api.apps() as? ApiResult.Ok ?: return null
        return r.value.apps.firstOrNull { it.kind == "parent" && it.versionCode > currentVersionCode }
    }

    fun downloadUrl(): String? = store.baseUrl?.let { it + "apk/parent-app/download" }

    companion object {
        /** 端末に保存する台帳の行数（新しい順）。それより前は管理画面で見る。 */
        const val LEDGER_KEEP = 200
    }
}
