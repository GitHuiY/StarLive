package io.github.githuiy.starlive.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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

class WithinEditHistoryTest {
    @Test fun undoAndRedoRestoreTextSelectionAndAllFormattingAsOneOperation() {
        val original = snapshot("前文\n后文", TextRange(0, 2))
        val style = WithinTextStyle(bold = true, strikethrough = true, highlight = true)
        val formatted = original.copy(
            spans = listOf(WithinTextSpan(0, 2, style)),
            paragraphs = listOf(WithinParagraphStyle(0, WithinParagraphAlignment.CENTER, WithinListStyle.DECIMAL)),
            images = listOf(image("photo", 2)), pendingStyle = style,
        )
        val history = WithinEditHistory(original).record(formatted)
        assertTrue(history.canUndo)
        assertEquals(original, history.undo().current)
        assertEquals(formatted, history.undo().redo().current)
        assertFalse(history.undo().canUndo)
    }

    @Test fun nearbyContiguousTypingGroupsButPausesAndNewlinesCreateNewSteps() {
        var history = WithinEditHistory(snapshot(""))
        history = history.record(snapshot("a"), WithinEditKind.TEXT, 0)
            .record(snapshot("ab"), WithinEditKind.TEXT, 300)
            .record(snapshot("abc"), WithinEditKind.TEXT, 600)
        assertEquals("", history.undo().current.body.text)
        assertEquals("abc", history.undo().redo().current.body.text)
        history = history.record(snapshot("abcd"), WithinEditKind.TEXT, 2_000)
        assertEquals("abc", history.undo().current.body.text)
        history = history.record(snapshot("abcd\n"), WithinEditKind.TEXT, 2_100)
            .record(snapshot("abcd\ne"), WithinEditKind.TEXT, 2_200)
        assertEquals("abcd\n", history.undo().current.body.text)
        assertEquals("abcd", history.undo().undo().current.body.text)
    }

    @Test fun backspaceAndForwardDeleteGroupIndependentlyAndPasteDoesNotJoinTyping() {
        val backward = WithinEditHistory(snapshot("abc"))
            .record(snapshot("ab"), WithinEditKind.TEXT, 0)
            .record(snapshot("a"), WithinEditKind.TEXT, 100)
        assertEquals("abc", backward.undo().current.body.text)
        val forward = WithinEditHistory(snapshot("abc", TextRange.Zero))
            .record(snapshot("bc", TextRange.Zero), WithinEditKind.TEXT, 0)
            .record(snapshot("c", TextRange.Zero), WithinEditKind.TEXT, 100)
        assertEquals("abc", forward.undo().current.body.text)
        val pasted = WithinEditHistory(snapshot(""))
            .record(snapshot("a"), WithinEditKind.TEXT, 0)
            .record(snapshot("a粘贴"), WithinEditKind.TEXT, 100)
            .record(snapshot("a粘贴b"), WithinEditKind.TEXT, 200)
        assertEquals("a粘贴", pasted.undo().current.body.text)
        assertEquals("a", pasted.undo().undo().current.body.text)
    }

    @Test fun compositionOnlyUpdatesAndFinalCommitStayOneUndoTransactionEvenAfterLongPause() {
        val initial = snapshot("旧")
        val starting = initial.copy(body = initial.body.copy(composition = TextRange(0, 1)))
        var history = WithinEditHistory(initial).record(starting, WithinEditKind.TEXT, 0)
        assertFalse(history.canUndo)
        history = history.record(snapshot("j", composition = TextRange(0, 1)), WithinEditKind.TEXT, 100)
            .record(snapshot("ji", composition = TextRange(0, 2)), WithinEditKind.TEXT, 5_000)
            .record(snapshot("ji", composition = TextRange(0, 1)), WithinEditKind.TEXT, 6_000)
            .record(snapshot("吉"), WithinEditKind.TEXT, 7_000)
        assertEquals(initial, history.undo().current)
        assertEquals("吉", history.undo().redo().current.body.text)
        assertNull(history.undo().redo().current.body.composition)

        val nextComposition = history.current.copy(body = history.current.body.copy(composition = TextRange(0, 1)))
        history = history.record(nextComposition, WithinEditKind.TEXT, 7_100)
            .record(snapshot("祥", composition = TextRange(0, 1)), WithinEditKind.TEXT, 7_200)
            .record(snapshot("祥"), WithinEditKind.TEXT, 7_300)
        assertEquals("吉", history.undo().current.body.text)
        assertEquals("旧", history.undo().undo().current.body.text)
    }

    @Test fun cursorChangesAndToolActionsSeparateTypingWhileSelectionAloneKeepsRedo() {
        val moved = WithinEditHistory(snapshot(""))
            .record(snapshot("a"), WithinEditKind.TEXT, 0)
            .record(snapshot("a", TextRange.Zero), WithinEditKind.TEXT, 100)
            .record(snapshot("xa", TextRange(1)), WithinEditKind.TEXT, 200)
        assertEquals(snapshot("a", TextRange.Zero), moved.undo().current)
        val movedAfterUndo = moved.undo().record(snapshot("a", TextRange(1)), WithinEditKind.TEXT, 300)
        assertTrue(movedAfterUndo.canRedo)
        assertEquals("xa", movedAfterUndo.redo().current.body.text)

        val action = WithinEditHistory(snapshot(""))
            .record(snapshot("a"), WithinEditKind.TEXT, 0)
            .record(snapshot("a"), WithinEditKind.ACTION, 100)
            .record(snapshot("ab"), WithinEditKind.TEXT, 200)
        assertEquals("a", action.undo().current.body.text)
        assertEquals("", action.undo().undo().current.body.text)
    }

    @Test fun attachmentReferencesStayReachableForUndoRedoAndReleaseWhenBranchOrHistoryExpires() {
        val original = snapshot("正文")
        val first = image("first", 0)
        val second = image("second", 2)
        val withFirst = original.copy(images = listOf(first))
        val withBoth = original.copy(images = listOf(first, second))
        var history = WithinEditHistory(original).record(withFirst).record(withBoth).undo()
        assertEquals(setOf("first", "second"), history.retainedImages.map { it.id }.toSet())
        assertEquals(withBoth, history.redo().current)
        history = history.record(withFirst.copy(pendingStyle = WithinTextStyle(italic = true)))
        assertFalse(history.canRedo)
        assertEquals(listOf(first), history.retainedImages)

        val evicted = WithinEditHistory(original, maxEntries = 2)
            .record(withFirst).record(original)
            .record(original.copy(pendingStyle = WithinTextStyle(bold = true)))
        assertTrue(evicted.retainedImages.isEmpty())
        assertEquals(original, evicted.undo().current)
    }

    @Test fun snapshotCountAndEstimatedMemoryAreBoundedWithoutTruncatingCurrentDocument() {
        var limited = WithinEditHistory(snapshot("0"), maxEntries = 3)
        for (index in 1..4) limited = limited.record(snapshot(index.toString()))
        assertEquals("3", limited.undo().current.body.text)
        assertEquals("2", limited.undo().undo().current.body.text)
        assertFalse(limited.undo().undo().canUndo)

        val byteLimited = WithinEditHistory(snapshot("0"), maxBytes = 300)
            .record(snapshot("1")).record(snapshot("2"))
        assertEquals("1", byteLimited.undo().current.body.text)
        assertFalse(byteLimited.undo().canUndo)
        val large = byteLimited.record(snapshot("长".repeat(1_000)))
        assertEquals(1_000, large.current.body.text.length)
        assertFalse(large.canUndo)
        assertFalse(large.canRedo)
    }

    @Test fun snapshotsCopyCallerListsAndUndoClearsActiveComposition() {
        val images = mutableListOf(image("shared", 0))
        val initial = snapshot("原文").copy(images = images)
        val history = WithinEditHistory(initial)
        images.clear()
        assertEquals(1, history.current.images.size)
        val composing = history.record(snapshot("新文", composition = TextRange(0, 2)), WithinEditKind.TEXT, 0)
        assertNull(composing.undo().current.body.composition)
        assertNull(composing.undo().redo().current.body.composition)
    }

    private fun snapshot(text: String, selection: TextRange = TextRange(text.length), composition: TextRange? = null) =
        WithinEditSnapshot(TextFieldValue(text, selection, composition))

    private fun image(id: String, offset: Int) = WithinImageAttachment(id, "图片", "image/png", offset)
}
