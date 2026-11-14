package io.github.githuiy.starlive.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private const val LandingWidth = 1080f
private const val LandingHeight = 2400f
private val LandingStars = listOf(
    Offset(856.8f, 465.55f), Offset(904.8f, 663.91f), Offset(703.2f, 810.07f),
    Offset(607.2f, 684.79f), Offset(450.72f, 710.89f), Offset(288.48f, 721.33f),
    Offset(136.8f, 893.59f)
)
private val LandingWarmPoint = Color(0xFFF3D5A9)
private val LandingCoolPoint = Color(0xFFA8D8F0)
private val LandingWarmCore = Color(0xFFFFF3D8)
private val LandingCoolCore = Color(0xFFE5F3FB)
private val StarNameTypeface = Typeface.create("serif", Typeface.NORMAL)

@Composable
internal fun MondayLandingSky(
    ratios: List<Float>, todayIndex: Int, onDay: (Int) -> Unit, modifier: Modifier = Modifier,
    overview: Boolean = true, selectedDay: Int = 0, focusBounds: Rect? = null,
    focusProgress: Float = 0f, reduceMotion: Boolean = false,
    weekdayAnchor: Offset? = null, starNameAnchor: Offset? = null,
    showLabels: Boolean = true, interactive: Boolean = true
) {
    val density = LocalDensity.current
    val focusedNameSize = with(density) { MaterialTheme.typography.headlineMedium.fontSize.toPx() }
    val lights = LandingStars.indices.map { index ->
        animateFloatAsState(ratios.getOrElse(index) { 0f }.coerceIn(0f, 1f),
            tween(if (reduceMotion) 0 else 700), label = "landing-star-light-$index").value
    }
    BoxWithConstraints(modifier.fillMaxSize().then(if (overview && interactive) Modifier.testTag("overview") else Modifier)) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val scale = minOf(width / LandingWidth, height / LandingHeight)
        if (scale <= 0f) return@BoxWithConstraints
        // The illustration under this layer uses the same centered fit transform.
        val origin = Offset((width - LandingWidth * scale) / 2, (height - LandingHeight * scale) / 2)
        val skyOpacity = 1f - focusedSkyOpacity(focusProgress)
        // Slow the last part of the camera movement and align both stars
        // before handing off to the existing focused sky and its companions.
        val travel = (focusProgress / FocusedSkyBlendStart).coerceIn(0f, 1f)
        val easedTravel = travel + travel * travel * (1f - travel)
        val projectionOrigin = focusBounds?.let { bounds ->
            Offset(bounds.center.x, bounds.top + bounds.height * DayStarAnchorY)
        }
        val selectedPosition = StarPositions[selectedDay]
        val points = LandingStars.mapIndexed { index, position ->
            val overviewPoint = origin + position * scale
            if (focusBounds == null || projectionOrigin == null) overviewPoint else {
                val difference = StarPositions[index] - selectedPosition
                val focusedPoint = projectionOrigin + Offset(
                    difference.x * focusBounds.width * DayZoom,
                    difference.y * focusBounds.height * DayZoom
                )
                lerp(overviewPoint, focusedPoint, easedTravel)
            }
        }
        val zoomedScale = scale * (1f + .52f * focusProgress)
        val secondaryOpacity = (1f - focusProgress) * (1f - focusProgress)
        fun starOpacity(index: Int) = skyOpacity * if (index == selectedDay) 1f else secondaryOpacity
        if (skyOpacity > 0f) Canvas(Modifier.matchParentSize()) {
            points.forEachIndexed { index, center ->
                drawLandingHalo(center, zoomedScale, starCompletionLight(lights[index]),
                    warm = index == todayIndex, opacity = starOpacity(index))
            }
            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(
                path,
                Brush.linearGradient(
                    0f to Color(0xFF8CC5E9).copy(alpha = .38f),
                    .64f to Color(0xFFA0CDEB).copy(alpha = .58f),
                    1f to Color(0xFFC4CDE0).copy(alpha = .46f),
                    start = Offset(points.last().x, points.last().y),
                    end = Offset(points[1].x, points.first().y)
                ),
                alpha = skyOpacity * secondaryOpacity,
                style = Stroke(2.8f * zoomedScale, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            points.forEachIndexed { index, center ->
                drawLandingStar(center, zoomedScale, starCompletionLight(lights[index]),
                    warm = index == todayIndex, opacity = starOpacity(index))
            }
            if (showLabels) {
                drawLandingLabels(points, scale, todayIndex, secondaryOpacity,
                    except = if (focusProgress > 0f && weekdayAnchor != null && starNameAnchor != null) selectedDay else -1)
            }
        }
        if (showLabels && focusProgress > 0f && weekdayAnchor != null && starNameAnchor != null) {
            Canvas(Modifier.matchParentSize()) {
                drawStarLabel(selectedDay, origin + LandingStars[selectedDay] * scale, scale, todayIndex,
                    progress = easedTravel, weekdayAnchor = weekdayAnchor, nameAnchor = starNameAnchor,
                    nameSize = focusedNameSize)
            }
        }

        if (overview && interactive) points.forEachIndexed { index, center ->
            val closest = points.indices.filter { it != index }.minOf { (points[it] - center).getDistance() }
            val touchSize = with(density) { minOf(48.dp, closest.toDp()).coerceAtLeast(1.dp) }
            Box(Modifier
                .absoluteOffset {
                    val half = touchSize.roundToPx() / 2f
                    IntOffset((center.x - half).roundToInt(), (center.y - half).roundToInt())
                }
                .size(touchSize)
                .testTag("star_$index")
                .semantics {
                    val today = if (index == todayIndex) "，今天" else ""
                    contentDescription = "${ShortWeekdays[index]} · ${StarNames[index]}$today，完成${(lights[index] * 100).roundToInt()}%"
                }
                .clickable(interactionSource = null, indication = null, role = Role.Button) { onDay(index) })
        }
    }
}

private fun DrawScope.drawLandingHalo(center: Offset, scale: Float, light: Float, warm: Boolean, opacity: Float) {
    val radius = (if (warm) 113f else 88f) * (1f + .28f * light) * scale
    val brush = if (warm) Brush.radialGradient(
        0f to Color(0xFFEFCB9B).copy(alpha = .39f + .20f * light),
        .31f to Color(0xFFCDA676).copy(alpha = .15f + .08f * light),
        .69f to Color(0xFFA78879).copy(alpha = .045f),
        1f to Color(0xFFA78879).copy(alpha = 0f),
        center = center, radius = radius
    ) else Brush.radialGradient(
        0f to Color(0xFF9BCEEE).copy(alpha = .32f + .20f * light),
        .28f to Color(0xFF7CB1E0).copy(alpha = .16f + .08f * light),
        .62f to Color(0xFF719FCA).copy(alpha = .05f),
        1f to Color(0xFF719FCA).copy(alpha = 0f),
        center = center, radius = radius
    )
    drawCircle(brush, radius, center, alpha = opacity)
}

private fun DrawScope.drawLandingStar(center: Offset, scale: Float, light: Float, warm: Boolean, opacity: Float) {
    val point = if (warm) LandingWarmPoint else LandingCoolPoint
    val arm = (if (warm) 29f else 22f) * (1f + .25f * light) * scale
    val control = 2f * scale
    val (x, y) = center
    val shape = Path().apply {
        moveTo(x, y - arm)
        quadraticTo(x + control, y - control, x + arm, y)
        quadraticTo(x + control, y + control, x, y + arm)
        quadraticTo(x - control, y + control, x - arm, y)
        quadraticTo(x - control, y - control, x, y - arm)
        close()
    }
    drawPath(shape, point, alpha = ((if (warm) .47f else .35f) + .14f * light) * opacity)
    val pointRadius = (if (warm) 10f else 7.4f) * (1f + .34f * light) * scale
    if (warm) {
        drawCircle(point.copy(alpha = .38f * opacity), pointRadius + 7f * scale, center,
            style = Stroke(1.5f * scale))
    }
    drawCircle(point.copy(alpha = (.95f + .05f * light) * opacity), pointRadius, center)
    val coreRadius = (if (warm) 4.7f else 3.8f) * (1f + .22f * light) * scale
    drawCircle((if (warm) LandingWarmCore else LandingCoolCore).copy(alpha = opacity), coreRadius, center)
}

private fun DrawScope.landingLabelPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textAlign = Paint.Align.CENTER
    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    textSize = 11.sp.toPx()
    isSubpixelText = true
}

private fun DrawScope.stackLandingLabels(scale: Float) = scale * LandingWidth < 295.dp.toPx() || fontScale > 1.3f

private fun DrawScope.labelBaseline(index: Int, center: Offset, scale: Float, paint: Paint, stacked: Boolean): Float {
    val clearance = (if (index == 0) 38f else 31f) * scale
    val above = index == 0 || index == 3 || index == 5
    val metrics = paint.fontMetrics
    return if (above) center.y - clearance - metrics.descent - (if (stacked) paint.fontSpacing else 0f)
        else center.y + clearance - metrics.ascent
}

private fun DrawScope.labelCenterX(center: Offset, textWidth: Float): Float {
    val halfWidth = (textWidth / 2 + 3.dp.toPx()).coerceAtMost(size.width / 2)
    return center.x.coerceIn(halfWidth, size.width - halfWidth)
}

private fun DrawScope.drawLandingLabels(points: List<Offset>, scale: Float, todayIndex: Int, opacity: Float, except: Int) {
    points.forEachIndexed { index, center ->
        if (index != except) drawStarLabel(index, center, scale, todayIndex, opacity = opacity)
    }
}

private fun DrawScope.drawStarLabel(index: Int, center: Offset, scale: Float, todayIndex: Int,
    opacity: Float = 1f, progress: Float = 0f, weekdayAnchor: Offset? = null,
    nameAnchor: Offset? = null, nameSize: Float = 11.sp.toPx()) {
    val dayPaint = landingLabelPaint()
    val namePaint = Paint(dayPaint).apply { typeface = StarNameTypeface }
    val day = ShortWeekdays[index]
    val name = StarNames[index]
    val stacked = stackLandingLabels(scale)
    val dayWidth = dayPaint.measureText(day)
    val nameWidth = namePaint.measureText(name)
    val lineWidth = dayWidth + dayPaint.measureText(" · ") + nameWidth
    val x = labelCenterX(center, if (stacked) maxOf(dayWidth, nameWidth) else lineWidth)
    val baseline = labelBaseline(index, center, scale, dayPaint, stacked)
    val weekdayStart = Offset(if (stacked) x else x + (dayWidth - lineWidth) / 2f, baseline)
    val nameStart = if (stacked) Offset(x, baseline + dayPaint.fontSpacing)
        else Offset(x + (lineWidth - nameWidth) / 2f, baseline)
    val startColor = if (index == todayIndex) LandingWarmCore else Ink
    val canvas = drawContext.canvas.nativeCanvas
    val shadow = Night.copy(alpha = opacity * (1f - progress)).toArgb()
    dayPaint.setShadowLayer(2.dp.toPx(), 0f, 1.dp.toPx(), shadow)
    namePaint.setShadowLayer(2.dp.toPx(), 0f, 1.dp.toPx(), shadow)
    if (!stacked && progress < 1f) {
        dayPaint.color = startColor.copy(alpha = opacity * (1f - progress) * (1f - progress)).toArgb()
        canvas.drawText("·", x + (dayWidth - nameWidth) / 2f, baseline, dayPaint)
    }
    val dayPosition = lerp(weekdayStart, weekdayAnchor ?: weekdayStart, progress)
    dayPaint.color = lerpColor(startColor, Gold, progress).copy(alpha = opacity).toArgb()
    canvas.drawText(day, dayPosition.x, dayPosition.y, dayPaint)
    val namePosition = lerp(nameStart, nameAnchor ?: nameStart, progress)
    namePaint.textSize += (nameSize - namePaint.textSize) * progress
    namePaint.color = lerpColor(startColor, Ink, progress).copy(alpha = opacity).toArgb()
    canvas.drawText(name, namePosition.x, namePosition.y, namePaint)
}
