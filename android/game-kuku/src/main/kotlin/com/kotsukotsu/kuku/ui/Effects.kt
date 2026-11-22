package com.kotsukotsu.kuku.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.random.Random

/** 数字をカウントアップしながら弾ませて表示する（獲得ポイントの派手な演出）。 */
@Composable
fun CountUpText(target: Int, color: Color, size: Int = 96, suffix: String = "") {
    val value = remember(target) { Animatable(0f) }
    val scale = remember(target) { Animatable(0.3f) }
    LaunchedEffect(target) {
        scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }
    LaunchedEffect(target) {
        value.animateTo(target.toFloat(), tween(durationMillis = (300 + target * 25).coerceAtMost(1400), easing = LinearEasing))
    }
    Text(
        "${value.value.toInt()}$suffix",
        modifier = Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value },
        style = TextStyle(fontSize = size.sp, fontWeight = FontWeight.Black, color = color),
    )
}

private data class Piece(val x: Float, val speed: Float, val sway: Float, val size: Float, val color: Color, val delay: Float)

/** 紙ふぶき。 */
@Composable
fun Confetti(count: Int = 70, modifier: Modifier = Modifier.fillMaxSize()) {
    val colors = listOf(Orange, Sky, Grass, Berry, Purple, Color(0xFFFFD54F))
    val pieces = remember { List(count) { Piece(Random.nextFloat(), 0.5f + Random.nextFloat(), Random.nextFloat() * 6f, 8f + Random.nextFloat() * 10f, colors.random(), Random.nextFloat() * 0.4f) } }
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) { t.animateTo(1f, tween(2600, easing = LinearEasing)) }
    Canvas(modifier) {
        for (p in pieces) {
            val tt = ((t.value - p.delay) / (1f - p.delay)).coerceIn(0f, 1f)
            if (tt <= 0f || tt >= 1f) continue
            val y = tt * p.speed * size.height * 1.2f
            val x = p.x * size.width + kotlin.math.sin(tt * p.sway * 6f) * 30f
            drawRect(p.color.copy(alpha = 1f - tt * 0.6f), topLeft = Offset(x, y), size = androidx.compose.ui.geometry.Size(p.size, p.size * 0.6f))
        }
    }
}
