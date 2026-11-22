package com.kotsukotsu.core

import com.kotsukotsu.core.api.AnswerDto
import com.kotsukotsu.core.api.ApiJson
import com.kotsukotsu.core.api.ApiResult
import com.kotsukotsu.core.api.Child
import com.kotsukotsu.core.api.GameApiPort
import com.kotsukotsu.core.api.RegistrationRequest
import com.kotsukotsu.core.data.GamePrefs
import com.kotsukotsu.core.data.PendingPlay
import com.kotsukotsu.core.data.PlayStore
import com.kotsukotsu.core.points.AnswerIn
import com.kotsukotsu.core.points.PlayInput
import com.kotsukotsu.core.points.PlayResult
import com.kotsukotsu.core.points.PointEngine
import com.kotsukotsu.core.sync.LocalCalculator
import com.kotsukotsu.core.sync.LocalView
import com.kotsukotsu.core.sync.SyncManager
import com.kotsukotsu.core.sync.SyncOutcome
import com.kotsukotsu.core.sync.tokyoDate
import com.kotsukotsu.core.notify.ReminderPlanner
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.random.Random

/** 1問の回答（ゲーム側の記録）。 */
data class AnsweredQuestion(val a: Int, val b: Int, val answer: Int?, val elapsedMs: Long) {
    val isCorrect: Boolean get() = answer == a * b
}

/** プレイを記録したときの、ローカル計算による結果（演出用）。 */
data class RecordedPlay(val playId: String, val result: PlayResult, val before: LocalView, val after: LocalView)

/**
 * ゲーム共通の窓口。第2弾以降のゲームも、これを使って登録・送信・同期する。
 * @param apiFactory サーバのアドレスから API クライアントを作る
 */
class GameRepository(
    val prefs: GamePrefs,
    private val store: PlayStore,
    private val apiFactory: (baseUrl: String) -> GameApiPort,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private var cachedApi: Pair<String, GameApiPort>? = null

    private fun api(baseUrl: String? = prefs.baseUrl): GameApiPort? {
        baseUrl ?: return null
        cachedApi?.let { if (it.first == baseUrl) return it.second }
        return apiFactory(baseUrl).also { cachedApi = baseUrl to it }
    }

    val sync = SyncManager({ api() }, store, prefs, clock)

    val isRegistered: Boolean get() = prefs.deviceToken != null && prefs.baseUrl != null && prefs.rules != null

    /** 子供一覧。未送信キューが残っているときは、前に登録していた子供だけを出す（キューの記録を別の子供に付けないため）。 */
    suspend fun fetchChildren(baseUrl: String): ApiResult<List<Child>> {
        return when (val r = api(baseUrl)!!.children()) {
            is ApiResult.Ok -> {
                val keepUser = prefs.userId.takeIf { store.pending().isNotEmpty() }
                val list = r.value.children.filter { keepUser == null || it.userId == keepUser }
                ApiResult.Ok(list, r.status)
            }
            is ApiResult.HttpError -> r
            is ApiResult.NetworkError -> r
        }
    }

    /** 初回登録（REQ-GAME-04/05）。失敗したらゲームは進めない。 */
    suspend fun register(baseUrl: String, child: Child, gameTitleId: String): ApiResult<Unit> {
        val r = api(baseUrl)!!.register(RegistrationRequest(gameTitleId, child.userId, prefs.deviceId))
        if (r !is ApiResult.Ok) return r as? ApiResult.HttpError ?: r as ApiResult.NetworkError
        prefs.baseUrl = baseUrl
        prefs.deviceToken = r.value.deviceToken
        if (prefs.userId != child.userId) prefs.summary = null
        prefs.userId = child.userId
        prefs.displayName = child.displayName
        prefs.rules = r.value.settings
        return ApiResult.Ok(Unit, r.status)
    }

    /** 起動時・通信回復時：キューを古い順に送る → 設定値と summary を取得（失敗時はキャッシュのまま）。 */
    suspend fun syncAll(): SyncOutcome {
        val out = sync.flush()
        if (out != SyncOutcome.DONE) return out
        val client = api() ?: return SyncOutcome.NOT_REGISTERED
        (client.settings() as? ApiResult.Ok)?.let { prefs.rules = it.value }
        when (val s = client.summary()) {
            is ApiResult.Ok -> { prefs.summary = s.value; prefs.summarySyncedAt = clock() }
            is ApiResult.HttpError -> if (s.status == 401) { prefs.clearRegistration(); return SyncOutcome.UNAUTHORIZED }
            is ApiResult.NetworkError -> return SyncOutcome.NETWORK
        }
        // おしらせ通知の設定（取れなければ前回の値のまま、docs/06 1.7）
        (client.notificationSettings() as? ApiResult.Ok)?.let { prefs.notifySettings = it.value }
        onSynced?.invoke()
        return SyncOutcome.DONE
    }

    /** 同期が終わったとき（通知の予約し直しに使う）。 */
    var onSynced: (() -> Unit)? = null

    /** 次の通知時刻（端末時刻 millis）。通知しない設定なら null。 */
    fun nextReminderAt(now: Long): Long? =
        prefs.notifySettings?.let { ReminderPlanner.nextFire(Instant.ofEpochMilli(now), it)?.toEpochMilli() }

    /**
     * 通知の時刻が来たとき：出すべきなら本文を返す（REQ-GAME-19〜21）。出さないなら null。
     * 判定は端末内の記録（未送信のプレイを含む）で行うので、外出中でも正しい。
     */
    suspend fun reminderText(now: Long, random: Random = Random.Default): String? {
        val s = prefs.notifySettings?.takeIf { it.enabled } ?: return null
        val rules = prefs.rules ?: return null
        val today = tokyoDate(now)
        val view = LocalCalculator.build(rules, prefs.summary, store.pending(), today)
        val played = view.prior.playedDates
        val last = ReminderPlanner.latest(prefs.lastPlayDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }, played.maxOrNull())
        if (!ReminderPlanner.shouldNotify(today, last, s.inactiveDays)) return null
        val v = ReminderPlanner.values(prefs.displayName.orEmpty(), today, played, view.balance, view.todayPointsLeft, view.monthLeft,
            rules.streakBonusPerDay)
        val msg = ReminderPlanner.pickMessage(s, v, prefs.notifyCounter, prefs.lastNotifyMessageId, random)
        prefs.notifyCounter = prefs.notifyCounter + 1
        prefs.lastNotifyMessageId = msg.messageId
        return ReminderPlanner.render(msg.text, v)
    }

    /** アプリ起動時に1回：前回の実行中に残った分はオフライン扱いにする。 */
    suspend fun onAppStart() = store.markAllOffline()

    suspend fun localView(): LocalView =
        LocalCalculator.build(prefs.rules!!, prefs.summary, store.pending(), tokyoDate(clock()))

    /** 1モード分の結果を、まず未送信キューに保存する（オンラインでも同じ経路）。送信は呼び出し側が sync.flush() する。 */
    suspend fun recordPlay(mode: String, dan: Int?, answers: List<AnsweredQuestion>, startedAt: Long, endedAt: Long): RecordedPlay {
        val before = localView()
        val input = PlayInput(mode, tokyoDate(endedAt), answers.map { AnswerIn(it.a, it.b, it.isCorrect) })
        val result = PointEngine.processPlay(before.rules, before.prior, input)
        val dtos = answers.mapIndexed { i, a -> AnswerDto(i + 1, a.a, a.b, a.answer, a.isCorrect, a.elapsedMs) }
        val playId = newId()
        val day = tokyoDate(endedAt)
        prefs.lastPlayDate = ReminderPlanner.latest(prefs.lastPlayDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }, day).toString()
        store.insert(
            PendingPlay(
                playId = playId, mode = mode, dan = dan, startedAtMillis = startedAt, endedAtMillis = endedAt,
                answersJson = ApiJson.encodeToString(ListSerializer(AnswerDto.serializer()), dtos),
                wasOffline = before.pendingCount > 0, // 先に未送信分があれば、このプレイもオフライン扱い
                clientRuleVersion = before.rules.version,
                localStudy = result.study, localStreakBonus = result.streakBonus, localSelfBestBonus = result.selfBestTotal,
            )
        )
        return RecordedPlay(playId, result, before, localView())
    }
}
