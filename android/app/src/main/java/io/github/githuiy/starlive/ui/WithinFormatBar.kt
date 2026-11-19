package io.github.githuiy.starlive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.githuiy.starlive.data.WithinFontFamily
import io.github.githuiy.starlive.data.WithinTextStyle

private val WithinFonts = listOf(
    "默认" to null,
    "无衬线" to WithinFontFamily.SANS_SERIF,
    "衬线" to WithinFontFamily.SERIF,
    "等宽" to WithinFontFamily.MONOSPACE,
)
private val WithinSizes = listOf(null, 12, 14, 16, 18, 20, 24, 28, 32)
private val WithinColors = listOf("默认暖白" to null, "暖金" to Gold, "浅蓝" to Color(0xFFB9DEFF),
    "柔绿" to Color(0xFFBBE5CA), "粉紫" to Color(0xFFEAC5F4), "珊瑚" to Color(0xFFFFBEB0))

@Composable
internal fun WithinFormatBar(style: WithinTextStyle, enabled: Boolean, onFormat: ((WithinTextStyle) -> WithinTextStyle) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            WithinFormatChoice("within_format_bold", "加粗", style.bold, enabled,
                { onFormat { it.copy(bold = !style.bold) } }, Modifier.weight(1f)) { color ->
                Text("B", color = color, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            }
            WithinFormatChoice("within_format_italic", "斜体", style.italic, enabled,
                { onFormat { it.copy(italic = !style.italic) } }, Modifier.weight(1f)) { color ->
                Text("I", color = color, fontSize = 21.sp, fontStyle = FontStyle.Italic)
            }
            WithinFormatChoice("within_format_underline", "下划线", style.underline, enabled,
                { onFormat { it.copy(underline = !style.underline) } }, Modifier.weight(1f)) { color ->
                Text("U", color = color, fontSize = 21.sp, textDecoration = TextDecoration.Underline)
            }
            WithinFormatChoice("within_format_strikethrough", "删除线", style.strikethrough, enabled,
                { onFormat { it.copy(strikethrough = !style.strikethrough) } }, Modifier.weight(1f)) { color ->
                Text("S", color = color, fontSize = 21.sp, textDecoration = TextDecoration.LineThrough)
            }
            WithinFormatChoice("within_format_highlight", "高亮", style.highlight, enabled,
                { onFormat { it.copy(highlight = !style.highlight) } }, Modifier.weight(1f)) { color ->
                Text("A", Modifier.background(WithinHighlightChip, CircleShape).padding(horizontal = 5.dp),
                    color = color, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Text("字号", Modifier.padding(start = 8.dp), color = Muted, style = MaterialTheme.typography.labelMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            WithinSizes.forEach { size ->
                val label = size?.toString() ?: "默认"
                WithinFormatChoice("within_size_" + (size ?: "default"), "字号 " + label, style.fontSizeSp == size,
                    enabled, { onFormat { it.copy(fontSizeSp = size) } }) { color -> Text(label, color = color, maxLines = 1) }
            }
        }
        Text("文字颜色", Modifier.padding(start = 8.dp), color = Muted, style = MaterialTheme.typography.labelMedium)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            WithinColors.forEachIndexed { index, (name, color) ->
                val selected = style.colorArgb == color?.toArgb()
                WithinFormatChoice("within_color_" + index, name, selected, enabled,
                    { onFormat { it.copy(colorArgb = color?.toArgb()) } }) {
                    Box(Modifier.size(23.dp).background(color ?: WithinWritingInk, CircleShape),
                        contentAlignment = Alignment.Center) {
                        if (selected) Text("✓", color = Night, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Text("字形", Modifier.padding(start = 8.dp), color = Muted, style = MaterialTheme.typography.labelMedium)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            WithinFonts.forEach { (name, family) ->
                WithinFormatChoice("within_font_" + (family?.name ?: "default"), name, style.fontFamily == family,
                    enabled, { onFormat { it.copy(fontFamily = family) } }) { color -> Text(name, color = color, maxLines = 1) }
            }
        }
    }
}

private val WithinHighlightChip = Color(0xFF594A25)

@Composable
internal fun WithinFormatChoice(tag: String, label: String, active: Boolean, enabled: Boolean,
    onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable (Color) -> Unit) {
    val color = when {
        !enabled -> Muted
        active -> Gold
        else -> WithinWritingInk
    }
    TextButton(onClick = onClick, enabled = enabled,
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag(tag).semantics {
            selected = active
            contentDescription = label
        }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
        content(color)
    }
}
