package io.github.githuiy.starlive.ui

import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.github.githuiy.starlive.data.WithinFontFamily
import io.github.githuiy.starlive.data.WithinImageAttachment
import io.github.githuiy.starlive.data.WithinTextSpan
import io.github.githuiy.starlive.data.WithinTextStyle

internal val withinSpanSaver = listSaver<List<WithinTextSpan>, Any>(
    save = { spans -> spans.flatMap { listOf(it.start, it.end) + saveStyle(it.style) } },
    restore = { values -> values.chunked(8).map { WithinTextSpan(it[0] as Int, it[1] as Int, restoreStyle(it.drop(2))) } },
)

internal val withinStyleSaver = listSaver<WithinTextStyle, Any>(save = { saveStyle(it) }, restore = ::restoreStyle)

internal val withinImageSaver = listSaver<List<WithinImageAttachment>, Any>(
    save = { images -> images.flatMap { listOf(it.id, it.name, it.mimeType, it.offset) } },
    restore = { it.chunked(4).map { data -> WithinImageAttachment(data[0] as String, data[1] as String,
        data[2] as String, data[3] as Int) } },
)

private val plainWithinText = WithinTextStyle()

private fun saveStyle(style: WithinTextStyle): List<Any> = listOf(
    style.fontFamily?.name.orEmpty(), style.fontSizeSp ?: 0, style.colorArgb ?: 0,
    style.bold, style.italic, style.underline,
)

private fun restoreStyle(values: List<Any>): WithinTextStyle = WithinTextStyle(
    fontFamily = (values[0] as String).takeIf { it.isNotEmpty() }?.let(WithinFontFamily::valueOf),
    fontSizeSp = (values[1] as Int).takeIf { it != 0 },
    colorArgb = (values[2] as Int).takeIf { it != 0 },
    bold = values[3] as Boolean,
    italic = values[4] as Boolean,
    underline = values[5] as Boolean,
)

internal fun withinStyledText(body: String, spans: List<WithinTextSpan>, start: Int = 0, end: Int = body.length) =
    AnnotatedString.Builder(end - start).apply {
        append(body, start, end)
        for (span in spans) {
            val from = maxOf(start, span.start)
            val to = minOf(end, span.end)
            if (from < to) addStyle(span.style.composeStyle(), from - start, to - start)
        }
    }.toAnnotatedString()

private fun WithinTextStyle.composeStyle() = SpanStyle(
    color = colorArgb?.let { Color(it) } ?: Color.Unspecified,
    fontSize = fontSizeSp?.sp ?: TextUnit.Unspecified,
    fontFamily = when (fontFamily) {
        WithinFontFamily.SANS_SERIF -> FontFamily.SansSerif
        WithinFontFamily.SERIF -> FontFamily.Serif
        WithinFontFamily.MONOSPACE -> FontFamily.Monospace
        WithinFontFamily.CURSIVE -> FontFamily.Cursive
        null -> null
    },
    fontWeight = if (bold) FontWeight.Bold else null,
    fontStyle = if (italic) FontStyle.Italic else null,
    textDecoration = if (underline) TextDecoration.Underline else null,
)

internal fun withinStyleAt(spans: List<WithinTextSpan>, index: Int): WithinTextStyle =
    spans.firstOrNull { index >= it.start && index < it.end }?.style ?: plainWithinText

internal fun formatWithinText(spans: List<WithinTextSpan>, start: Int, end: Int,
    change: (WithinTextStyle) -> WithinTextStyle): List<WithinTextSpan> {
    val boundaries = (spans.flatMap { listOf(it.start, it.end) } + listOf(start, end)).distinct().sorted()
    return boundaries.zipWithNext { from, to ->
        val previous = withinStyleAt(spans, from)
        WithinTextSpan(from, to, if (from >= start && to <= end) change(previous) else previous)
    }.combineWithinSpans()
}

internal data class WithinTextEdit(val start: Int, val removedEnd: Int, val insertedEnd: Int) {
    private val shift get() = insertedEnd - removedEnd

    fun updateSpans(spans: List<WithinTextSpan>, insertedStyle: WithinTextStyle): List<WithinTextSpan> = buildList {
        addAll(spans.mapNotNull { span ->
            if (span.start < start) span.copy(end = minOf(span.end, start)) else null
        })
        if (start < insertedEnd) add(WithinTextSpan(start, insertedEnd, insertedStyle))
        addAll(spans.mapNotNull { span ->
            if (span.end > removedEnd) span.copy(start = maxOf(span.start, removedEnd) + shift,
                end = span.end + shift) else null
        })
    }.combineWithinSpans()

    fun updateImages(images: List<WithinImageAttachment>): List<WithinImageAttachment> = images.map { image ->
        when {
            image.offset <= start -> image
            image.offset <= removedEnd -> image.copy(offset = insertedEnd)
            else -> image.copy(offset = image.offset + shift)
        }
    }
}

internal fun withinTextEdit(old: TextFieldValue, new: TextFieldValue): WithinTextEdit? {
    if (old.text == new.text) return null
    val start = (old.composition ?: old.selection).min
    val end = (old.composition ?: old.selection).max
    val lengthDelta = new.text.length - old.text.length
    val insertedEnd = end + lengthDelta
    if (insertedEnd in start..new.text.length && old.text.regionMatches(0, new.text, 0, start) &&
        old.text.regionMatches(end, new.text, insertedEnd, old.text.length - end)) {
        return WithinTextEdit(start, end, insertedEnd)
    }
    val left = old.text.commonPrefixWith(new.text).length
    val right = old.text.substring(left).commonSuffixWith(new.text.substring(left)).length
    return WithinTextEdit(left, old.text.length - right, new.text.length - right)
}

private fun List<WithinTextSpan>.combineWithinSpans(): List<WithinTextSpan> = buildList {
    for (span in this@combineWithinSpans) {
        if (span.style == plainWithinText || span.start >= span.end) continue
        val previous = lastOrNull()
        if (previous != null && previous.end == span.start && previous.style == span.style) {
            this[lastIndex] = previous.copy(end = span.end)
        } else {
            add(span)
        }
    }
}
