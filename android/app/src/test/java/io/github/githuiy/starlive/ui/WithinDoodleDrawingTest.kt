package io.github.githuiy.starlive.ui

import android.content.Context
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import io.github.githuiy.starlive.FileProviderPathShadow
import io.github.githuiy.starlive.data.WithinRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [FileProviderPathShadow::class], instrumentedPackages = ["androidx.core.content"])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WithinDoodleDrawingTest {
    private val viewport = IntSize(300, 600)
    private val stroke = WithinDoodleStroke(listOf(Offset(.2f, .5f), Offset(.8f, .5f)), erasing = false)

    @Test fun blankOrErasedDrawingProducesNoImageAndUndoRecoversInk() {
        assertNull(renderWithinDoodle(emptyList(), viewport))
        val eraser = stroke.copy(erasing = true)
        assertNull(renderWithinDoodle(listOf(eraser), viewport))
        val history = listOf(stroke, eraser)
        assertNull(renderWithinDoodle(history, viewport))

        val restored = checkNotNull(renderWithinDoodle(history.dropLast(1), viewport))
        try {
            assertEquals(WithinDoodleInk, restored.getPixel(restored.width / 2, restored.height / 2))
            assertEquals(Color.WHITE, restored.getPixel(0, 0))
            assertEquals(2, restored.height / restored.width)
        } finally {
            restored.recycle()
        }
    }

    @Test fun eraserRemovesOnlyTouchedInkAndSingleTapsRemainVisible() {
        val eraser = WithinDoodleStroke(listOf(Offset(.5f, .5f)), erasing = true)
        val bitmap = checkNotNull(renderWithinDoodle(listOf(stroke, eraser), viewport))
        try {
            assertEquals(Color.WHITE, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
            assertEquals(WithinDoodleInk, bitmap.getPixel(bitmap.width / 4, bitmap.height / 2))
        } finally {
            bitmap.recycle()
        }
        val dot = renderWithinDoodle(listOf(WithinDoodleStroke(listOf(Offset(.5f, .5f)), erasing = false)), viewport)
        assertNotNull(dot)
        dot?.recycle()
    }

    @Test fun generatedUriSurvivesImportAndCleanupOnlyDeletesItsTemporaryCopy() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = WithinRepository(context)
        val bitmap = checkNotNull(renderWithinDoodle(listOf(stroke), viewport))
        val uri = try { writeWithinDoodleImage(context, bitmap) } finally { bitmap.recycle() }
        assertEquals("${context.packageName}.within.images", uri.authority)
        assertTrue(checkNotNull(context.contentResolver.openInputStream(uri)).use { it.read() != -1 })
        assertThrows(SecurityException::class.java) {
            context.contentResolver.openInputStream(Uri.parse("content://${uri.authority}/within/..%2Foutside.png"))
        }

        val attachment = repository.importImage(uri, 4)
        try {
            discardWithinTemporaryImage(context, uri)
            assertFalse(File(context.cacheDir, "within-images/${uri.lastPathSegment}").exists())
            assertTrue(repository.readImage(attachment).isNotEmpty())
            assertEquals(4, attachment.offset)
            assertEquals("image/png", attachment.mimeType)

            val unrelated = File(context.cacheDir, "unrelated-image.png").apply { writeText("keep") }
            try {
                discardWithinTemporaryImage(context, Uri.fromFile(unrelated))
                discardWithinTemporaryImage(context, Uri.parse("content://another.app/within/doodle-file.png"))
                assertTrue(unrelated.exists())
            } finally {
                unrelated.delete()
            }
        } finally {
            discardWithinTemporaryImage(context, uri)
            repository.discardUnreferencedImages(listOf(attachment))
        }
    }
}
