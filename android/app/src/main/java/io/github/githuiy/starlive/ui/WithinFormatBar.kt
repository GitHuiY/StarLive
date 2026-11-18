package io.github.githuiy.starlive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.githuiy.starlive.data.WithinFontFamily
import io.github.githuiy.starlive.data.WithinTextStyle

private val WithinFonts = listOf(
    "默认" to null,
    "无衬线" to WithinFontFamily.SANS_SERIF,
    "衬线" to WithinFontFamily.SERIF,
    "等宽" to WithinFontFamily.MONOSPACE,
)
private val WithinSizes = listOf(null, 12, 14, 16, 18, 20, 24, 28, 32)
private val WithinColors = listOf("默认" to null, "暖金" to Gold, "浅蓝" to Color(0xFFB9DEFF),
    "柔绿" to Color(0xFFBBE5CA), "粉紫" to Color(0xFFEAC5F4), "珊瑚" to Color(0xFFFFBEB0))

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WithinFormatBar(style: WithinTextStyle, enabled: Boolean, onFormat: ((WithinTextStyle) -> WithinTextStyle) -> Unit) {
    Column {
        Text("选中文字可单独排版。未选中文字时，调整整篇正文。", color = Muted,
            style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WithinFormatMenu("字体", "within_format_font", enabled) { close ->
                WithinFonts.forEach { (name, family) ->
                    DropdownMenuItem(text = { Text(name) }, modifier = Modifier.testTag("within_font_${family?.name ?: "default"}"),
                        onClick = { onFormat { it.copy(fontFamily = family) }; close() })
                }
            }
            WithinFormatMenu("字号", "within_format_size", enabled) { close ->
                WithinSizes.forEach { size ->
                    DropdownMenuItem(text = { Text(size?.toString() ?: "默认") },
                        modifier = Modifier.testTag("within_size_${size ?: "default"}"),
                        onClick = { onFormat { it.copy(fontSizeSp = size) }; close() })
                }
            }
            WithinFormatMenu("颜色", "within_format_color", enabled) { close ->
                WithinColors.forEachIndexed { index, (name, color) ->
                    DropdownMenuItem(text = { Text(name) }, modifier = Modifier.testTag("within_color_$index"),
                        leadingIcon = { Box(Modifier.size(14.dp).background(color ?: Ink, CircleShape)) },
                        onClick = { onFormat { it.copy(colorArgb = color?.toArgb()) }; close() })
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(style.bold, { onFormat { it.copy(bold = !style.bold) } }, { Text("加粗") },
                enabled = enabled, modifier = Modifier.testTag("within_format_bold"))
            FilterChip(style.italic, { onFormat { it.copy(italic = !style.italic) } }, { Text("斜体") }, enabled = enabled)
            FilterChip(style.underline, { onFormat { it.copy(underline = !style.underline) } }, { Text("下划线") }, enabled = enabled)
        }
    }
}

@Composable
private fun WithinFormatMenu(label: String, tag: String, enabled: Boolean,
    menu: @Composable (close: () -> Unit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.testTag(tag)) { Text(label) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) { menu { expanded = false } }
    }
}
