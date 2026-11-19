package io.github.githuiy.starlive.ui

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import io.github.githuiy.starlive.data.WithinImageAttachment
import io.github.githuiy.starlive.data.WithinListStyle
import io.github.githuiy.starlive.data.WithinParagraphAlignment
import io.github.githuiy.starlive.data.WithinParagraphStyle
import io.github.githuiy.starlive.data.WithinTextSpan
import io.github.githuiy.starlive.data.WithinTextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WithinRichTextTest {
    @Test fun selectionAppliesToTouchedParagraphsAndCaretCanStyleTheEmptyFinalParagraph() {
        val body = "第一段\n第二段\n"
        val first = formatWithinParagraphs(body, emptyList(), 0, 4) {
            it.copy(alignment = WithinParagraphAlignment.CENTER, list = WithinListStyle.DECIMAL)
        }
        assertEquals(listOf(WithinParagraphStyle(0, WithinParagraphAlignment.CENTER, WithinListStyle.DECIMAL)), first)
        val final = formatWithinParagraphs(body, first, body.length, body.length) {
            it.copy(list = WithinListStyle.LETTERS)
        }
        assertEquals(WithinParagraphStyle(8, list = WithinListStyle.LETTERS), final.last())
        val cleared = formatWithinParagraphs(body, final, 0, 4) { it.copy(alignment = WithinParagraphAlignment.START,
            list = WithinListStyle.NONE) }
        assertEquals(listOf(final.last()), cleared)
    }

    @Test fun splitInheritsStyleAndJoinKeepsTheLeadingParagraphWithoutChangingLaterOnes() {
        val body = "甲乙\n丙丁\n戊己"
        val paragraphs = listOf(
            WithinParagraphStyle(0, WithinParagraphAlignment.CENTER, WithinListStyle.DECIMAL),
            WithinParagraphStyle(3, WithinParagraphAlignment.END, WithinListStyle.BULLET),
            WithinParagraphStyle(6, list = WithinListStyle.LETTERS)
        )
        val split = WithinTextEdit(1, 1, 2).updateParagraphs(body, "甲\n乙\n丙丁\n戊己", paragraphs)
        assertEquals(listOf(paragraphs[0], paragraphs[0].copy(offset = 2), paragraphs[1].copy(offset = 4),
            paragraphs[2].copy(offset = 7)), split)
        val joined = WithinTextEdit(2, 3, 2).updateParagraphs(body, "甲乙丙丁\n戊己", paragraphs)
        assertEquals(listOf(paragraphs[0], paragraphs[2].copy(offset = 5)), joined)
        val replaced = WithinTextEdit(3, 6, 5).updateParagraphs(body, "甲乙\n新\n戊己", paragraphs)
        assertEquals(listOf(paragraphs[0], paragraphs[1], paragraphs[2].copy(offset = 5)), replaced)
        val removed = WithinTextEdit(3, 6, 3).updateParagraphs(body, "甲乙\n戊己", paragraphs)
        assertEquals(listOf(paragraphs[0], paragraphs[2].copy(offset = 3)), removed)
    }

    @Test fun listVisualOffsetsRoundTripEveryUtf16BoundaryIncludingEmojiAndTrailingEmptyLine() {
        val body = "星🌟\n二\n"
        val paragraphs = listOf(WithinParagraphStyle(0, list = WithinListStyle.DECIMAL),
            WithinParagraphStyle(4, WithinParagraphAlignment.CENTER, WithinListStyle.DECIMAL),
            WithinParagraphStyle(6, WithinParagraphAlignment.END, WithinListStyle.LETTERS))
        val rendered = withinEditorText(body, emptyList(), paragraphs)
        assertEquals("1. 星🌟\n2. 二\na. ", rendered.text.text)
        for (offset in 0..body.length) {
            assertEquals(offset, rendered.offsetMapping.transformedToOriginal(
                rendered.offsetMapping.originalToTransformed(offset)))
        }
        val positions = (0..rendered.text.length).map(rendered.offsetMapping::transformedToOriginal)
        assertTrue(positions.zipWithNext().all { (first, second) -> first <= second })
        assertTrue(positions.all { it in 0..body.length })
        assertEquals(TextAlign.End, rendered.text.paragraphStyles.last().item.textAlign)
    }

    @Test fun readerSlicesKeepAlignmentAndNumberingAcrossInlineImagesWithoutRepeatingMarkers() {
        val body = "前文后文\n第二段"
        val paragraphs = listOf(WithinParagraphStyle(0, WithinParagraphAlignment.END, WithinListStyle.DECIMAL),
            WithinParagraphStyle(5, list = WithinListStyle.DECIMAL))
        val beforeImage = withinStyledText(body, emptyList(), 0, 2, paragraphs, listMarkers = true)
        val afterImage = withinStyledText(body, emptyList(), 2, body.length, paragraphs, listMarkers = true)
        assertEquals("1. 前文", beforeImage.text)
        assertEquals("后文\n2. 第二段", afterImage.text)
        assertEquals("1. 前文后文\n", withinStyledText(body, emptyList(), 0, 5, paragraphs,
            listMarkers = true).text)
        assertEquals(TextAlign.End, afterImage.paragraphStyles.first().item.textAlign)
        assertEquals("第二段", withinStyledText(body, emptyList(), 5, body.length).text)
        assertEquals("2. 第二段", withinStyledText(body, emptyList(), 5, body.length, paragraphs,
            listMarkers = true).text)
    }

    @Test fun decimalAndLetterListsResetAtOtherStylesAndLetterNumbersContinueBeyondZ() {
        val body = List(28) { "段" }.joinToString("\n")
        val letters = List(28) { WithinParagraphStyle(it * 2, list = WithinListStyle.LETTERS) }
        val rendered = withinStyledText(body, emptyList(), paragraphs = letters, listMarkers = true).text
        assertTrue(rendered.startsWith("a. 段\nb. 段"))
        assertTrue(rendered.endsWith("z. 段\naa. 段\nab. 段"))
        val mixed = letters.take(4).mapIndexed { index, style ->
            style.copy(list = if (index == 1) WithinListStyle.BULLET else WithinListStyle.DECIMAL)
        }
        assertEquals("1. 段\n• 段\n1. 段\n2. 段", withinStyledText("段\n段\n段\n段", emptyList(),
            paragraphs = mixed, listMarkers = true).text)
    }

    @Test fun newTextEffectsComposeWithUnderlineAndSurviveLegacyAndCurrentSavedState() {
        val style = WithinTextStyle(underline = true, strikethrough = true, highlight = true)
        val spans = listOf(WithinTextSpan(0, 1, style))
        val effect = withinStyledText("文", spans).spanStyles.single().item
        assertTrue(effect.textDecoration!!.contains(TextDecoration.Underline))
        assertTrue(effect.textDecoration!!.contains(TextDecoration.LineThrough))
        assertTrue(effect.background != Color.Unspecified)
        val scope = SaverScope { true }
        val saved = with(withinSpanSaver) { scope.save(spans) }!!
        assertEquals(spans, withinSpanSaver.restore(saved))
        val legacy = withinSpanSaver.restore(listOf(0, 1, "", 0, 0, true, false, false))!!.single().style
        assertTrue(legacy.bold)
        assertFalse(legacy.strikethrough)
        assertFalse(legacy.highlight)
    }

    @Test fun textEditsStillPreserveUnrelatedCharacterStylesAndImageAnchors() {
        val body = "一🌟二\n尾"
        val old = TextFieldValue(body, TextRange(1))
        val new = TextFieldValue("一\n🌟二\n尾", TextRange(2))
        val edit = withinTextEdit(old, new)!!
        val style = WithinTextStyle(strikethrough = true, highlight = true)
        val spans = edit.updateSpans(listOf(WithinTextSpan(1, 4, style)), WithinTextStyle())
        assertEquals(listOf(WithinTextSpan(2, 5, style)), spans)
        val image = WithinImageAttachment("sample", "图片", "image/png", offset = 4)
        assertEquals(5, edit.updateImages(listOf(image)).single().offset)
    }

    @Test fun composingBackspacePreservesUnchangedStylesAndInlineImagePositions() {
        val old = TextFieldValue("abc", TextRange(3), TextRange(0, 3))
        val new = TextFieldValue("ab", TextRange(2), TextRange(0, 2))
        val edit = withinTextEdit(old, new)!!
        assertEquals(WithinTextEdit(2, 3, 2), edit)
        val styles = listOf(WithinTextSpan(0, 1, WithinTextStyle(bold = true)),
            WithinTextSpan(1, 2, WithinTextStyle(italic = true)))
        assertEquals(styles, edit.updateSpans(styles, WithinTextStyle()))
        val image = WithinImageAttachment("inline", "图片", "image/png", offset = 1)
        assertEquals(image, edit.updateImages(listOf(image)).single())
    }

    @Test fun imeCandidateCorrectionsTrimUnchangedTextInsideTheComposition() {
        val old = TextFieldValue("abcd", TextRange(4), TextRange(0, 4))
        val new = TextFieldValue("abXd", TextRange(3), TextRange(0, 4))
        assertEquals(WithinTextEdit(2, 3, 3), withinTextEdit(old, new))
        val candidate = TextFieldValue("拼音", TextRange(2))
        assertEquals(WithinTextEdit(0, 4, 2), withinTextEdit(old, candidate))
    }

    @Test fun repeatedCharacterDeletionUsesTheCaretForBothBackspaceAndForwardDelete() {
        val old = TextFieldValue("aaaa", TextRange(2))
        val backward = withinTextEdit(old, TextFieldValue("aaa", TextRange(1)))!!
        val forward = withinTextEdit(old, TextFieldValue("aaa", TextRange(2)))!!
        assertEquals(WithinTextEdit(1, 2, 1), backward)
        assertEquals(WithinTextEdit(2, 3, 2), forward)
        val image = WithinImageAttachment("inline", "图片", "image/png", offset = 2)
        assertEquals(1, backward.updateImages(listOf(image)).single().offset)
        assertEquals(2, forward.updateImages(listOf(image)).single().offset)
    }

    @Test fun repeatedTextInsertionAndExplicitReplacementRetainSelectionAnchors() {
        val old = TextFieldValue("aaaa", TextRange(1), TextRange(0, 4))
        assertEquals(WithinTextEdit(1, 1, 2), withinTextEdit(old,
            TextFieldValue("aaaaa", TextRange(2), TextRange(0, 5))))
        val selection = TextFieldValue("aaaa", TextRange(3, 1), TextRange(0, 4))
        assertEquals(WithinTextEdit(1, 3, 3), withinTextEdit(selection, TextFieldValue("abaa", TextRange(3))))
    }

    @Test fun enterOnAnEmptyListedParagraphClearsOnlyItsListAndRetainsAlignment() {
        val body = "前段\n\n后段"
        val cursor = 3
        val before = TextFieldValue(body, TextRange(cursor))
        val after = TextFieldValue("前段\n\n\n后段", TextRange(cursor + 1))
        val first = WithinParagraphStyle(0, list = WithinListStyle.DECIMAL)
        val empty = WithinParagraphStyle(cursor, WithinParagraphAlignment.CENTER, WithinListStyle.BULLET)
        val last = WithinParagraphStyle(4, WithinParagraphAlignment.END, WithinListStyle.LETTERS)
        assertEquals(listOf(first, empty.copy(list = WithinListStyle.NONE), last),
            withinListExitOnEnter(before, after, listOf(first, empty, last), emptyList()))
    }

    @Test fun enterOnAnEmptyFinalItemExitsEveryListTypeWithoutAStoredDefaultParagraph() {
        val before = TextFieldValue("前段\n", TextRange(3))
        val after = TextFieldValue("前段\n\n", TextRange(4))
        val first = WithinParagraphStyle(0, list = WithinListStyle.BULLET)
        for (list in listOf(WithinListStyle.BULLET, WithinListStyle.DECIMAL, WithinListStyle.LETTERS)) {
            assertEquals(listOf(first), withinListExitOnEnter(before, after,
                listOf(first, WithinParagraphStyle(3, list = list)), emptyList()))
        }
        assertEquals(emptyList<WithinParagraphStyle>(), withinListExitOnEnter(TextFieldValue("", TextRange(0)),
            TextFieldValue("\n", TextRange(1)), listOf(WithinParagraphStyle(0, list = WithinListStyle.BULLET)), emptyList()))
    }

    @Test fun listExitIgnoresTextImagesPasteCompositionAndSelectionReplacement() {
        val bullet = listOf(WithinParagraphStyle(0, list = WithinListStyle.BULLET))
        val empty = TextFieldValue("", TextRange(0))
        val newline = TextFieldValue("\n", TextRange(1))
        val image = WithinImageAttachment("inline", "图片", "image/png", 0)
        assertNull(withinListExitOnEnter(empty, newline, bullet, listOf(image)))
        assertNull(withinListExitOnEnter(empty, newline, emptyList(), emptyList()))
        assertNull(withinListExitOnEnter(TextFieldValue("字", TextRange(1)), TextFieldValue("字\n", TextRange(2)),
            bullet, emptyList()))
        assertNull(withinListExitOnEnter(TextFieldValue(" ", TextRange(1)), TextFieldValue(" \n", TextRange(2)),
            bullet, emptyList()))
        assertNull(withinListExitOnEnter(empty, TextFieldValue("\n\n", TextRange(2)), bullet, emptyList()))
        assertNull(withinListExitOnEnter(TextFieldValue("字", TextRange(0, 1)), newline, bullet, emptyList()))
        assertNull(withinListExitOnEnter(TextFieldValue("", TextRange(0), TextRange(0)), newline, bullet, emptyList()))
        assertNull(withinListExitOnEnter(empty, TextFieldValue("\n", TextRange(1), TextRange(0, 1)), bullet, emptyList()))
    }

    @Test fun enterContinuesANonemptyBulletAndBackspaceRejoinsItsPlainSuccessor() {
        val original = TextFieldValue("项目", TextRange(2))
        val continued = TextFieldValue("项目\n", TextRange(3))
        val bullet = WithinParagraphStyle(0, list = WithinListStyle.BULLET)
        val paragraphs = listOf(bullet)
        assertNull(withinListExitOnEnter(original, continued, paragraphs, emptyList()))
        val continuation = withinTextEdit(original, continued)!!.updateParagraphs(original.text, continued.text, paragraphs)
        assertEquals(listOf(bullet, bullet.copy(offset = 3)), continuation)
        val plain = withinListExitOnEnter(continued, TextFieldValue("项目\n\n", TextRange(4)),
            continuation, emptyList())!!
        assertEquals(listOf(bullet), plain)
        val joined = withinTextEdit(continued, original)!!.updateParagraphs(continued.text, original.text, plain)
        assertEquals(listOf(bullet), joined)
    }
}
