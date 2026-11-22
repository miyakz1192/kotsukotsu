package com.kotsukotsu.launcher.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kotsukotsu.launcher.AppRow
import com.kotsukotsu.launcher.BuildConfig
import com.kotsukotsu.launcher.LauncherViewModel
import com.kotsukotsu.launcher.Screen

private val Bg = Color(0xFFE3F2FD)
private val Blue = Color(0xFF1E88E5)
private val Ink = Color(0xFF263238)
private val Warn = Color(0xFFFFF3E0)
private val CardColors = listOf(Color(0xFFFFB300), Color(0xFF43A047), Color(0xFFE53935), Color(0xFF8E24AA), Color(0xFF00897B))

@Composable
fun LauncherRoot(vm: LauncherViewModel) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Blue, background = Bg, onBackground = Ink)) {
        val screen by vm.screen.collectAsState()
        Box(Modifier.fillMaxSize().background(Bg).safeDrawingPadding()) {
            when (val s = screen) {
                Screen.Loading -> Center { CircularProgressIndicator() }
                is Screen.Setup -> SetupScreen(s, vm)
                is Screen.Home -> HomeScreen(s, vm)
                is Screen.Working -> WorkingScreen(s)
            }
        }
    }
}

@Composable
private fun Center(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun SetupScreen(s: Screen.Setup, vm: LauncherViewModel) {
    var text by remember(s.address) { mutableStateOf(s.address) }
    Center {
        Text("さいしょの せってい", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text("おうちの ひとと いっしょに やってね", fontSize = 16.sp, color = Ink)
        OutlinedTextField(
            value = text, onValueChange = { text = it }, singleLine = true,
            label = { Text("サーバの アドレス（れい 192.168.0.2:8000）") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        s.error?.let { Text(it, color = Color(0xFFC62828), textAlign = TextAlign.Center) }
        Button(onClick = { vm.connect(text) }, enabled = !s.busy, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(if (s.busy) "つないで いるよ…" else "つなぐ", fontSize = 20.sp)
        }
    }
}

@Composable
private fun HomeScreen(s: Screen.Home, vm: LauncherViewModel) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("なにで あそぶ？", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Ink,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), textAlign = TextAlign.Center)
        }
        s.message?.let { m -> item { Notice(m) } }
        if (s.needsPermission) item {
            Notice("アプリを いれたり あたらしく したり するには「きょか」が いるよ。おうちの ひとに たのんでね") {
                Button(onClick = vm::openInstallPermission) { Text("きょかの せってい を ひらく") }
            }
        }
        if (s.offline) item { Text("いまは サーバに つながって いないよ（はいって いる ゲームは あそべるよ）", color = Ink, fontSize = 14.sp) }
        if (s.games.isEmpty()) item { Text("まだ ゲームが ないよ", fontSize = 18.sp, color = Ink) }
        items(s.games.size) { i -> GameCard(s.games[i], CardColors[i % CardColors.size]) { vm.play(s.games[i]) } }
        s.launcherUpdate?.let { u ->
            item {
                TextButton(onClick = { vm.updateLauncher(u) }, modifier = Modifier.fillMaxWidth()) {
                    Text("あたらしい「こつこつ」が あるよ（タップで こうしん）")
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("ばん ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = vm::openSetup) { Text("せってい", fontSize = 12.sp, color = Color.Gray) }
            }
        }
    }
}

@Composable
private fun Notice(text: String, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Warn).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, color = Ink, fontSize = 16.sp)
        action?.invoke()
    }
}

@Composable
private fun GameCard(row: AppRow, color: Color, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(color).clickable(onClick = onClick).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(row.item.title, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
        val status = when {
            row.installedVersion == null -> "タップして いれる"
            row.updatable -> "あたらしく なったよ！"
            else -> "タップで あそぶ"
        }
        Text(status, fontSize = 16.sp, color = Color.White)
    }
}

@Composable
private fun WorkingScreen(s: Screen.Working) {
    Center {
        Text(s.title, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text(s.phase, fontSize = 20.sp, color = Ink)
        if (s.progress != null) {
            LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)))
        } else {
            CircularProgressIndicator()
        }
    }
}
