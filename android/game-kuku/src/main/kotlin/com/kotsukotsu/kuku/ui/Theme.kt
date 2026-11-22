package com.kotsukotsu.kuku.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Cream = Color(0xFFFFF8E1)
val Orange = Color(0xFFFF8F00)
val Sky = Color(0xFF29B6F6)
val Grass = Color(0xFF43A047)
val Berry = Color(0xFFE53935)
val Ink = Color(0xFF3E2723)
val Purple = Color(0xFF8E24AA)

@Composable
fun KukuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(primary = Orange, secondary = Sky, background = Cream, surface = Color.White, onBackground = Ink),
        content = content,
    )
}
