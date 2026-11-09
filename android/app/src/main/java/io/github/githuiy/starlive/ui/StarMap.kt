package io.github.githuiy.starlive.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

val Weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
val StarNames = listOf("天枢", "天璇", "天玑", "天权", "玉衡", "开阳", "摇光")
private val PortraitStars = listOf(
    Offset(.76f, .12f), Offset(.84f, .39f), Offset(.58f, .46f),
    Offset(.51f, .23f), Offset(.34f, .34f), Offset(.20f, .59f), Offset(.09f, .79f)
)

/** Six consecutive segments deliberately leave Monday–Thursday unconnected. */
@Composable
fun WeekStarMap(
    ratios: List<Float>, todayIndex: Int, selectedDay: Int, overview: Boolean,
    reduceMotion: Boolean, onDay: (Int) -> Unit, modifier: Modifier = Modifier,
    miniature: Boolean = false
) {
    val duration = if (reduceMotion || miniature) 0 else 1100
    val lights = (0..6).map { index ->
        animateFloatAsState(ratios.getOrElse(index) { 0f }.coerceIn(0f, 1f),
            tween(if (reduceMotion) 0 else 700), label = "star-light-$index").value
    }
    val zoom by animateFloatAsState(if (overview) 1f else 2.75f, tween(duration), label = "star-zoom")
    val cameraX by animateFloatAsState(if (overview) .5f else PortraitStars[selectedDay].x,
        tween(duration), label = "star-camera-x")
    val cameraY by animateFloatAsState(if (overview) .5f else PortraitStars[selectedDay].y,
        tween(duration), label = "star-camera-y")
    val anchorY by animateFloatAsState(if (overview) .5f else .34f,
        tween(duration), label = "star-anchor")
    BoxWithConstraints(modifier.clipToBounds()) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        fun point(index: Int): Offset = Offset(
            (PortraitStars[index].x - cameraX) * widthPx * zoom + widthPx / 2,
            (PortraitStars[index].y - cameraY) * heightPx * zoom + heightPx * anchorY
        )
        Canvas(Modifier.matchParentSize()) {
            for (index in 0..5) {
                drawLine(Gold.copy(alpha = if (overview) .36f else .10f),
                    point(index), point(index + 1), if (miniature) 1.dp.toPx() else .8.dp.toPx(),
                    cap = StrokeCap.Round)
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Muted.toArgb(); textAlign = Paint.Align.CENTER
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                textSize = 11.5f * density * fontScale
            }
            for (index in 0..6) {
                val center = point(index)
                val ratio = lights[index]
                val visible = overview || index == selectedDay
                val alpha = if (visible) 1f else .12f
                val halo = (if (miniature) 9f else 26f + ratio * 26f) * density
                drawCircle(Brush.radialGradient(listOf(
                    Gold.copy(alpha = (.08f + ratio * .30f) * alpha), Color.Transparent
                ), center, halo), radius = halo, center = center)
                if (index == todayIndex && !miniature) drawCircle(
                    Gold.copy(alpha = .38f * alpha), radius = 10.dp.toPx(), center = center,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(.7.dp.toPx()))
                val coreRadius = (if (miniature) 1.8f else 2.7f + ratio * 2.1f) * density
                drawCircle(Color(0xFFFFEDC7).copy(alpha = (.42f + ratio * .58f) * alpha),
                    coreRadius, center)
                if (!miniature && visible) {
                    val cross = (7 + ratio * 4).dp.toPx()
                    drawLine(Gold.copy(alpha = (.25f + ratio * .45f) * alpha),
                        center - Offset(cross, 0f), center + Offset(cross, 0f), .6.dp.toPx())
                    drawLine(Gold.copy(alpha = (.25f + ratio * .45f) * alpha),
                        center - Offset(0f, cross), center + Offset(0f, cross), .6.dp.toPx())
                }
                if (overview && !miniature) {
                    paint.color = (if (index == todayIndex) Gold else Muted).toArgb()
                    paint.textAlign = if (index == 6) Paint.Align.LEFT else Paint.Align.CENTER
                    val labelX = if (index == 6) center.x else center.x
                    drawContext.canvas.nativeCanvas.drawText(
                        if (fontScale > 1.3f) Weekdays[index] else "${Weekdays[index]} · ${StarNames[index]}", labelX,
                        center.y + 25.dp.toPx(), paint)
                }
            }
        }
        if (!miniature) for (index in 0..6) {
            if (overview || index == selectedDay) {
                val center = point(index)
                Box(Modifier.offset { IntOffset(
                    (center.x - 24.dp.toPx()).roundToInt(),
                    (center.y - 24.dp.toPx()).roundToInt())
                }.size(48.dp).testTag("star_$index").semantics {
                    contentDescription = "${Weekdays[index]}，${StarNames[index]}${if (index == todayIndex) "，今天" else ""}，完成${(ratios.getOrElse(index) { 0f } * 100).roundToInt()}%"
                }.clickable(role = Role.Button) { onDay(index) })
            }
        }
    }
}
