package com.kotsukotsu.launcher

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kotsukotsu.core.api.normalizeBaseUrl
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

data class AppRow(val item: AppItem, val installedVersion: Long?) {
    val updatable get() = installedVersion != null && item.versionCode > installedVersion
}

sealed interface Screen {
    data object Loading : Screen
    data class Setup(val address: String, val error: String? = null, val busy: Boolean = false) : Screen
    data class Home(
        val games: List<AppRow>,
        val offline: Boolean,
        val needsPermission: Boolean,
        val launcherUpdate: AppItem?,
        val message: String? = null,
    ) : Screen
    data class Working(val title: String, val phase: String, val progress: Float? = null) : Screen
}

class LauncherViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as LauncherApp
    private val _screen = MutableStateFlow<Screen>(Screen.Loading)
    val screen: StateFlow<Screen> = _screen

    /** Activity に開いてもらう画面（ゲームの起動、インストールの確認、設定）。 */
    private val intents = Channel<Intent>(Channel.BUFFERED)
    val intentFlow = intents.receiveAsFlow()

    private var remote: List<AppItem>? = null // 今回の起動でサーバから取れた一覧（取れなければ null）
    private var job: Job? = null

    init {
        viewModelScope.launch {
            if (app.prefs.baseUrl == null) _screen.value = Screen.Setup(addressForEdit()) else refresh()
        }
    }

    private fun addressForEdit() =
        (app.prefs.baseUrl ?: BuildConfig.DEFAULT_SERVER_URL).removePrefix("http://").removeSuffix("/")

    // ---------- 初回設定 ----------

    fun openSetup() {
        if (job?.isActive == true) return
        _screen.value = Screen.Setup(addressForEdit())
    }

    fun connect(input: String) {
        val url = normalizeBaseUrl(input)
        if (url == null) {
            _screen.value = Screen.Setup(input, error = "サーバの アドレスを いれてね（れい 192.168.0.2:8000）"); return
        }
        _screen.value = Screen.Setup(input, busy = true)
        viewModelScope.launch {
            val apps = app.client.fetchApps(url)
            if (apps == null) {
                _screen.value = Screen.Setup(input, error = "サーバに つながりませんでした。おうちの Wi-Fi と アドレスを たしかめてね")
                return@launch
            }
            app.prefs.baseUrl = url
            app.prefs.cachedApps = apps
            remote = apps
            showHome()
        }
    }

    // ---------- ホーム ----------

    /** 起動時・ゲームから戻ったときに、一覧と入っている版を取り直す。 */
    fun refresh() {
        if (job?.isActive == true || _screen.value is Screen.Setup) return
        val baseUrl = app.prefs.baseUrl ?: return
        if (_screen.value !is Screen.Home) showHome() // まず保存しておいた一覧で出す
        job = viewModelScope.launch {
            remote = app.client.fetchApps(baseUrl)?.also { app.prefs.cachedApps = it }
            showHome()
        }
    }

    private fun showHome(message: String? = null) {
        val list = remote ?: app.prefs.cachedApps
        val ctx = getApplication<Application>()
        val games = list.filter { it.isGame }.map { AppRow(it, Installer.installedVersion(ctx, it.packageName)) }
        val self = list.firstOrNull { it.isLauncher }?.takeIf { it.versionCode > BuildConfig.VERSION_CODE && it.packageName == ctx.packageName }
        _screen.value = Screen.Home(
            games = games,
            offline = remote == null,
            needsPermission = !app.installer.canRequestInstalls(),
            launcherUpdate = self,
            message = message,
        )
    }

    fun openInstallPermission() {
        val ctx = getApplication<Application>()
        intents.trySend(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** 通知から開かれた：一覧の読み込みを待ってから、そのゲームを選んだのと同じ動きをする。 */
    fun openRequested(appId: String) {
        viewModelScope.launch {
            job?.join()
            if (_screen.value !is Screen.Home) return@launch // 初回設定がまだ
            val item = (remote ?: app.prefs.cachedApps).firstOrNull { it.appId == appId && it.isGame } ?: return@launch
            play(AppRow(item, Installer.installedVersion(getApplication(), item.packageName)))
        }
    }

    /** 子供がゲームを選んだ：最新版を問い合わせ → 必要なら入れる・更新する → 起動（REQ-GAME-15）。 */
    fun play(row: AppRow) {
        if (job?.isActive == true) return
        val baseUrl = app.prefs.baseUrl ?: return
        val title = row.item.title
        job = viewModelScope.launch {
            _screen.value = Screen.Working(title, "じゅんび ちゅう…")
            remote = app.client.fetchApps(baseUrl)?.also { app.prefs.cachedApps = it }
            val ctx = getApplication<Application>()
            val latest = remote?.firstOrNull { it.appId == row.item.appId }
            val installed = Installer.installedVersion(ctx, row.item.packageName)
            when (val action = decide(latest, installed, Build.VERSION.SDK_INT, offline = remote == null)) {
                Action.Launch -> launch(row.item.packageName)
                is Action.Unavailable -> showHome(
                    when (action.reason) {
                        Action.Reason.OFFLINE -> "サーバに つながらないよ。おうちの Wi-Fi で ひらいてね"
                        Action.Reason.NOT_PUBLISHED -> "まだ じゅんび できていないよ"
                        Action.Reason.DEVICE_TOO_OLD -> "この たんまつでは あそべないよ（Android が ふるい）"
                    }
                )
                is Action.Install -> install(baseUrl, action.item, fresh = true)
                is Action.Update -> install(baseUrl, action.item, fresh = false)
            }
        }
    }

    private suspend fun install(baseUrl: String, item: AppItem, fresh: Boolean) {
        val phase = if (fresh) "いれて いるよ" else "あたらしく して いるよ"
        _screen.value = Screen.Working(item.title, phase, 0f)
        val outcome = app.updater.installOrUpdate(
            baseUrl, item,
            onProgress = { p -> _screen.value = Screen.Working(item.title, phase, p) },
            onUserAction = { confirm ->
                _screen.value = Screen.Working(item.title, "「インストール」を おしてね")
                intents.trySend(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).isSuccess
            },
        )
        val installedNow = Installer.installedVersion(getApplication(), item.packageName)
        when {
            outcome == Updater.Outcome.INSTALLED -> launch(item.packageName)
            // 更新に失敗しても、入っている版で遊べるようにする
            installedNow != null -> launch(item.packageName)
            else -> showHome(
                when (outcome) {
                    Updater.Outcome.NO_PERMISSION -> "おうちの ひとに「アプリの インストールの きょか」を して もらってね"
                    Updater.Outcome.NETWORK -> "ダウンロード できなかったよ。もういちど ためしてね"
                    Updater.Outcome.CORRUPT -> "ダウンロードが こわれていたよ。もういちど ためしてね"
                    else -> "インストール できなかったよ"
                }
            )
        }
    }

    private fun launch(packageName: String) {
        val intent = getApplication<Application>().packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            showHome("ひらけなかったよ"); return
        }
        intents.trySend(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        showHome()
    }

    /** ランチャー自身の更新（Android 12 以上は充電中に裏でも行う。それ未満は確認を押す）。 */
    fun updateLauncher(item: AppItem) {
        if (job?.isActive == true) return
        val baseUrl = app.prefs.baseUrl ?: return
        job = viewModelScope.launch {
            _screen.value = Screen.Working(app.getString(R.string.app_name), "あたらしく して いるよ", 0f)
            val outcome = app.updater.installOrUpdate(
                baseUrl, item,
                onProgress = { p -> _screen.value = Screen.Working(app.getString(R.string.app_name), "あたらしく して いるよ", p) },
                onUserAction = { confirm -> intents.trySend(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).isSuccess },
            )
            // 成功するとこのアプリは終了する。ここに来たら失敗か取り消し
            showHome(if (outcome == Updater.Outcome.NO_PERMISSION) "おうちの ひとに「アプリの インストールの きょか」を して もらってね" else null)
        }
    }
}
