package com.kotsukotsu.parent

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kotsukotsu.core.api.normalizeBaseUrl
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

sealed interface Screen {
    data class Login(val reason: String? = null) : Screen
    data object ChangePassword : Screen
    data object Home : Screen
    data class Move(val childUserId: String?) : Screen
    data class History(val childUserId: String) : Screen
}

data class Ui(
    val screen: Screen,
    val busy: Boolean = false,
    val error: String? = null,
    /** 画面下に一時的に出すお知らせ。 */
    val message: String? = null,
    /** 最後の同期でサーバに届かなかった（外出先など）。 */
    val offline: Boolean = false,
    val update: AppInfo? = null,
    val ledger: List<LedgerEntry>? = null,
)

class ParentViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ParentApp
    val repo = app.repository
    val snapshot = repo.snapshot

    private val _ui = MutableStateFlow(Ui(if (repo.snapshot.value.loggedIn) Screen.Home else Screen.Login()))
    val ui: StateFlow<Ui> = _ui

    private val intents = Channel<Intent>(Channel.BUFFERED)
    val intentFlow = intents.receiveAsFlow()

    private fun set(f: Ui.() -> Ui) { _ui.value = _ui.value.f() }

    fun defaultAddress(): String = (repo.snapshot.value.baseUrl ?: BuildConfig.DEFAULT_SERVER_URL).removePrefix("http://").removeSuffix("/")

    // ---------- ログイン ----------

    fun login(address: String, loginId: String, password: String) {
        val url = normalizeBaseUrl(address)
        if (url == null || loginId.isBlank() || password.isEmpty()) {
            set { copy(error = "サーバのアドレス・ログインID・パスワードを入力してください") }; return
        }
        set { copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = repo.login(url, loginId.trim(), password)) {
                is LoginOutcome.Ok -> if (r.mustChangePassword) {
                    set { copy(busy = false, screen = Screen.ChangePassword) }
                } else {
                    set { copy(busy = false, screen = Screen.Home) }
                    refresh()
                }
                is LoginOutcome.Failed -> set { copy(busy = false, error = r.message) }
            }
        }
    }

    fun changePassword(current: String, new: String, confirm: String) {
        if (new != confirm) { set { copy(error = "新しいパスワードが一致しません") }; return }
        set { copy(busy = true, error = null) }
        viewModelScope.launch {
            val err = repo.changePassword(current, new)
            if (err == null) {
                set { copy(busy = false, screen = Screen.Home, message = "パスワードを変更しました") }
                refresh()
            } else {
                set { copy(busy = false, error = err) }
            }
        }
    }

    fun logout() {
        repo.logout()
        set { Ui(Screen.Login()) }
    }

    // ---------- ホーム ----------

    fun refresh() {
        if (!repo.snapshot.value.loggedIn) return
        viewModelScope.launch {
            set { copy(busy = true) }
            val r = repo.refresh()
            set { copy(busy = false, offline = r != ParentRepository.RefreshOutcome.Ok) }
            when (r) {
                ParentRepository.RefreshOutcome.AuthRequired -> needLogin()
                ParentRepository.RefreshOutcome.Offline -> if (repo.snapshot.value.ops.isNotEmpty()) FlushWorker.schedule(app)
                ParentRepository.RefreshOutcome.Ok -> {
                    val upd = repo.newerRelease(BuildConfig.VERSION_CODE.toLong())
                    set { copy(update = upd) }
                }
            }
            (ui.value.screen as? Screen.History)?.let { reloadLedger(it.childUserId) }
        }
    }

    private fun needLogin() {
        set { copy(screen = Screen.Login("ログインの期限が切れました。ログインし直すと、未送信の操作を送ります")) }
    }

    fun openUpdate() {
        val url = repo.downloadUrl() ?: return
        intents.trySend(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun go(screen: Screen) {
        set { copy(screen = screen, error = null, ledger = null) }
        if (screen is Screen.History) {
            set { copy(ledger = repo.cachedLedger(screen.childUserId)) }
            reloadLedger(screen.childUserId)
        }
    }

    fun back() {
        when (ui.value.screen) {
            is Screen.Move, is Screen.History -> go(Screen.Home)
            else -> Unit
        }
    }

    fun clearMessage() = set { copy(message = null) }

    private fun reloadLedger(childId: String) {
        viewModelScope.launch {
            val entries = repo.loadLedger(childId)
            if ((ui.value.screen as? Screen.History)?.childUserId == childId) set { copy(ledger = entries) }
        }
    }

    // ---------- ポイントを動かす ----------

    fun record(child: ChildCard, kind: OpKind, amount: Int, note: String) {
        set { copy(busy = true, error = null) }
        viewModelScope.launch {
            val r = repo.record(child, kind, amount, note)
            afterRecord(r, "${child.displayName}：${kind.label} ${amount}円")
        }
    }

    fun cancelPending(op: PendingOp) {
        set { copy(busy = true) }
        viewModelScope.launch { afterRecord(repo.cancelPending(op), "とりけしました", stay = true) }
    }

    fun cancelEntry(child: ChildCard, entry: LedgerEntry) {
        set { copy(busy = true) }
        viewModelScope.launch { afterRecord(repo.cancelEntry(child, entry), "とりけしました", stay = true) }
    }

    private fun afterRecord(r: RecordOutcome, what: String, stay: Boolean = false) {
        val next = if (stay) ui.value.screen else Screen.Home
        when (r) {
            is RecordOutcome.Sent -> set { copy(busy = false, screen = next, offline = false, message = "$what（記録しました）") }
            RecordOutcome.Queued -> {
                FlushWorker.schedule(app)
                set { copy(busy = false, screen = next, offline = true, message = "$what（サーバにつながらないので保存しました。家のWi-Fiで自動で送ります）") }
            }
            RecordOutcome.AuthRequired -> {
                set { copy(busy = false, message = "$what（保存しました）") }
                needLogin()
            }
            is RecordOutcome.Rejected -> set { copy(busy = false, error = r.message) }
        }
        (ui.value.screen as? Screen.History)?.let { h -> set { copy(ledger = repo.cachedLedger(h.childUserId)) }; reloadLedger(h.childUserId) }
    }
}
