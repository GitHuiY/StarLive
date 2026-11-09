package io.github.githuiy.starlive.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

val Night = Color(0xFF0B1424)
val Panel = Color(0xFF142336)
val PanelSoft = Color(0xFF102033)
val Gold = Color(0xFFDFC38D)
val Ink = Color(0xFFD9E2E7)
val Muted = Color(0xFF91A3B4)
val Line = Color(0xFF293D51)

@Composable
fun StarLiveTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Gold, onPrimary = Night, secondary = Color(0xFF9DB6BB),
            background = Night, onBackground = Ink, surface = Panel,
            onSurface = Ink, surfaceVariant = PanelSoft, onSurfaceVariant = Muted,
            outline = Line, error = Color(0xFFE6A29B)
        ),
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 29.sp, lineHeight = 42.sp),
            headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 25.sp, lineHeight = 36.sp),
            titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 21.sp, lineHeight = 30.sp),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 23.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 20.sp),
            labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
            labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 17.sp)
        )
    ) {
        CompositionLocalProvider(LocalContentColor provides Ink, content = content)
    }
}

@Composable
fun StarBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.background(Night)) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.radialGradient(
                listOf(Color(0xFF183448).copy(alpha = .65f), Color.Transparent),
                center = Offset(size.width * .8f, size.height * .17f),
                radius = size.height * .75f
            ))
            repeat(76) { index ->
                val x = ((index * 73 + 19) % 997) / 997f * size.width
                val y = ((index * 137 + 41) % 991) / 991f * size.height
                drawCircle(Color(0xFFC7D8E7).copy(alpha = .10f + (index % 4) * .035f),
                    radius = (.45f + index % 3 * .35f) * density, center = Offset(x, y))
            }
        }
        content()
    }
}
