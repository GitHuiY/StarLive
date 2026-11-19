package io.github.githuiy.starlive.ui

import androidx.compose.ui.text.input.TextFieldValue
import io.github.githuiy.starlive.data.WithinImageAttachment
import io.github.githuiy.starlive.data.WithinParagraphStyle
import io.github.githuiy.starlive.data.WithinTextSpan
import io.github.githuiy.starlive.data.WithinTextStyle

internal data class WithinEditSnapshot(
    val body: TextFieldValue,
    val spans: List<WithinTextSpan> = emptyList(),
    val paragraphs: List<WithinParagraphStyle> = emptyList(),
    val images: List<WithinImageAttachment> = emptyList(),
    val pendingStyle: WithinTextStyle = WithinTextStyle()
)

internal enum class WithinEditKind { TEXT, ACTION }

/** Immutable writer history. The current document is retained even when it exceeds the history budget. */
internal class WithinEditHistory private constructor(
    val current: WithinEditSnapshot,
    private val past: List<WithinEditSnapshot>,
    private val future: List<WithinEditSnapshot>,
    private val typing: TypingGroup?,
    private val maxEntries: Int,
    private val maxBytes: Int,
) {
    constructor(initial: WithinEditSnapshot, maxEntries: Int = 100, maxBytes: Int = 4 * 1024 * 1024) :
        this(initial.frozen(), emptyList(), emptyList(), null, maxEntries, maxBytes) {
        require(maxEntries > 0) { "History must retain at least its current snapshot" }
        require(maxBytes > 0) { "History byte budget must be positive" }
    }

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    /** Image bytes live outside snapshots. Keep these immutable copies until their last history reference expires. */
    val retainedImages: List<WithinImageAttachment> = buildList {
        val ids = mutableSetOf<String>()
        for (snapshot in past + current + future) {
            for (image in snapshot.images) if (ids.add(image.id)) add(image)
        }
    }

    fun record(
        next: WithinEditSnapshot,
        kind: WithinEditKind = WithinEditKind.ACTION,
        nowMillis: Long = System.nanoTime() / 1_000_000,
    ): WithinEditHistory {
        val snapshot = next.frozen()
        if (current.sameContent(snapshot)) {
            // Cursor moves do not create an undo step or destroy redo. They do separate typing operations.
            val continuingInput = kind == WithinEditKind.TEXT && current.body.selection == snapshot.body.selection &&
                (current.body.composition != null) == (snapshot.body.composition != null)
            return create(snapshot, past, future, typing.takeIf { continuingInput })
        }
        val mode = if (kind == WithinEditKind.TEXT) typingMode(current.body, snapshot.body) else null
        val coalesce = mode != null && typing != null && typing.mode == mode && when (mode) {
            TypingMode.COMPOSITION -> current.body.composition != null
            else -> nowMillis - typing.atMillis in 0..1_000
        }
        val previous = if (coalesce) past else past + current.withoutComposition()
        return create(snapshot, previous, emptyList(), mode?.let { TypingGroup(it, nowMillis) })
    }

    fun undo(): WithinEditHistory {
        if (!canUndo) return this
        return create(past.last().withoutComposition(), past.dropLast(1), future + current.withoutComposition(), null)
    }

    fun redo(): WithinEditHistory {
        if (!canRedo) return this
        return create(future.last().withoutComposition(), past + current.withoutComposition(), future.dropLast(1), null)
    }

    private fun create(
        snapshot: WithinEditSnapshot,
        previous: List<WithinEditSnapshot>,
        following: List<WithinEditSnapshot>,
        group: TypingGroup?,
    ): WithinEditHistory {
        val keptPast = previous.toMutableList()
        val keptFuture = following.toMutableList()
        var size = snapshot.estimatedBytes() + previous.sumOf { it.estimatedBytes() } + following.sumOf { it.estimatedBytes() }
        while (keptPast.size + keptFuture.size + 1 > maxEntries || size > maxBytes) {
            // Discard the farthest reachable snapshots first. A large current document remains editable.
            val removed = when {
                keptPast.isNotEmpty() -> keptPast.removeAt(0)
                keptFuture.isNotEmpty() -> keptFuture.removeAt(0)
                else -> break
            }
            size -= removed.estimatedBytes()
        }
        return WithinEditHistory(snapshot, keptPast, keptFuture, group, maxEntries, maxBytes)
    }
}

private enum class TypingMode { INSERT, BACKSPACE, DELETE, COMPOSITION }
private data class TypingGroup(val mode: TypingMode, val atMillis: Long)

private fun typingMode(before: TextFieldValue, after: TextFieldValue): TypingMode? {
    if (before.composition != null || after.composition != null) return TypingMode.COMPOSITION
    if (!before.selection.collapsed || !after.selection.collapsed) return null
    val edit = withinTextEdit(before, after) ?: return null
    val inserted = after.text.substring(edit.start, edit.insertedEnd)
    val removed = before.text.substring(edit.start, edit.removedEnd)
    return when {
        removed.isEmpty() && inserted.isSingleTypedCharacter() &&
            before.selection.start == edit.start && after.selection.start == edit.insertedEnd -> TypingMode.INSERT
        inserted.isEmpty() && removed.isSingleTypedCharacter() && before.selection.start == edit.removedEnd &&
            after.selection.start == edit.start -> TypingMode.BACKSPACE
        inserted.isEmpty() && removed.isSingleTypedCharacter() && before.selection.start == edit.start &&
            after.selection.start == edit.start -> TypingMode.DELETE
        else -> null
    }
}

private fun String.isSingleTypedCharacter() = isNotEmpty() && '\n' !in this && '\r' !in this &&
    codePointCount(0, length) == 1

private fun WithinEditSnapshot.sameContent(other: WithinEditSnapshot) =
    body.annotatedString == other.body.annotatedString && spans == other.spans && paragraphs == other.paragraphs &&
        images == other.images && pendingStyle == other.pendingStyle

private fun WithinEditSnapshot.withoutComposition() = copy(body = body.copy(composition = null))

private fun WithinEditSnapshot.frozen() = copy(spans = spans.toList(), paragraphs = paragraphs.toList(), images = images.toList())

/** Conservative payload estimate includes text and metadata, with shared values counted per snapshot. */
private fun WithinEditSnapshot.estimatedBytes(): Long = 128L + body.text.length * 2L + spans.size * 96L +
    paragraphs.size * 40L + images.sumOf { 64L + (it.id.length + it.name.length + it.mimeType.length) * 2L }
