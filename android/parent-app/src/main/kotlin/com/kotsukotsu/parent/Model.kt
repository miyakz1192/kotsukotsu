package com.kotsukotsu.parent

import kotlinx.serialization.Serializable

/** ポイントを動かす種類（docs/06 2.1）。 */
enum class OpKind(val label: String) {
    /** 使った・現金を渡した（−、POST /withdrawals） */
    WITHDRAWAL("引き出し"),
    /** お手伝い・ごほうび（＋、POST /adjustments） */
    ADD("ふやす"),
    /** 訂正（−、POST /adjustments） */
    SUBTRACT("へらす"),
    /** とりけし（逆向きの調整、POST /adjustments。REQ-APP-11） */
    CANCEL("とりけし");

    val isWithdrawal get() = this == WITHDRAWAL
}

/**
 * 未送信の操作。端末に保存し、送れたら消す（docs/06 2.2）。
 * @param id 冪等キー（transaction_id／adjustment_id）。再送しても同じ値を使う
 * @param points 残高への影響（符号付き）。引き出しは負
 * @param occurredAt 操作した時刻（UTC、ISO 8601）
 * @param cancels とりけしの場合、取り消す元の操作のID
 * @param problem 送れない理由（サーバが内容を受け付けなかった）。捨てずに残し、とりけしで相殺する
 */
@Serializable
data class PendingOp(
    val id: String,
    val kind: OpKind,
    val childUserId: String,
    val childName: String,
    val points: Int,
    val note: String?,
    val occurredAt: String,
    val cancels: String? = null,
    val problem: String? = null,
)

/** 端末に保存するもの。テストではメモリ上の実装を使う。 */
interface ParentStore {
    var baseUrl: String?
    var loginId: String?
    var token: String?
    var children: List<ChildCard>
    /** children を取得した時刻（UTC、ISO 8601）。 */
    var syncedAt: String?
    var ops: List<PendingOp>
    /** とりけし済みの操作のID（同じものを2回取り消さないため）。 */
    var cancelled: Set<String>
    fun ledger(childUserId: String): List<LedgerEntry>?
    fun setLedger(childUserId: String, entries: List<LedgerEntry>)
}

/** 仮残高＝サーバ残高＋未送信の合計（docs/06 2.3）。 */
fun pendingTotal(ops: List<PendingOp>, childUserId: String): Int = ops.filter { it.childUserId == childUserId }.sumOf { it.points }

fun provisionalBalance(card: ChildCard, ops: List<PendingOp>): Int = card.balance + pendingTotal(ops, card.userId)

/** 台帳の行で、とりけしできるもの（親の操作だけ）。 */
fun LedgerEntry.cancellable(): Boolean = (type == "withdrawal" || type == "adjustment") && transactionId != null

fun ledgerTypeLabel(type: String): String = when (type) {
    "study" -> "学習"
    "streak_bonus" -> "連続日数ボーナス"
    "self_best_bonus" -> "自己ベストボーナス"
    "withdrawal" -> "引き出し"
    "adjustment" -> "調整"
    else -> type
}
