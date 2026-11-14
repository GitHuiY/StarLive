package io.github.githuiy.starlive.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

internal data class SparksWeekSlide(
    val monday: LocalDate,
    val nextMonday: LocalDate = monday,
    val fraction: Float = 0f,
    val direction: Int = 1,
    val settled: Boolean = true,
)

internal fun sparksWeekSlideAt(start: LocalDate, position: Float, direction: Int): SparksWeekSlide {
    // Round toward the outgoing page so the incoming page starts on the correct side.
    val offset = if (direction > 0) floor(position).toLong() else ceil(position).toLong()
    val outgoing = start.plusWeeks(offset)
    return SparksWeekSlide(outgoing, outgoing.plusWeeks(direction.toLong()),
        abs(position - offset), direction, settled = false)
}

@Composable
internal fun rememberSparksWeekSlide(monday: LocalDate, reduceMotion: Boolean): SparksWeekSlide {
    var slide by remember { mutableStateOf(SparksWeekSlide(monday)) }
    LaunchedEffect(monday, reduceMotion) {
        val start = slide
        val from = start.fraction * start.direction
        val to = ChronoUnit.WEEKS.between(start.monday, monday).toFloat()
        if (!reduceMotion && from != to) {
            val direction = if (to > from) 1 else -1
            val distance = abs(to - from)
            val duration = (540 + distance * 62).roundToInt().coerceAtMost(1050)
            animate(initialValue = from, targetValue = to,
                animationSpec = tween(duration, easing = FastOutSlowInEasing)) { position, _ ->
                slide = sparksWeekSlideAt(start.monday, position, direction)
            }
        }
        slide = SparksWeekSlide(monday)
    }
    return if (reduceMotion) SparksWeekSlide(monday)
        else if (slide.monday == monday) slide else slide.copy(settled = false)
}
