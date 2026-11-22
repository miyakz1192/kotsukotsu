package com.kotsukotsu.parent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kotsukotsu.parent.BuildConfig
import com.kotsukotsu.parent.ChildCard
import com.kotsukotsu.parent.LedgerEntry
import com.kotsukotsu.parent.OpKind
import com.kotsukotsu.parent.ParentViewModel
import com.kotsukotsu.parent.PendingOp
import com.kotsukotsu.parent.Screen
import com.kotsukotsu.parent.Snapshot
import com.kotsukotsu.parent.Ui
import com.kotsukotsu.parent.cancellable
import com.kotsukotsu.parent.ledgerTypeLabel
import com.kotsukotsu.parent.pendingTotal
import com.kotsukotsu.parent.provisionalBalance
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Bg = Color(0xFFE8F5E9)
private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFC62828)
private val Ink = Color(0xFF263238)
private val Mut = Color(0xFF607D8B)
private val Warn = Color(0xFFFFF3E0)

private val TOKYO = ZoneId.of("Asia/Tokyo")
private val FMT = DateTimeFormatter.ofPattern("M/d HH:mm")

/** UTC の ISO 8601 を日本時間で表示する（原則5）。 */
fun jst(iso: String?): String = iso?.let { runCatching { FMT.format(Instant.parse(it).atZone(TOKYO)) }.getOrNull() } ?: "-"

fun yen(v: Int): String = "%,d円".format(v)
fun signedYen(v: Int): String = (if (v > 0) "+" else if (v < 0) "−" else "") + "%,d円".format(kotlin.math.abs(v))

@Composable
fun ParentRoot(vm: ParentViewModel) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Green, background = Bg, onBackground = Ink)) {
        val ui by vm.ui.collectAsState()
        val snap by vm.snapshot.collectAsState()
        Box(Modifier.fillMaxSize().background(Bg).safeDrawingPadding()) {
            Column(Modifier.fillMaxSize()) {
                if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
                Box(Modifier.weight(1f)) {
                    when (val s = ui.screen) {
                        is Screen.Login -> LoginScreen(vm, ui, snap, s.reason)
                        Screen.ChangePassword -> PasswordScreen(vm, ui)
                        Screen.Home -> HomeScreen(vm, ui, snap)
                        is Screen.Move -> MoveScreen(vm, ui, snap, s.childUserId)
                        is Screen.History -> HistoryScreen(vm, ui, snap, s.childUserId)
                    }
                }
                ui.message?.let { msg ->
                    Row(Modifier.fillMaxWidth().background(Ink).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, color = Color.White, modifier = Modifier.weight(1f))
                        TextButton(onClick = vm::clearMessage) { Text("閉じる", color = Color.White) }
                    }
                }
            }
        }
        BackHandler(enabled = ui.screen is Screen.Move || ui.screen is Screen.History) { vm.back() }
    }
}

@Composable
private fun Title(text: String, onBack: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        if (onBack != null) TextButton(onClick = onBack) { Text("＜ もどる") }
        Text(text, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
    }
}

@Composable
private fun ErrorText(error: String?) {
    if (error != null) Text(error, color = Red, modifier = Modifier.padding(vertical = 8.dp))
}

// ---------- ログイン ----------

@Composable
private fun LoginScreen(vm: ParentViewModel, ui: Ui, snap: Snapshot, reason: String?) {
    var address by rememberSaveable { mutableStateOf(vm.defaultAddress()) }
    var loginId by rememberSaveable { mutableStateOf(snap.loginId ?: "") }
    var password by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Title("こつこつ おや")
        Text("家のWi-Fiでログインします。ログインした後は、外出先（サーバにつながらない場所）でもポイントを動かせます。", color = Mut)
        if (reason != null) Text(reason, color = Red, modifier = Modifier.padding(top = 8.dp))
        val pending = snap.ops.size
        if (pending > 0) Text("未送信の操作が${pending}件あります。ログインすると送ります。", color = Red)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(address, { address = it }, label = { Text("サーバのアドレス（例 192.168.0.2:8000）") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(loginId, { loginId = it }, label = { Text("ログインID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("パスワード") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        ErrorText(ui.error)
        Spacer(Modifier.height(12.dp))
        Button(onClick = { vm.login(address, loginId, password) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) { Text("ログイン") }
        Spacer(Modifier.height(24.dp))
        Text("版 ${BuildConfig.VERSION_NAME}", color = Mut, fontSize = 12.sp)
    }
}

@Composable
private fun PasswordScreen(vm: ParentViewModel, ui: Ui) {
    var cur by rememberSaveable { mutableStateOf("") }
    var new by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Title("パスワードの変更")
        Text("初回のログインのため、パスワードを変更してください。", color = Mut)
        listOf(Triple("今のパスワード", cur) { v: String -> cur = v }, Triple("新しいパスワード", new) { v: String -> new = v },
            Triple("新しいパスワード（確認）", confirm) { v: String -> confirm = v }).forEach { (label, value, onChange) ->
            OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        }
        ErrorText(ui.error)
        Button(onClick = { vm.changePassword(cur, new, confirm) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text("変更する")
        }
    }
}

// ---------- ホーム ----------

@Composable
private fun HomeScreen(vm: ParentViewModel, ui: Ui, snap: Snapshot) {
    LaunchedEffect(Unit) { vm.refresh() }
    var confirmLogout by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("お財布", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::refresh, enabled = !ui.busy) { Text("同期") }
            }
            Text("最終同期 ${jst(snap.syncedAt)}" + if (ui.offline) "（サーバにつながりません）" else "", color = if (ui.offline) Red else Mut)
        }
        ui.update?.let { upd ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Warn), modifier = Modifier.fillMaxWidth().clickable(onClick = vm::openUpdate)) {
                    Text("新しい版（${upd.versionName ?: upd.versionCode}）があります。タップしてダウンロードし、インストールしてください。",
                        modifier = Modifier.padding(12.dp))
                }
            }
        }
        if (snap.children.isEmpty()) {
            item { Text("子供の情報がまだありません。家のWi-Fiで「同期」してください。", color = Mut) }
        }
        items(snap.children, key = { it.userId }) { child -> ChildCardView(child, snap.ops, onClick = { vm.go(Screen.History(child.userId)) }) }
        val orphan = snap.ops.filter { op -> snap.children.none { it.userId == op.childUserId } }
        if (orphan.isNotEmpty()) item { Text("一覧にない子供への未送信の操作が${orphan.size}件あります", color = Red) }
        item {
            Button(onClick = { vm.go(Screen.Move(snap.children.singleOrNull()?.userId)) }, enabled = snap.children.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("ポイントを うごかす", fontSize = 18.sp) }
        }
        item {
            Spacer(Modifier.height(16.dp))
            Text("ログイン中：${snap.loginId ?: "-"}（${snap.baseUrl ?: "-"}）  版 ${BuildConfig.VERSION_NAME}", color = Mut, fontSize = 12.sp)
            TextButton(onClick = { confirmLogout = true }) { Text("ログアウト") }
        }
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("ログアウトしますか") },
            text = {
                Text(if (snap.ops.isNotEmpty()) "未送信の操作が${snap.ops.size}件あります。消さずに残し、次にログインしたときに送ります。外出先ではログインできないので注意してください。"
                else "外出先ではログインできないので注意してください。")
            },
            confirmButton = { TextButton(onClick = { confirmLogout = false; vm.logout() }) { Text("ログアウト") } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("やめる") } },
        )
    }
}

/** 子供のカード。サーバ残高・未送信・仮残高を区別して出す（REQ-APP-09、docs/06 2.3）。 */
@Composable
private fun ChildCardView(child: ChildCard, ops: List<PendingOp>, onClick: () -> Unit) {
    val pending = pendingTotal(ops, child.userId)
    val count = ops.count { it.childUserId == child.userId }
    val provisional = provisionalBalance(child, ops)
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp)) {
            Text(child.displayName, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(yen(provisional), fontSize = 36.sp, fontWeight = FontWeight.Bold, color = if (provisional < 0) Red else Ink)
            if (count > 0) {
                Row { Text("サーバ残高", color = Mut, modifier = Modifier.width(96.dp)); Text(yen(child.balance), color = if (child.balance < 0) Red else Ink) }
                Row { Text("未送信", color = Mut, modifier = Modifier.width(96.dp)); Text("${signedYen(pending)}（${count}件）", color = Red) }
                Row { Text("仮残高", color = Mut, modifier = Modifier.width(96.dp)); Text(yen(provisional), color = if (provisional < 0) Red else Ink) }
            }
            Spacer(Modifier.height(4.dp))
            Text("連続 ${child.streakDays}日 ・ 今月 ${child.monthEarned} / ${child.monthCap}pt", color = Mut)
        }
    }
}

// ---------- ポイントを動かす ----------

@Composable
private fun MoveScreen(vm: ParentViewModel, ui: Ui, snap: Snapshot, initialChild: String?) {
    var childId by rememberSaveable { mutableStateOf(initialChild) }
    var kind by rememberSaveable { mutableStateOf(OpKind.WITHDRAWAL) }
    var amountText by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val child = snap.children.firstOrNull { it.userId == childId }
    val amount = amountText.toIntOrNull()?.takeIf { it in 1..1_000_000 }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Title("ポイントを うごかす", onBack = vm::back)
        Text("子供", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            snap.children.forEach { c -> FilterChip(selected = c.userId == childId, onClick = { childId = c.userId }, label = { Text(c.displayName) }) }
        }
        Spacer(Modifier.height(8.dp))
        Text("種類", fontWeight = FontWeight.Bold)
        listOf(
            OpKind.WITHDRAWAL to "引き出し（使った・現金を渡した）−",
            OpKind.ADD to "ふやす（お手伝い・ごほうび）＋",
            OpKind.SUBTRACT to "へらす（訂正）−",
        ).forEach { (k, label) ->
            FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(amountText, { v -> amountText = v.filter { it.isDigit() }.take(7) }, label = { Text("金額（円＝ポイント）") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(note, { note = it.take(100) }, label = { Text("メモ（例 おかし、お手伝い）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (child != null && amount != null) {
            val delta = if (kind == OpKind.ADD) amount else -amount
            val after = provisionalBalance(child, snap.ops) + delta
            Text("記録後の仮残高：${yen(after)}", color = if (after < 0) Red else Mut, modifier = Modifier.padding(top = 8.dp))
            if (after < 0) Text("残高がマイナスになりますが、記録できます。", color = Red, fontSize = 12.sp)
        }
        ErrorText(ui.error)
        Button(onClick = { confirm = true }, enabled = child != null && amount != null && !ui.busy,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(52.dp)) { Text("確認する") }
    }
    if (confirm && child != null && amount != null) {
        val delta = if (kind == OpKind.ADD) amount else -amount
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("この内容で記録しますか") },
            text = { Text("${child.displayName}\n${kind.label} ${signedYen(delta)}" + (if (note.isNotBlank()) "\nメモ：$note" else "")) },
            confirmButton = { TextButton(onClick = { confirm = false; vm.record(child, kind, amount, note) }) { Text("記録する") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("やめる") } },
        )
    }
}

// ---------- 履歴 ----------

@Composable
private fun HistoryScreen(vm: ParentViewModel, ui: Ui, snap: Snapshot, childId: String) {
    val child = snap.children.firstOrNull { it.userId == childId } ?: return
    val ops = snap.ops.filter { it.childUserId == childId }
    var cancelOp by remember { mutableStateOf<PendingOp?>(null) }
    var cancelEntry by remember { mutableStateOf<LedgerEntry?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            Title("${child.displayName} の履歴", onBack = vm::back)
            ChildCardView(child, snap.ops, onClick = {})
            ErrorText(ui.error)
            Spacer(Modifier.height(12.dp))
        }
        if (ops.isNotEmpty()) {
            item { Text("未送信（${ops.size}件）", fontWeight = FontWeight.Bold, color = Red) }
            items(ops, key = { "op-" + it.id }) { op ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${jst(op.occurredAt)}  ${op.kind.label}" + (op.note?.let { " $it" } ?: ""))
                        op.problem?.let { Text("送れません：$it", color = Red, fontSize = 12.sp) }
                    }
                    Text(signedYen(op.points), color = if (op.points < 0) Red else Green)
                    if (op.kind != OpKind.CANCEL && op.id !in snap.cancelled) {
                        TextButton(onClick = { cancelOp = op }, enabled = !ui.busy) { Text("とりけし") }
                    }
                }
                HorizontalDivider()
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
        item {
            Text("台帳（最後に同期した分、新しい順）", fontWeight = FontWeight.Bold)
            if (ui.ledger == null) Text("まだありません。家のWi-Fiで開くと表示します。", color = Mut)
        }
        items(ui.ledger.orEmpty(), key = { "e-" + it.entryId }) { e ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${jst(e.occurredAt)}  ${ledgerTypeLabel(e.type)}" + (e.note?.let { " $it" } ?: ""), modifier = Modifier.weight(1f))
                Text(signedYen(e.points), color = if (e.points < 0) Red else Ink)
                if (e.cancellable() && e.transactionId !in snap.cancelled) {
                    TextButton(onClick = { cancelEntry = e }, enabled = !ui.busy) { Text("とりけし") }
                }
            }
            HorizontalDivider()
        }
    }
    cancelOp?.let { op ->
        CancelDialog("${op.kind.label} ${signedYen(op.points)}" + (op.note?.let { "（$it）" } ?: ""), -op.points,
            onYes = { cancelOp = null; vm.cancelPending(op) }, onNo = { cancelOp = null })
    }
    cancelEntry?.let { e ->
        CancelDialog("${ledgerTypeLabel(e.type)} ${signedYen(e.points)}" + (e.note?.let { "（$it）" } ?: ""), -e.points,
            onYes = { cancelEntry = null; vm.cancelEntry(child, e) }, onNo = { cancelEntry = null })
    }
}

@Composable
private fun CancelDialog(what: String, reverse: Int, onYes: () -> Unit, onNo: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNo,
        title = { Text("とりけしますか") },
        text = { Text("$what\n\n消すのではなく、逆向きの調整（${signedYen(reverse)}）を追加します。") },
        confirmButton = { TextButton(onClick = onYes) { Text("とりけす") } },
        dismissButton = { OutlinedButton(onClick = onNo) { Text("やめる") } },
    )
}
