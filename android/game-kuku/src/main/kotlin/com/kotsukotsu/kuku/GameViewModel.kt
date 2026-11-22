package com.kotsukotsu.kuku

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kotsukotsu.core.AnsweredQuestion
import com.kotsukotsu.core.GameRepository
import com.kotsukotsu.core.api.ApiResult
import com.kotsukotsu.core.api.Child
import com.kotsukotsu.core.api.normalizeBaseUrl
import com.kotsukotsu.core.notify.Reminders
import com.kotsukotsu.core.points.Modes
import com.kotsukotsu.core.points.SelfBestAward
import com.kotsukotsu.core.sync.LocalView
import com.kotsukotsu.core.sync.SyncOutcome
import com.kotsukotsu.core.sync.SyncWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 画面の状態（docs/06 1.1 の画面遷移）。 */
sealed interface Screen {
    data object Loading : Screen
    data class Setup(
        val address: String,
        val children: List<Child>? = null,
        val busy: Boolean = false,
        val error: String? = null,
    ) : Screen
    data object UpdateRequired : Screen
    data class Encourage(val message: String, val view: LocalView, val pointsUpdated: Boolean) : Screen
    data class ModeSelect(val view: LocalView, val choosingDan: Boolean = false, val notice: String? = null) : Screen
    data class Quiz(
        val mode: String,
        val dan: Int?,
        val questions: List<Question>,
        val index: Int = 0,
        val input: String = "",
        /** 不正解時に正しい答えを表示中 */
        val showingAnswer: Boolean = false,
        val lastCorrect: Boolean? = null,
        val answered: List<AnsweredQuestion> = emptyList(),
    ) : Screen {
        val current: Question get() = questions[index]
    }
    data class ModeResult(
        val mode: String,
        val dan: Int?,
        val correct: Int,
        val total: Int,
        val points: Int,
        val selfBest: List<SelfBestAward>,
        val banners: List<Banner>,
        val canContinue: Boolean,
        /** このプレイで卒業した段（自己ベストが卒業の正解率に届いた） */
        val graduatedNow: List<Int> = emptyList(),
        /** 卒業済みの段を段選択で遊んだ（ポイントが卒業後の単価になる） */
        val playedGraduated: Boolean = false,
    ) : Screen
    /** おさいふ（いまの ポイント）。モード選択から開く（docs/06 1.3.2）。 */
    /** notifyOff：おしらせ通知が許可されていない（docs/06 1.7） */
    data class Wallet(val view: LocalView, val notifyOff: Boolean = false) : Screen
    data class SessionResult(val studyPoints: Int, val streakBonus: Int, val streakDays: Int, val selfBestBonus: Int, val view: LocalView) : Screen
}

/** モード結果で出す演出（docs/06 1.4）。自己ベストは ModeResult.selfBest で別に出す。 */
enum class Banner(val text: String) {
    DAY_POINTS_FULL("きょうの ポイントは まんタン！"),
    DAY_DONE("きょうは ここまで！ また あした！"),
    MONTH_DONE("こんげつの もくひょう たっせい！");

    companion object {
        /** このモードで新たに到達したものだけを返す。 */
        fun compute(before: LocalView, after: LocalView): List<Banner> = buildList {
            if (before.todayStudyPoints < before.rules.dailyPointCap && after.todayStudyPoints >= after.rules.dailyPointCap) add(DAY_POINTS_FULL)
            if (before.monthEarned < before.rules.monthlyCap && after.monthEarned >= after.rules.monthlyCap) add(MONTH_DONE)
            if (after.questionsLeft == 0) add(DAY_DONE)
        }
    }
}

/** 1回の起動〜「おわる」までに遊んだモードの合計（プレイ結果画面用）。 */
private data class SessionTotals(var study: Int = 0, var streak: Int = 0, var streakDays: Int = 0, var selfBest: Int = 0)

class GameViewModel(app: Application) : AndroidViewModel(app) {
    private val kuku = app as KukuApp
    private val repo: GameRepository get() = kuku.gameRepository
    private val _screen = MutableStateFlow<Screen>(Screen.Loading)
    val screen: StateFlow<Screen> = _screen.asStateFlow()
    val soundOn = MutableStateFlow(true)
    /** 通知の許可を尋ねる案内を出すか。 */
    val askNotify = MutableStateFlow(false)

    private var session = SessionTotals()
    private var questionShownAt = 0L
    private var startedAt = 0L
    private var needsReregister = false
    private var startupSync: Job? = null

    init {
        viewModelScope.launch {
            soundOn.value = repo.prefs.soundOn
            start()
        }
    }

    private fun sound(s: Sounds.S) {
        if (soundOn.value) kuku.sounds.play(s)
    }

    fun toggleSound() {
        soundOn.value = !soundOn.value
        repo.prefs.soundOn = soundOn.value
    }

    // ---------- 起動 ----------

    private suspend fun start() {
        if (!repo.isRegistered) {
            _screen.value = Screen.Setup(setupAddress())
            return
        }
        repo.onAppStart()
        // 未送信キューを送る → summary 取得。家の外では通信できないので、待ちすぎずにキャッシュで進める
        val job = viewModelScope.launch { handleSync(repo.syncAll()) }
        startupSync = job
        withTimeoutOrNull(4000) { job.join() }
        if (_screen.value !is Screen.Loading) return // 401/426 で画面が変わった
        showEncourage()
    }

    private fun handleSync(out: SyncOutcome) {
        when (out) {
            SyncOutcome.UNAUTHORIZED -> {
                // 登録解除：キューは保持したまま初回登録画面へ（プレイ中ならプレイ後に）
                if (_screen.value is Screen.Quiz || _screen.value is Screen.ModeResult) needsReregister = true
                else goSetup("とうろくが かいじょされました。もういちど とうろくしてね")
            }
            SyncOutcome.UPDATE_REQUIRED -> _screen.value = Screen.UpdateRequired
            SyncOutcome.NETWORK, SyncOutcome.SERVER_BUSY -> SyncWorker.enqueue(getApplication())
            else -> Unit
        }
    }

    private fun goSetup(error: String? = null) {
        _screen.value = Screen.Setup(setupAddress(), error = error)
    }

    /** 前回入れたアドレス。なければビルド時の既定値（BuildConfig.DEFAULT_SERVER_URL）。 */
    private fun setupAddress(): String =
        (repo.prefs.baseUrl ?: BuildConfig.DEFAULT_SERVER_URL).removePrefix("http://").removeSuffix("/")

    private suspend fun showEncourage() {
        val notice = repo.prefs.pointsUpdatedNotice
        repo.prefs.pointsUpdatedNotice = false // 1回だけ表示
        _screen.value = Screen.Encourage(Quiz.ENCOURAGEMENTS.random(), repo.localView(), notice)
        delay(if (notice) 3500 else 1800)
        if (_screen.value is Screen.Encourage) toModeSelect()
    }

    fun skipEncourage() {
        if (_screen.value is Screen.Encourage) viewModelScope.launch { toModeSelect() }
    }

    private suspend fun toModeSelect(notice: String? = null) {
        if (needsReregister) {
            needsReregister = false
            goSetup("とうろくが かいじょされました。もういちど とうろくしてね")
            return
        }
        val v = repo.localView()
        _screen.value = Screen.ModeSelect(v, notice = notice ?: if (v.questionsLeft == 0) Banner.DAY_DONE.text else null)
        // Android 13 以上：はじめて モード選択を開いたときに一度だけ、通知の許可を尋ねる（REQ-GAME-22）
        if (Build.VERSION.SDK_INT >= 33 && !repo.prefs.notifyPermissionAsked && !Reminders.canPost(getApplication())) {
            askNotify.value = true
        }
    }

    fun notifyAsked() {
        repo.prefs.notifyPermissionAsked = true
        askNotify.value = false
    }

    // ---------- 初回登録 ----------

    fun setAddress(text: String) {
        val s = _screen.value as? Screen.Setup ?: return
        _screen.value = s.copy(address = text, children = null, error = null)
    }

    fun loadChildren() {
        val s = _screen.value as? Screen.Setup ?: return
        val url = normalizeBaseUrl(s.address) ?: run {
            _screen.value = s.copy(error = "サーバの アドレスを いれてね（れい 192.168.1.10:8000）"); return
        }
        _screen.value = s.copy(busy = true, error = null)
        viewModelScope.launch {
            _screen.value = when (val r = repo.fetchChildren(url)) {
                is ApiResult.Ok -> s.copy(children = r.value, busy = false,
                    error = if (r.value.isEmpty()) "こどもが まだ とうろくされていません（かんりがめんで ついかしてね）" else null)
                is ApiResult.HttpError -> s.copy(busy = false, error = errorText(r))
                is ApiResult.NetworkError -> s.copy(busy = false, error = NETWORK_ERROR)
            }
        }
    }

    fun register(child: Child) {
        val s = _screen.value as? Screen.Setup ?: return
        val url = normalizeBaseUrl(s.address) ?: return
        _screen.value = s.copy(busy = true, error = null)
        viewModelScope.launch {
            when (val r = repo.register(url, child, BuildConfig.GAME_TITLE_ID)) {
                is ApiResult.Ok -> {
                    _screen.value = Screen.Loading
                    session = SessionTotals()
                    start()
                }
                // 失敗したらゲームは進めない（REQ-GAME-05）
                is ApiResult.HttpError -> _screen.value = s.copy(busy = false, error = errorText(r))
                is ApiResult.NetworkError -> _screen.value = s.copy(busy = false, error = NETWORK_ERROR)
            }
        }
    }

    private fun errorText(r: ApiResult.HttpError) = when {
        r.status == 409 -> "この なまえは もう とうろくされています。かんりがめんで とうろくを かいじょしてね"
        r.status == 426 -> "アプリを こうしんしてね"
        r.status >= 500 -> "サーバで エラーが おきました。もういちど ためしてね"
        else -> "とうろくできませんでした（${r.status} ${r.code ?: ""}）"
    }

    // ---------- モード選択と出題 ----------

    fun chooseDan(show: Boolean) {
        val s = _screen.value as? Screen.ModeSelect ?: return
        _screen.value = s.copy(choosingDan = show)
    }

    fun startMode(mode: String, dan: Int? = null) {
        val s = _screen.value as? Screen.ModeSelect ?: return
        val v = s.view
        if (v.questionsLeft == 0) return
        val d = if (mode == Modes.SEQUENTIAL) v.nextSequentialDan else dan
        startedAt = System.currentTimeMillis()
        questionShownAt = startedAt
        _screen.value = Screen.Quiz(mode, d, Quiz.questions(mode, d, minOf(Quiz.PER_MODE, v.questionsLeft)))
    }

    fun key(digit: Int) {
        val q = _screen.value as? Screen.Quiz ?: return
        if (q.showingAnswer || q.input.length >= 2) return
        sound(Sounds.S.TAP)
        _screen.value = q.copy(input = (q.input + digit).trimStart('0').ifEmpty { "0" })
    }

    fun clear() {
        val q = _screen.value as? Screen.Quiz ?: return
        if (!q.showingAnswer) _screen.value = q.copy(input = "")
    }

    /** 「けってい」で判定（桁数が揃っても自動判定しない、REQ-KUKU-05）。 */
    fun decide() {
        val q = _screen.value as? Screen.Quiz ?: return
        if (q.showingAnswer || q.lastCorrect == true || q.input.isEmpty()) return
        val ans = AnsweredQuestion(q.current.a, q.current.b, q.input.toInt(), System.currentTimeMillis() - questionShownAt)
        val answered = q.answered + ans
        if (ans.isCorrect) {
            sound(Sounds.S.CORRECT)
            _screen.value = q.copy(answered = answered, lastCorrect = true)
            viewModelScope.launch { delay(250); next(answered) }
        } else {
            sound(Sounds.S.WRONG)
            _screen.value = q.copy(answered = answered, lastCorrect = false, showingAnswer = true)
            viewModelScope.launch { delay(1100); next(answered) }
        }
    }

    private suspend fun next(answered: List<AnsweredQuestion>) {
        val q = _screen.value as? Screen.Quiz ?: return
        if (answered.size < q.questions.size) {
            questionShownAt = System.currentTimeMillis()
            _screen.value = q.copy(index = answered.size, input = "", showingAnswer = false, lastCorrect = null, answered = answered)
        } else {
            finishMode(q, answered)
        }
    }

    private suspend fun finishMode(q: Screen.Quiz, answered: List<AnsweredQuestion>) {
        val rec = repo.recordPlay(q.mode, q.dan, answered, startedAt, System.currentTimeMillis())
        Reminders.clear(getApplication()) // 遊んだので、出ている おしらせは消す
        val r = rec.result
        session.study += r.study
        session.selfBest += r.selfBestTotal
        if (r.isFirstPlayOfDay) { session.streak += r.streakBonus; session.streakDays = r.streakDays }
        val a = rec.after
        val banners = Banner.compute(rec.before, a)
        _screen.value = Screen.ModeResult(
            q.mode, q.dan, answered.count { it.isCorrect }, answered.size, r.study + r.selfBestTotal,
            r.selfBest, banners, canContinue = a.questionsLeft > 0,
            graduatedNow = (a.graduatedDans(Modes.SELECT) - rec.before.graduatedDans(Modes.SELECT)).sorted(),
            playedGraduated = q.dan != null && q.dan in rec.before.graduatedDans(q.mode),
        )
        sound(Sounds.S.FANFARE)
        if (r.selfBest.isNotEmpty() || banners.isNotEmpty()) viewModelScope.launch { delay(1300); sound(Sounds.S.SPARKLE) }
        // その場で送信を試み、だめなら WorkManager に任せる
        viewModelScope.launch {
            val out = repo.sync.flush()
            handleSync(out)
            if (out != SyncOutcome.DONE) SyncWorker.enqueue(getApplication())
        }
    }

    fun openWallet() {
        viewModelScope.launch {
            val off = repo.prefs.notifySettings?.enabled == true && !Reminders.canPost(getApplication())
            _screen.value = Screen.Wallet(repo.localView(), notifyOff = off)
        }
    }

    fun closeWallet() {
        if (_screen.value is Screen.Wallet) viewModelScope.launch { toModeSelect() }
    }

    fun continuePlaying() {
        viewModelScope.launch { toModeSelect() }
    }

    fun finishSession() {
        viewModelScope.launch {
            val v = repo.localView()
            _screen.value = Screen.SessionResult(session.study, session.streak, session.streakDays, session.selfBest, v)
            if (session.streak > 0) { delay(900); sound(Sounds.S.SPARKLE) }
        }
    }

    fun backToTop() {
        session = SessionTotals()
        viewModelScope.launch {
            if (needsReregister) toModeSelect() else { _screen.value = Screen.Loading; start() }
        }
    }

    fun quitQuiz() {
        // 途中でやめたモードは記録しない（9題そろったものだけが1プレイ）
        viewModelScope.launch { toModeSelect() }
    }

    companion object {
        const val NETWORK_ERROR = "サーバに つながりませんでした。いえの Wi-Fi に つないで、もういちど ためしてね"
    }
}
