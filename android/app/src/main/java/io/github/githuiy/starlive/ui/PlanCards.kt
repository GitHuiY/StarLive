package io.github.githuiy.starlive.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val PlanRevealMillis = 260
private val PlanRevealEasing = CubicBezierEasing(.42f, 0f, .58f, 1f)

internal class PlanRevealState(private val scope: CoroutineScope, private val reduceMotion: Boolean) {
    private val animation = Animatable(0f)
    private var activeCardId by mutableStateOf<String?>(null)
    var openId by mutableStateOf<String?>(null)
        private set

    fun requestOpen(id: String): Boolean {
        if (activeCardId != null) return false
        activeCardId = id
        if (reduceMotion) {
            openId = id
        } else {
            scope.launch {
                animation.animateTo(1f, tween(PlanRevealMillis, easing = PlanRevealEasing))
                openId = id
            }
        }
        return true
    }

    fun close() {
        if (openId == null) return
        openId = null
        if (reduceMotion) {
            activeCardId = null
        } else {
            scope.launch {
                animation.animateTo(0f, tween(PlanRevealMillis, easing = PlanRevealEasing))
                activeCardId = null
            }
        }
    }

    fun progress(id: String): Float =
        if (activeCardId == id && openId == null) animation.value else 0f
}

@Composable
internal fun rememberPlanReveal(reduceMotion: Boolean, vararg keys: Any?): PlanRevealState =
    key(reduceMotion, *keys) {
        val scope = rememberCoroutineScope()
        remember { PlanRevealState(scope, reduceMotion) }
    }

@Composable
internal fun PlanTile(title: String, category: String, modifier: Modifier = Modifier,
    done: Boolean? = null, flash: () -> Float = { 0f }) {
    val tint = categoryColor(category)
    Surface(color = if (done == true) Panel else PanelSoft,
        shape = RoundedCornerShape(15.dp),
        border = BorderStroke(1.dp, if (done == true) Gold.copy(alpha = .32f) else Line),
        modifier = modifier) {
        Box {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).sizeIn(minHeight = 68.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.fillMaxHeight().background(tint.copy(alpha = .12f))
                    .padding(horizontal = 9.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center) {
                    Text(category.toList().joinToString("\n"), color = tint,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 15.sp),
                        textAlign = TextAlign.Center)
                }
                Text(title, color = if (done == false) Muted else Ink,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 11.dp))
            }
            FourPointFlash(flash, Modifier.matchParentSize())
        }
    }
}

@Composable
internal fun StarDetailDialog(title: String, category: String, tag: String,
    onDismiss: () -> Unit, actions: @Composable (close: () -> Unit) -> Unit = {},
    content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            if (window != null && window.attributes.windowAnimations != 0) window.setWindowAnimations(0)
        }
        val focusManager = LocalFocusManager.current
        val close: () -> Unit = {
            focusManager.clearFocus()
            onDismiss()
        }
        Surface(color = Panel, contentColor = Ink, shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, Line), modifier = Modifier.fillMaxWidth().testTag(tag)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(category, color = categoryColor(category), style = MaterialTheme.typography.labelMedium)
                Text(title, style = MaterialTheme.typography.titleLarge)
                content()
                actions(close)
                TextButton(onClick = close, modifier = Modifier.align(Alignment.End)) { Text("关闭") }
            }
        }
    }
}

@Composable
private fun FourPointFlash(progress: () -> Float, modifier: Modifier) {
    Canvas(modifier) {
        val fraction = progress()
        if (fraction <= 0f || size.minDimension <= 0f) return@Canvas
        val radius = minOf(size.height * .40f, size.width * .44f) * fraction
        val glowRadius = size.height * .17f
        val glowWidth = size.width * .52f * fraction
        scale(scaleX = glowWidth / glowRadius, scaleY = 1f, pivot = center) {
            drawCircle(
                Brush.radialGradient(
                    0f to Gold.copy(alpha = .60f),
                    .28f to Gold.copy(alpha = .32f),
                    1f to Color.Transparent,
                    center = center, radius = glowRadius
                ),
                center = center, radius = glowRadius, alpha = fraction
            )
        }
        val star = Path().apply {
            moveTo(center.x, center.y - radius)
            quadraticTo(center.x, center.y, center.x + radius, center.y)
            quadraticTo(center.x, center.y, center.x, center.y + radius)
            quadraticTo(center.x, center.y, center.x - radius, center.y)
            quadraticTo(center.x, center.y, center.x, center.y - radius)
            close()
        }
        drawPath(star, Starlight, alpha = fraction)
    }
}
