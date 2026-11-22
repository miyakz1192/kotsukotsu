package com.kotsukotsu.core

import com.kotsukotsu.core.api.ApiResult
import com.kotsukotsu.core.api.Awarded
import com.kotsukotsu.core.api.Child
import com.kotsukotsu.core.api.ChildrenResponse
import com.kotsukotsu.core.api.GameApiPort
import com.kotsukotsu.core.api.Month
import com.kotsukotsu.core.api.PlaySessionRequest
import com.kotsukotsu.core.api.PlaySessionResponse
import com.kotsukotsu.core.api.RegistrationRequest
import com.kotsukotsu.core.api.RegistrationResponse
import com.kotsukotsu.core.api.Summary
import com.kotsukotsu.core.api.Today
import com.kotsukotsu.core.data.GamePrefs
import com.kotsukotsu.core.data.PendingPlay
import com.kotsukotsu.core.data.PlayStore
import com.kotsukotsu.core.points.PointRules
import com.kotsukotsu.core.points.SelfBest
import com.kotsukotsu.core.points.Threshold
import java.io.IOException
import java.time.Instant

val RULES = PointRules(
    version = 1,
    pointsPerCorrect = mapOf("training_sequential" to 1, "training_select" to 1, "challenge" to 2),
    streakBonusPerDay = 1, dailyPointCap = 40, dailyQuestionCap = 117, monthlyCap = 1500,
    selfBest = SelfBest(27, listOf(Threshold(80, 10), Threshold(90, 20), Threshold(100, 30))),
)

class MemoryStore : PlayStore {
    val rows = mutableListOf<PendingPlay>()
    private var seq = 0L
    override suspend fun insert(play: PendingPlay) {
        if (rows.none { it.playId == play.playId }) rows += play.copy(localId = ++seq)
    }
    override suspend fun pending() = rows.filter { it.status == PendingPlay.STATUS_PENDING }.sortedBy { it.localId }
    override suspend fun delete(playId: String) { rows.removeAll { it.playId == playId } }
    private fun upd(id: String, f: (PendingPlay) -> PendingPlay) { val i = rows.indexOfFirst { it.playId == id }; if (i >= 0) rows[i] = f(rows[i]) }
    override suspend fun markOffline(playId: String) = upd(playId) { it.copy(wasOffline = true) }
    override suspend fun markAllOffline() { rows.replaceAll { if (it.status == PendingPlay.STATUS_PENDING) it.copy(wasOffline = true) else it } }
    override suspend fun markRejected(playId: String, reason: String) = upd(playId) { it.copy(status = PendingPlay.STATUS_REJECTED, rejectReason = reason) }
}

class MemoryPrefs : GamePrefs {
    override var baseUrl: String? = null
    override val deviceId = "dev-test"
    override var deviceToken: String? = null
    override var userId: String? = null
    override var displayName: String? = null
    override var rules: PointRules? = null
    override var summary: Summary? = null
    override var summarySyncedAt: Long = 0
    override var soundOn = true
    override var pointsUpdatedNotice = false
    override var notifySettings: com.kotsukotsu.core.notify.NotifySettings? = null
    override var lastPlayDate: String? = null
    override var notifyCounter = 0
    override var lastNotifyMessageId: String? = null
    override var notifyPermissionAsked = false
}

/** サーバの振る舞い（冪等性・401・503・通信不可）を真似るフェイク。 */
class FakeServer(var nowMillis: () -> Long) : GameApiPort {
    enum class Mode { OK, OFFLINE, BUSY_503, REVOKED_401 }
    var mode = Mode.OK
    val received = mutableListOf<PlaySessionRequest>()   // 受信順
    val ledgerByPlay = LinkedHashMap<String, Awarded>()   // play_id → 付与（冪等）
    var awardOverride: Awarded? = null
    var rejectPlayIds = mutableSetOf<String>()
    val children = listOf(Child("c1", "はると"), Child("c2", "ゆな"))
    var tokenSeq = 0

    private fun <T> gate(block: () -> ApiResult<T>): ApiResult<T> = when (mode) {
        Mode.OFFLINE -> ApiResult.NetworkError(IOException("offline"))
        Mode.BUSY_503 -> ApiResult.HttpError(503, "TRANSACTION_FAILED", null)
        Mode.REVOKED_401 -> ApiResult.HttpError(401, "UNAUTHORIZED", null)
        Mode.OK -> block()
    }

    fun currentSummary() = Summary(
        asOf = Instant.ofEpochMilli(nowMillis()).toString(), streakDays = 0, cumulativeAccuracy = 0.0,
        balance = ledgerByPlay.values.sumOf { it.total }, today = Today(0, 117, 0, 40, false), month = Month(0, 1500),
    )

    override suspend fun children() = gate { ApiResult.Ok(ChildrenResponse(children), 200) }
    override suspend fun register(req: RegistrationRequest) =
        gate { ApiResult.Ok(RegistrationResponse("r${++tokenSeq}", "tok$tokenSeq", RULES), 201) }
    override suspend fun settings() = gate { ApiResult.Ok(RULES, 200) }
    override suspend fun summary(): ApiResult<Summary> = gate { ApiResult.Ok(currentSummary(), 200) }
    var notify = com.kotsukotsu.core.notify.NotifySettings(weekdayTimes = listOf("07:15"), pointMessageEvery = 3)
    override suspend fun notificationSettings() = gate { ApiResult.Ok(notify, 200) }
    override suspend fun postPlay(req: PlaySessionRequest) = gate {
        if (req.playId in rejectPlayIds) return@gate ApiResult.HttpError(400, "INVALID_REQUEST", "bad")
        received += req
        val first = req.playId !in ledgerByPlay
        val aw = ledgerByPlay.getOrPut(req.playId) { awardOverride ?: Awarded(req.answers.count { it.answer == it.a * it.b }, 0, 0) }
        ApiResult.Ok(PlaySessionResponse(req.playId, "2026-12-01", aw, emptyList(), currentSummary()), if (first) 201 else 200)
    }
}
