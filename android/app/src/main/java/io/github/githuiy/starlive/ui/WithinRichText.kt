package io.github.githuiy.starlive.ui

import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.github.githuiy.starlive.data.WithinFontFamily
import io.github.githuiy.starlive.data.WithinImageAttachment
import io.github.githuiy.starlive.data.WithinListStyle
import io.github.githuiy.starlive.data.WithinParagraphAlignment
import io.github.githuiy.starlive.data.WithinParagraphStyle
import io.github.githuiy.starlive.data.WithinTextSpan
import io.github.githuiy.starlive.data.WithinTextStyle

internal val withinSpanSaver = listSaver<List<WithinTextSpan>, Any>(
    save = { spans -> listOf("v2") + spans.flatMap { listOf(it.start, it.end) + saveStyle(it.style) } },
    restore = { values ->
        val current = values.firstOrNull() == "v2"
        val records = if (current) values.drop(1) else values
        records.chunked(if (current) 10 else 8).map {
            WithinTextSpan(it[0] as Int, it[1] as Int, restoreStyle(it.drop(2)))
        }
    },
)

internal val withinStyleSaver = listSaver<WithinTextStyle, Any>(save = { saveStyle(it) }, restore = ::restoreStyle)

internal val withinImageSaver = listSaver<List<WithinImageAttachment>, Any>(
    save = { images -> images.flatMap { listOf(it.id, it.name, it.mimeType, it.offset) } },
    restore = { it.chunked(4).map { data -> WithinImageAttachment(data[0] as String, data[1] as String,
        data[2] as String, data[3] as Int) } },
)

internal val withinParagraphSaver = listSaver<List<WithinParagraphStyle>, Any>(
    save = { paragraphs -> paragraphs.flatMap { listOf(it.offset, it.alignment.name, it.list.name) } },
    restore = { values -> values.chunked(3).map {
        WithinParagraphStyle(it[0] as Int, WithinParagraphAlignment.valueOf(it[1] as String),
            WithinListStyle.valueOf(it[2] as String))
    } },
)

private val plainWithinText = WithinTextStyle()

private fun saveStyle(style: WithinTextStyle): List<Any> = listOf(
    style.fontFamily?.name.orEmpty(), style.fontSizeSp ?: 0, style.colorArgb ?: 0,
    style.bold, style.italic, style.underline, style.strikethrough, style.highlight,
)

private fun restoreStyle(values: List<Any>): WithinTextStyle = WithinTextStyle(
    fontFamily = (values[0] as String).takeIf { it.isNotEmpty() }?.let(WithinFontFamily::valueOf),
    fontSizeSp = (values[1] as Int).takeIf { it != 0 },
    colorArgb = (values[2] as Int).takeIf { it != 0 },
    bold = values[3] as Boolean,
    italic = values[4] as Boolean,
    underline = values[5] as Boolean,
    strikethrough = values.getOrNull(6) as? Boolean ?: false,
    highlight = values.getOrNull(7) as? Boolean ?: false,
)

internal fun withinStyledText(
    body: String, spans: List<WithinTextSpan>, start: Int = 0, end: Int = body.length,
    paragraphs: List<WithinParagraphStyle> = emptyList(), listMarkers: Boolean = false,
): AnnotatedString = withinRenderedText(body, spans, start, end, paragraphs, listMarkers).text

internal fun withinEditorText(
    body: String, spans: List<WithinTextSpan>, paragraphs: List<WithinParagraphStyle>
): TransformedText = withinRenderedText(body, spans, 0, body.length, paragraphs, listMarkers = true)

private fun withinRenderedText(
    body: String, spans: List<WithinTextSpan>, start: Int, end: Int,
    paragraphs: List<WithinParagraphStyle>, listMarkers: Boolean,
): TransformedText {
    require(start in 0..end && end <= body.length)
    val starts = withinParagraphStarts(body)
    val markers = if (listMarkers) withinListMarkers(starts, paragraphs).filter {
        it.offset >= start && (it.offset < end || it.offset == body.length && end == body.length)
    } else emptyList()
    val mapping = WithinListOffsetMapping(end - start, markers.map { it.copy(offset = it.offset - start) })
    val text = AnnotatedString.Builder(end - start).apply {
        var cursor = start
        for (marker in markers) {
            append(body, cursor, marker.offset)
            append(marker.text)
            cursor = marker.offset
        }
        append(body, cursor, end)
        for (span in spans) {
            val from = maxOf(start, span.start)
            val to = minOf(end, span.end)
            if (from < to) addStyle(span.style.composeStyle(),
                mapping.originalToTransformed(from - start), mapping.beforeMarker(to - start))
        }
        starts.forEachIndexed { index, offset ->
            val paragraphEnd = starts.getOrNull(index + 1) ?: body.length
            val from = maxOf(start, offset)
            val to = minOf(end, paragraphEnd)
            if (from < to || offset == body.length && start <= offset && end == offset) {
                val alignment = paragraphs.firstOrNull { it.offset == offset }?.alignment
                    ?: WithinParagraphAlignment.START
                addStyle(ParagraphStyle(textAlign = alignment.composeAlignment()),
                    mapping.beforeMarker(from - start),
                    if (offset == body.length) mapping.originalToTransformed(to - start)
                    else mapping.beforeMarker(to - start))
            }
        }
    }.toAnnotatedString()
    return TransformedText(text, mapping)
}

private fun WithinParagraphAlignment.composeAlignment() = when (this) {
    WithinParagraphAlignment.START -> TextAlign.Start
    WithinParagraphAlignment.CENTER -> TextAlign.Center
    WithinParagraphAlignment.END -> TextAlign.End
}

private data class WithinListMarker(val offset: Int, val text: String)

/** Prefixes are view-only. TextFieldValue, selections and attachment anchors remain in source offsets. */
private class WithinListOffsetMapping(
    private val sourceLength: Int, private val markers: List<WithinListMarker>
) : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int = offset +
        markers.takeWhile { it.offset <= offset }.sumOf { it.text.length }

    fun beforeMarker(offset: Int): Int = offset +
        markers.takeWhile { it.offset < offset }.sumOf { it.text.length }

    override fun transformedToOriginal(offset: Int): Int {
        var prefixLength = 0
        for (marker in markers) {
            val prefixStart = marker.offset + prefixLength
            if (offset < prefixStart) break
            if (offset <= prefixStart + marker.text.length) return marker.offset
            prefixLength += marker.text.length
        }
        return (offset - prefixLength).coerceIn(0, sourceLength)
    }
}

private fun withinListMarkers(starts: List<Int>, paragraphs: List<WithinParagraphStyle>): List<WithinListMarker> {
    var previous = WithinListStyle.NONE
    var number = 0
    return starts.mapNotNull { offset ->
        val list = paragraphs.firstOrNull { it.offset == offset }?.list ?: WithinListStyle.NONE
        number = if (list == previous) number + 1 else 1
        previous = list
        val marker = when (list) {
            WithinListStyle.NONE -> return@mapNotNull null
            WithinListStyle.BULLET -> "• "
            WithinListStyle.DECIMAL -> "$number. "
            WithinListStyle.LETTERS -> "${withinLetterNumber(number)}. "
        }
        WithinListMarker(offset, marker)
    }
}

private fun withinLetterNumber(number: Int): String {
    var value = number
    return buildString {
        while (value > 0) {
            value--
            append('a' + value % 26)
            value /= 26
        }
    }.reversed()
}

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
    textDecoration = TextDecoration.combine(buildList {
        if (underline) add(TextDecoration.Underline)
        if (strikethrough) add(TextDecoration.LineThrough)
    }),
    background = if (highlight) Color(0x665E4D17) else Color.Unspecified,
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

private fun withinParagraphStarts(body: String): List<Int> = buildList {
    add(0)
    body.forEachIndexed { index, character -> if (character == '\n') add(index + 1) }
}

internal fun withinParagraphStyleAt(
    body: String, paragraphs: List<WithinParagraphStyle>, index: Int
): WithinParagraphStyle {
    val position = index.coerceIn(0, body.length)
    val offset = if (position == 0) 0 else body.lastIndexOf('\n', position - 1) + 1
    return paragraphs.firstOrNull { it.offset == offset } ?: WithinParagraphStyle(offset)
}

internal fun formatWithinParagraphs(
    body: String, paragraphs: List<WithinParagraphStyle>, start: Int, end: Int,
    change: (WithinParagraphStyle) -> WithinParagraphStyle
): List<WithinParagraphStyle> {
    val first = withinParagraphStyleAt(body, paragraphs, minOf(start, end)).offset
    val selectionEnd = maxOf(start, end).coerceIn(0, body.length)
    val last = withinParagraphStyleAt(body, paragraphs,
        if (start == end) selectionEnd else (selectionEnd - 1).coerceAtLeast(0)).offset
    return withinParagraphStarts(body).mapNotNull { offset ->
        val previous = paragraphs.firstOrNull { it.offset == offset } ?: WithinParagraphStyle(offset)
        val updated = if (offset in first..last) change(previous).copy(offset = offset) else previous
        updated.takeUnless { it.alignment == WithinParagraphAlignment.START && it.list == WithinListStyle.NONE }
    }
}

/** Returns styles for the original text when Enter ends an empty list item without inserting a newline. */
internal fun withinListExitOnEnter(
    before: TextFieldValue, after: TextFieldValue, paragraphs: List<WithinParagraphStyle>,
    images: List<WithinImageAttachment>
): List<WithinParagraphStyle>? {
    if (!before.selection.collapsed || !after.selection.collapsed ||
        before.composition != null || after.composition != null) return null
    val cursor = before.selection.start
    if (withinTextEdit(before, after) != WithinTextEdit(cursor, cursor, cursor + 1) ||
        after.text[cursor] != '\n') return null
    val paragraph = withinParagraphStyleAt(before.text, paragraphs, cursor)
    if (paragraph.list == WithinListStyle.NONE || cursor != paragraph.offset ||
        cursor < before.text.length && before.text[cursor] != '\n' || images.any { it.offset == cursor }) return null
    return formatWithinParagraphs(before.text, paragraphs, cursor, cursor) { it.copy(list = WithinListStyle.NONE) }
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

    fun updateParagraphs(
        oldBody: String, newBody: String, paragraphs: List<WithinParagraphStyle>
    ): List<WithinParagraphStyle> = withinParagraphStarts(newBody).mapNotNull { offset ->
        val source = when {
            // Removing a whole leading paragraph leaves the surviving paragraph's own style.
            offset == start && insertedEnd == start && offset < newBody.length -> removedEnd
            offset <= start -> offset
            offset < insertedEnd -> start
            else -> offset - shift
        }
        withinParagraphStyleAt(oldBody, paragraphs, source).copy(offset = offset).takeUnless {
            it.alignment == WithinParagraphAlignment.START && it.list == WithinListStyle.NONE
        }
    }
}

internal fun withinTextEdit(old: TextFieldValue, new: TextFieldValue): WithinTextEdit? {
    if (old.text == new.text) return null
    anchoredWithinEdit(old.text, new.text, old.selection.min, old.selection.max)?.let { return it }
    val lengthDelta = new.text.length - old.text.length
    if (old.selection.collapsed && new.selection.collapsed) {
        val start = minOf(old.selection.start, new.selection.start)
        val removedEnd = new.selection.start - lengthDelta
        anchoredWithinEdit(old.text, new.text, start, removedEnd)?.let { return it }
    }
    // Candidate updates can replace the whole composing range while changing only its middle.
    // Keep unchanged text outside that change attached to its existing styles and images.
    val window = old.composition?.let { anchoredWithinEdit(old.text, new.text, it.min, it.max) }
        ?: WithinTextEdit(0, old.text.length, new.text.length)
    val before = old.text.substring(window.start, window.removedEnd)
    val after = new.text.substring(window.start, window.insertedEnd)
    val left = before.commonPrefixWith(after).length
    val right = before.substring(left).commonSuffixWith(after.substring(left)).length
    return WithinTextEdit(window.start + left, window.removedEnd - right, window.insertedEnd - right)
}

private fun anchoredWithinEdit(old: String, new: String, start: Int, removedEnd: Int): WithinTextEdit? {
    val insertedEnd = removedEnd + new.length - old.length
    return if (start in 0..old.length && removedEnd in start..old.length && insertedEnd in start..new.length &&
        old.regionMatches(0, new, 0, start) && old.regionMatches(removedEnd, new, insertedEnd, old.length - removedEnd)) {
        WithinTextEdit(start, removedEnd, insertedEnd)
    } else null
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
