package io.github.githuiy.starlive.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

private val CollapsedMusicWidth = 48.dp
private val ExpandedMusicWidth = 96.dp
private val MusicSurfaceInset = 5.dp

@Composable
internal fun MusicControl(playing: Boolean, onToggle: () -> Unit, reduceMotion: Boolean,
    modifier: Modifier = Modifier) {
    val width by animateDpAsState(
        targetValue = if (playing) ExpandedMusicWidth else CollapsedMusicWidth,
        animationSpec = tween(if (reduceMotion) 0 else 300, easing = FastOutSlowInEasing),
        label = "music-control-width",
    )
    val interactionSource = remember { MutableInteractionSource() }
    val indication = LocalIndication.current
    Box(modifier.width(width).height(48.dp).testTag("music_toggle")
        .semantics { contentDescription = if (playing) "暂停背景音乐" else "播放背景音乐" }
        .clickable(interactionSource = interactionSource, indication = null,
            role = Role.Button, onClick = onToggle)) {
        Surface(Modifier.fillMaxSize().padding(MusicSurfaceInset), shape = CircleShape,
            color = Night.copy(alpha = .70f), border = BorderStroke(1.dp, Gold.copy(alpha = .32f))) {
            Box {
                Box(Modifier.align(Alignment.CenterStart).size(38.dp).clearAndSetSemantics {},
                    contentAlignment = Alignment.Center) {
                    Text("♫", color = Gold, fontFamily = FontFamily.Serif,
                        fontSize = with(LocalDensity.current) { 23.dp.toSp() })
                }
                if (playing) {
                    val opacity = ((width - 74.dp) / (ExpandedMusicWidth - 74.dp)).coerceIn(0f, 1f)
                    MusicBars(reduceMotion, opacity,
                        Modifier.align(Alignment.CenterEnd).padding(end = 11.dp).size(26.dp, 17.dp))
                }
            }
        }
        Canvas(Modifier.matchParentSize().drawWithCache {
            val inset = MusicSurfaceInset.roundToPx().toFloat()
            val capsule = Rect(inset, inset, size.width - inset, size.height - inset)
            val radius = CornerRadius(minOf(capsule.width, capsule.height) / 2f)
            val outline = Path().apply { addRoundRect(RoundRect(capsule, radius)) }
            onDrawWithContent { clipPath(outline) { this@onDrawWithContent.drawContent() } }
        }.indication(interactionSource, indication)) {}
    }
}

@Composable
private fun MusicBars(reduceMotion: Boolean, opacity: Float, modifier: Modifier = Modifier) {
    val phase = if (reduceMotion) null else rememberInfiniteTransition(label = "music-bars")
        .animateFloat(initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1_500, easing = LinearEasing)), label = "music-bars-phase")
    Canvas(modifier.testTag("music_playing_indicator")) {
        val rhythm = phase?.value ?: 0f
        val heights = floatArrayOf(.45f, .67f, .86f, .64f, .47f)
        heights.forEachIndexed { index, height ->
            val x = size.width * (index + .5f) / heights.size
            val variation = sin(2 * PI * rhythm + index * 1.2).toFloat()
            val half = size.height * (height + variation * .16f) / 2
            drawLine(Gold.copy(alpha = .80f * opacity), Offset(x, center.y - half), Offset(x, center.y + half),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}
