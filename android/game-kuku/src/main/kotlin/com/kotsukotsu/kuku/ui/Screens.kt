package com.kotsukotsu.kuku.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kotsukotsu.core.points.Modes
import com.kotsukotsu.core.sync.LocalView
import com.kotsukotsu.kuku.Banner
import com.kotsukotsu.kuku.GameViewModel
import com.kotsukotsu.kuku.Screen

@Composable
fun KukuRoot(vm: GameViewModel) {
    val screen by vm.screen.collectAsState()
    val soundOn by vm.soundOn.collectAsState()
    KukuTheme {
        Box(Modifier.fillMaxSize().background(Cream).safeDrawingPadding()) {
            when (val s = screen) {
                Screen.Loading -> Center { CircularProgressIndicator() }
                is Screen.Setup -> SetupScreen(s, vm)
                Screen.UpdateRequired -> Center {
                    Text("アプリを こうしんしてね", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("「こつこつ」から ひらくと あたらしく なるよ", fontSize = 18.sp)
                    Text("できない ときは おうちの ひとに たのんでね", fontSize = 16.sp)
                }
                is Screen.Encourage -> EncourageScreen(s, vm)
                is Screen.ModeSelect -> ModeSelectScreen(s, vm, soundOn)
                is Screen.Quiz -> QuizScreen(s, vm)
                is Screen.ModeResult -> ModeResultScreen(s, vm)
                is Screen.SessionResult -> SessionResultScreen(s, vm)
                is Screen.Wallet -> WalletScreen(s, vm)
            }
        }
    }
}

@Composable
private fun Center(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        content()
    }
}

@Composable
private fun BigButton(text: String, color: Color, enabled: Boolean = true, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.height(64.dp),
        shape = RoundedCornerShape(20.dp), colors = ButtonDefaults.buttonColors(containerColor = color),
    ) { Text(text, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
}

// ---------- 初回登録 ----------

@Composable
private fun SetupScreen(s: Screen.Setup, vm: GameViewModel) {
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("はじめの とうろく", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Ink)
        Text("おうちの ひとと いっしょに やってね", fontSize = 16.sp)
        OutlinedTextField(
            value = s.address, onValueChange = vm::setAddress, singleLine = true, enabled = !s.busy,
            label = { Text("サーバの アドレス（れい 192.168.1.10:8000）") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
        )
        if (s.children == null) BigButton("つぎへ", Orange, enabled = !s.busy) { vm.loadChildren() }
        if (s.busy) CircularProgressIndicator()
        s.error?.let { Text(it, color = Berry, fontSize = 18.sp) }
        s.children?.let { kids ->
            if (kids.isNotEmpty()) Text("じぶんの なまえを えらんでね", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            kids.forEach { c -> BigButton(c.displayName, Sky, enabled = !s.busy) { vm.register(c) } }
            TextButton(onClick = { vm.setAddress(s.address) }) { Text("アドレスを なおす") }
        }
    }
}

// ---------- 励まし ----------

@Composable
private fun EncourageScreen(s: Screen.Encourage, vm: GameViewModel) {
    Column(
        Modifier.fillMaxSize().clickable { vm.skipEncourage() }.padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(s.message, fontSize = 30.sp, fontWeight = FontWeight.Black, color = Orange, textAlign = TextAlign.Center, lineHeight = 42.sp)
        Spacer(Modifier.height(32.dp))
        Text("れんぞく ${s.view.streakDays} にち", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text("せいかいりつ ${(s.view.cumulativeAccuracy * 100).toInt()}%", fontSize = 24.sp, color = Ink)
        if (s.pointsUpdated) {
            Spacer(Modifier.height(24.dp))
            Text("ポイントが こうしん されました", fontSize = 20.sp, color = Sky, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(24.dp))
        Text("タップで すすむ", fontSize = 14.sp, color = Color.Gray)
    }
}

// ---------- モード選択 ----------

@Composable
private fun Stat(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 13.sp, color = Color.Gray)
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun StatsRow(v: LocalView) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Stat("きょう のこり", "${v.questionsLeft}もん", Sky, Modifier.weight(1f))
        Stat("きょうの ポイント", "あと${v.todayPointsLeft}", Orange, Modifier.weight(1f))
        Stat("こんげつ", "あと${v.monthLeft}", Grass, Modifier.weight(1f))
    }
}

@Composable
private fun ModeSelectScreen(s: Screen.ModeSelect, vm: GameViewModel, soundOn: Boolean) {
    val v = s.view
    val open = v.questionsLeft > 0
    NotifyPermissionDialog(vm)
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("れんぞく ${v.streakDays}にち", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
            TextButton(onClick = vm::toggleSound) { Text(if (soundOn) "🔊 おと オン" else "🔇 おと オフ", fontSize = 16.sp) }
        }
        StatsRow(v)
        WalletChip(v.balance, onClick = vm::openWallet)
        if (v.pendingCount > 0) Text("まだ おくっていない きろく：${v.pendingCount}こ（いえの Wi-Fi で おくるよ）", fontSize = 13.sp, color = Color.Gray)
        s.notice?.let { Text(it, fontSize = 26.sp, fontWeight = FontWeight.Black, color = Berry, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        if (s.choosingDan) {
            Text("どの だんに する？", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            val graduated = v.graduatedDans(Modes.SELECT)
            for (row in 0 until 3) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (c in 1..3) {
                    val dan = row * 3 + c
                    val done = dan in graduated
                    BigButton(if (done) "🎓${dan}" else "${dan}のだん", if (done) Color(0xFF90A4AE) else Sky, enabled = open,
                        modifier = Modifier.weight(1f)) { vm.startMode(Modes.SELECT, dan) }
                }
            }
            v.rules.graduation?.let { g ->
                if (graduated.isNotEmpty()) {
                    Text("🎓 は そつぎょうした だん。れんしゅう できるけど ポイントは 1もん ${g.pointsPerCorrect}だよ。" +
                        "まだの だんで ポイントを ためよう！", fontSize = 15.sp, color = Ink)
                } else {
                    Text("${g.rate}% できた だんは そつぎょう！（そのあと えらんでも ポイントは ${g.pointsPerCorrect}）", fontSize = 15.sp, color = Color.Gray)
                }
            }
            TextButton(onClick = { vm.chooseDan(false) }) { Text("もどる", fontSize = 18.sp) }
        } else {
            BigButton("じゅんばん（${v.nextSequentialDan}のだん）", Grass, enabled = open) { vm.startMode(Modes.SEQUENTIAL) }
            BigButton("だんを えらぶ", Sky, enabled = open) { vm.chooseDan(true) }
            BigButton("チャレンジ（ランダム）", Orange, enabled = open) { vm.startMode(Modes.CHALLENGE) }
            Text("チャレンジは 1もん ${v.rules.pointsPerCorrect[Modes.CHALLENGE]}ポイント！", fontSize = 15.sp, color = Color.Gray)
            Spacer(Modifier.height(8.dp))
            BigButton("おわる", Color.Gray) { vm.finishSession() }
        }
    }
}

/** はじめての モード選択で一度だけ：おしらせ通知の許可を尋ねる（Android 13 以上、docs/06 1.7）。 */
@Composable
private fun NotifyPermissionDialog(vm: GameViewModel) {
    val ask by vm.askNotify.collectAsState()
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.notifyAsked() }
    if (!ask || Build.VERSION.SDK_INT < 33) return
    AlertDialog(
        onDismissRequest = vm::notifyAsked,
        title = { Text("🔔 おしらせ", fontWeight = FontWeight.Bold) },
        text = { Text("九九を やって いない ひに、「きょうも やってみよう！」と おしらせしても いい？", fontSize = 18.sp) },
        confirmButton = { TextButton(onClick = { request.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("いいよ", fontSize = 18.sp) } },
        dismissButton = { TextButton(onClick = vm::notifyAsked) { Text("いらない", fontSize = 18.sp) } },
    )
}

// ---------- おさいふ ----------

/** モード選択の「おさいふ」ボタン。タップで おさいふ画面へ。 */
@Composable
private fun WalletChip(balance: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("💰 おさいふ", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
        Text("${yen(balance)}えん", fontSize = 26.sp, fontWeight = FontWeight.Black, color = if (balance < 0) Berry else Grass)
        Text("  ＞", fontSize = 20.sp, color = Color.Gray)
    }
}

private fun yen(v: Int) = "%,d".format(v)

@Composable
private fun Meter(label: String, value: Int, cap: Int, color: Color) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).padding(14.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, fontSize = 18.sp, color = Ink, modifier = Modifier.weight(1f))
            Text("$value", fontSize = 28.sp, fontWeight = FontWeight.Black, color = color)
            Text(" / $cap", fontSize = 16.sp, color = Color.Gray)
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { if (cap > 0) (value.toFloat() / cap).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)), color = color, trackColor = color.copy(alpha = 0.15f),
        )
    }
}

/** いまの ポイント（おさいふ）と、きょう・こんげつ ためた ポイント（docs/06 1.3.2）。 */
@Composable
private fun WalletScreen(s: Screen.Wallet, vm: GameViewModel) {
    val v = s.view
    BackHandler { vm.closeWallet() }
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("💰 おさいふ", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Ink)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.White).padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("いまの ポイント", fontSize = 18.sp, color = Color.Gray)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(yen(v.balance), fontSize = 64.sp, fontWeight = FontWeight.Black, color = if (v.balance < 0) Berry else Grass)
                Text(" えん", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.padding(bottom = 10.dp))
            }
            Text("1ポイント ＝ 1えん", fontSize = 14.sp, color = Color.Gray)
        }
        Meter("きょう ためた ポイント", v.todayStudyPoints, v.rules.dailyPointCap, Orange)
        Meter("こんげつ ためた ポイント", v.monthEarned, v.rules.monthlyCap, Sky)
        Text("🔥 れんぞく ${v.streakDays}にち", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text("おうちの ひとと おかいものに つかうと、おさいふの ポイントが へるよ", fontSize = 14.sp, color = Color.Gray, textAlign = TextAlign.Center)
        if (v.pendingCount > 0) {
            Text("まだ おくっていない きろくの ぶんも たしているよ。いえの Wi-Fi で おくると かくていするよ", fontSize = 13.sp, color = Color.Gray, textAlign = TextAlign.Center)
        }
        if (s.notifyOff) {
            Text("🔕 おしらせが オフに なっているよ（おうちの ひとに きいてね）", fontSize = 14.sp, color = Berry, textAlign = TextAlign.Center)
        }
        BigButton("もどる", Sky) { vm.closeWallet() }
    }
}

// ---------- 出題 ----------

@Composable
private fun QuizScreen(s: Screen.Quiz, vm: GameViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${s.index + 1} / ${s.questions.size}", fontSize = 20.sp, color = Color.Gray, modifier = Modifier.weight(1f))
            TextButton(onClick = vm::quitQuiz) { Text("やめる", color = Color.Gray) }
        }
        // 画面上部：問題（アニメーションは 200ms 以内）
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            AnimatedContent(targetState = s.index, transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(80)) }, label = "q") { idx ->
                val q = s.questions[idx]
                val isCurrent = idx == s.index
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${q.a} × ${q.b} =", fontSize = 56.sp, fontWeight = FontWeight.Black, color = Ink)
                        Spacer(Modifier.width(12.dp))
                        Box(
                            Modifier.width(110.dp).clip(RoundedCornerShape(16.dp))
                                .background(when (if (isCurrent) s.lastCorrect else null) { true -> Grass.copy(alpha = 0.25f); false -> Berry.copy(alpha = 0.2f); null -> Color.White })
                                .padding(vertical = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(if (isCurrent) s.input.ifEmpty { " " } else " ", fontSize = 56.sp, fontWeight = FontWeight.Black, color = Ink) }
                    }
                    when {
                        !isCurrent -> Text(" ", fontSize = 28.sp)
                        s.lastCorrect == true -> Text("せいかい！", fontSize = 28.sp, color = Grass, fontWeight = FontWeight.Bold)
                        s.showingAnswer -> Text("こたえは ${q.answer}", fontSize = 32.sp, color = Berry, fontWeight = FontWeight.Bold)
                        else -> Text(" ", fontSize = 28.sp)
                    }
                }
            }
        }
        // 画面中央〜下部：テンキー
        Keypad(vm)
    }
}

@Composable
private fun Keypad(vm: GameViewModel) {
    val rows = listOf(listOf("7", "8", "9"), listOf("4", "5", "6"), listOf("1", "2", "3"), listOf("けす", "0", "けってい"))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (r in rows) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (k in r) {
                val color = when (k) { "けってい" -> Orange; "けす" -> Color.Gray; else -> Sky }
                Button(
                    onClick = { when (k) { "けす" -> vm.clear(); "けってい" -> vm.decide(); else -> vm.key(k.toInt()) } },
                    modifier = Modifier.weight(1f).aspectRatio(1.6f), shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = color), contentPadding = PaddingValues(0.dp),
                ) { Text(k, fontSize = if (k.length > 1) 22.sp else 36.sp, fontWeight = FontWeight.Black) }
            }
        }
    }
}

// ---------- モード結果 ----------

@Composable
private fun ModeResultScreen(s: Screen.ModeResult, vm: GameViewModel) {
    Box(Modifier.fillMaxSize()) {
        Confetti()
        Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.height(16.dp))
            Text("${s.correct} / ${s.total} もん せいかい", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink)
            CountUpText(s.points, Orange, suffix = "pt")
            Text("ゲット！", fontSize = 32.sp, fontWeight = FontWeight.Black, color = Orange)
            s.selfBest.forEach {
                Text("🏆 ${it.dan}のだん ${it.threshold}% たっせい！ +${it.bonus}", fontSize = 22.sp, fontWeight = FontWeight.Black, color = Purple)
            }
            s.graduatedNow.forEach {
                Text("🎓 ${it}のだん そつぎょう！ つぎの だんに チャレンジしよう", fontSize = 22.sp, fontWeight = FontWeight.Black,
                    color = Grass, textAlign = TextAlign.Center)
            }
            if (s.playedGraduated) Text("そつぎょうした だんの れんしゅう だよ", fontSize = 16.sp, color = Color.Gray)
            s.banners.forEach { b ->
                Text(b.text, fontSize = 24.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
                    color = when (b) { Banner.DAY_DONE -> Berry; Banner.MONTH_DONE -> Grass; else -> Sky })
            }
            Spacer(Modifier.weight(1f))
            if (s.canContinue) BigButton("つづける", Grass) { vm.continuePlaying() }
            BigButton("おわる", Color.Gray) { vm.finishSession() }
        }
    }
}

// ---------- プレイ結果 ----------

@Composable
private fun SessionResultScreen(s: Screen.SessionResult, vm: GameViewModel) {
    Box(Modifier.fillMaxSize()) {
        Confetti(120)
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.height(24.dp))
            Text("きょうの けっか", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Ink)
            CountUpText(s.studyPoints + s.selfBestBonus + s.streakBonus, Orange, size = 88, suffix = "pt")
            Text("せいかい ${s.studyPoints}pt" + if (s.selfBestBonus > 0) "  じこベスト ${s.selfBestBonus}pt" else "", fontSize = 18.sp)
            if (s.streakBonus > 0) {
                Text("🔥 れんぞく ${s.streakDays}にち ボーナス +${s.streakBonus}", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Berry)
            }
            Text("おさいふ ${s.view.balance}えん", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Grass)
            Spacer(Modifier.weight(1f))
            BigButton("さいしょに もどる", Orange) { vm.backToTop() }
            Spacer(Modifier.size(8.dp))
        }
    }
}
