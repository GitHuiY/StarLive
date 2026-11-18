package io.github.githuiy.starlive.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.githuiy.starlive.PosixAtomicFileShadow
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [PosixAtomicFileShadow::class])
class WithinRepositoryTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()
    private val olderDate = LocalDate.of(2026, 11, 16)
    private val newerDate = LocalDate.of(2026, 11, 17)
    private val profileFile: File get() = File(app.filesDir, "starlive-within-v1.json")
    private val essayFile: File get() = File(app.filesDir, "starlive-within-v2.json")

    @Test fun createEditAndDeleteSurviveReopeningWithoutChangingPlans() = runBlocking {
        val planFile = File(app.filesDir, PlanSlot.PRIMARY.fileName)
        planFile.writeText("existing plan data")
        val repository = WithinRepository(app)
        assertTrue(repository.load().isEmpty())
        val draft = WithinEssayDraft(
            "测试记录\n  第二行\n", newerDate, setOf(WithinCategory.SELF, WithinCategory.WORLD)
        )
        val created = repository.save(null, draft).single()
        val reopened = WithinRepository(app)
        assertEquals(created, reopened.load().single())
        assertEquals(draft, created.draft)

        val uncategorizedDraft = WithinEssayDraft("另一篇随笔", newerDate)
        val twoEssays = reopened.save(null, uncategorizedDraft)
        val other = twoEssays.first()
        assertEquals(uncategorizedDraft, other.draft)
        assertEquals(listOf(other, created), twoEssays)
        val edited = WithinEssayDraft("更新测试记录", olderDate, setOf(WithinCategory.BEYOND))
        reopened.save(created.id, edited)
        assertEquals(listOf(other, created.copy(draft = edited)), WithinRepository(app).load())

        val bumpedDate = edited.copy(date = newerDate.plusDays(1))
        assertEquals(listOf(created.copy(draft = bumpedDate), other), reopened.save(created.id, bumpedDate))
        val matchingDate = edited.copy(date = newerDate)
        assertEquals(listOf(other, created.copy(draft = matchingDate)), reopened.save(created.id, matchingDate))

        reopened.delete(created.id)
        assertEquals(listOf(other), WithinRepository(app).load())
        assertEquals("existing plan data", planFile.readText())
    }

    @Test fun overlappingSavesFromTwoInstancesKeepBothRecords() = runBlocking {
        val first = async {
            WithinRepository(app).save(null, WithinEssayDraft("第一篇", newerDate, setOf(WithinCategory.MOMENT)))
        }
        val second = async {
            WithinRepository(app).save(null, WithinEssayDraft("第二篇", newerDate, setOf(WithinCategory.ECHO)))
        }
        awaitAll(first, second)
        val records = WithinRepository(app).load()
        assertEquals(2, records.size)
        assertEquals(2, records.map { it.id }.toSet().size)
        assertEquals(setOf(WithinCategory.MOMENT, WithinCategory.ECHO), records.flatMap { it.draft.categories }.toSet())
    }

    @Test fun originalProfileIsReadableAndSurvivesTheFirstEssayWrite() = runBlocking {
        val original = """
            {"version":1,"records":[
                {"id":"old-undated","domain":"VALUES","type":"EXPLORATION","description":"保留原想法"},
                {"id":"old-dated","domain":"ABILITY","type":"STRENGTH","description":"保留原说明\n下一行",
                "example":"保留原例子","source":"FEEDBACK","recordedOn":"2026-11-16"}
            ]}
        """.trimIndent().toByteArray()
        profileFile.writeBytes(original)
        val repository = WithinRepository(app)
        val (dated, undated) = repository.load()
        assertEquals(WithinEssay(
            "old-dated", WithinEssayDraft("保留原说明\n下一行", olderDate),
            WithinLegacy(WithinDomain.ABILITY, WithinRecordType.STRENGTH, "保留原例子", WithinSource.FEEDBACK)
        ), dated)
        assertEquals(WithinEssay(
            "old-undated", WithinEssayDraft("保留原想法", null),
            WithinLegacy(WithinDomain.VALUES, WithinRecordType.EXPLORATION)
        ), undated)
        assertFalse(essayFile.exists())
        assertArrayEquals(original, profileFile.readBytes())

        val edited = undated.draft.copy(body = "保留原想法及新一行", categories = setOf(WithinCategory.BEYOND))
        repository.save(undated.id, edited)
        val reopened = WithinRepository(app)
        assertEquals(listOf(dated, undated.copy(draft = edited)), reopened.load())
        reopened.delete(dated.id)
        reopened.delete(undated.id)
        assertTrue(WithinRepository(app).load().isEmpty())
        assertArrayEquals(original, profileFile.readBytes())
    }

    @Test fun unreadableProfilePreventsCreatingAnEssayFile() = runBlocking {
        val unsupported = """{"version":99,"records":[]}"""
        profileFile.writeText(unsupported)
        val attempted = runCatching {
            WithinRepository(app).save(null, WithinEssayDraft("新随笔", newerDate))
        }
        assertTrue(attempted.exceptionOrNull() is IOException)
        assertEquals(unsupported, profileFile.readText())
        assertFalse(essayFile.exists())
    }

    @Test fun unreadableEssayFileIsPreservedWithoutFallingBackToProfileData() = runBlocking {
        val oldProfile = """{"version":1,"records":[
            {"id":"old","domain":"VALUES","type":"TRAIT","description":"原记录"}
        ]}"""
        val unsupported = """{"version":99,"records":[]}"""
        profileFile.writeText(oldProfile)
        essayFile.writeText(unsupported)
        val repository = WithinRepository(app)
        assertTrue(runCatching { repository.load() }.exceptionOrNull() is IOException)
        assertTrue(runCatching { repository.delete("old") }.exceptionOrNull() is IOException)
        assertEquals(unsupported, essayFile.readText())
        assertEquals(oldProfile, profileFile.readText())
    }

    @Test fun blankEssayDoesNotWriteToDisk() = runBlocking {
        val attempted = runCatching {
            WithinRepository(app).save(null, WithinEssayDraft(" \n\t", newerDate))
        }
        assertTrue(attempted.exceptionOrNull() is IllegalArgumentException)
        assertFalse(essayFile.exists())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun styledEssayAndCopiedImageSurviveReopenAlongsidePlainV2Essay() = runBlocking {
        essayFile.writeText("""{"version":2,"records":[
            {"id":"plain","body":"已有随笔\n第二行","date":"2026-11-16","categories":["SELF"]}
        ]}""")
        val plain = WithinEssay("plain", WithinEssayDraft("已有随笔\n第二行", olderDate, setOf(WithinCategory.SELF)))
        val repository = WithinRepository(app)
        assertEquals(listOf(plain), repository.load())

        val image = repository.importImage(providedImage("selected", png), offset = 3)
        assertEquals("image/png", image.mimeType)
        val spans = listOf(
            WithinTextSpan(1, 3, WithinTextStyle(WithinFontFamily.SERIF, 24, 0xfff1cd57.toInt(), bold = true)),
            WithinTextSpan(3, 4, WithinTextStyle(italic = true, underline = true))
        )
        val rich = WithinEssayDraft("图🌟文\n末尾", newerDate, setOf(WithinCategory.ECHO), spans, listOf(image))
        val created = repository.save(null, rich).first()

        val reopened = WithinRepository(app)
        assertEquals(listOf(created, plain), reopened.load())
        assertEquals(rich, reopened.load().first().draft)
        reopened.discardUnreferencedImages(listOf(image))
        assertArrayEquals(png, reopened.readImage(image))

        val imageOnly = reopened.save(null, WithinEssayDraft("", newerDate, images = listOf(image.copy(offset = 0))))
            .first()
        reopened.save(created.id, rich.copy(images = emptyList()))
        assertArrayEquals(png, reopened.readImage(image))
        reopened.delete(imageOnly.id)
        assertTrue(runCatching { reopened.readImage(image) }.exceptionOrNull() is IOException)
        assertEquals(listOf(created.copy(draft = rich.copy(images = emptyList())), plain), reopened.load())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun invalidAndDiscardedImagesLeaveNoPrivateCopiesOrEssay() = runBlocking {
        val repository = WithinRepository(app)
        val directory = File(app.filesDir, "starlive-within-images")
        val invalid = providedImage("invalid", "not a picture".toByteArray())
        val oversized = providedImage("oversized", ByteArray(8 * 1024 * 1024 + 1))
        assertTrue(runCatching { repository.importImage(invalid, 0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { repository.importImage(oversized, 0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(directory.list().orEmpty().isEmpty())

        val temporary = repository.importImage(providedImage("clipboard", png), 0)
        assertArrayEquals(png, repository.readImage(temporary))
        repository.discardUnreferencedImages(listOf(temporary))
        assertTrue(directory.list().orEmpty().isEmpty())
        assertFalse(essayFile.exists())
    }

    @Test fun malformedFormattingDoesNotOverwriteExistingRecords() = runBlocking {
        val repository = WithinRepository(app)
        val created = repository.save(null, WithinEssayDraft("现有随笔正文", newerDate)).single()
        val originalBytes = essayFile.readBytes()
        val overlapping = created.draft.copy(spans = listOf(
            WithinTextSpan(0, 3, WithinTextStyle(bold = true)),
            WithinTextSpan(2, 4, WithinTextStyle(italic = true))
        ))
        assertTrue(runCatching { repository.save(created.id, overlapping) }.exceptionOrNull() is IllegalArgumentException)
        assertArrayEquals(originalBytes, essayFile.readBytes())
        assertEquals(listOf(created), repository.load())

        val unsupported = """{"version":2,"records":[
            {"id":"unsupported","body":"原文","date":"2026-11-17","categories":[],
             "spans":[{"start":0,"end":1,"fontFamily":"UNKNOWN"}]}
        ]}""".trimIndent().toByteArray()
        essayFile.writeBytes(unsupported)
        assertTrue(runCatching { repository.load() }.exceptionOrNull() is IOException)
        assertTrue(runCatching { repository.save(null, created.draft) }.exceptionOrNull() is IOException)
        assertArrayEquals(unsupported, essayFile.readBytes())
    }

    private fun providedImage(path: String, bytes: ByteArray): Uri = Uri.parse("content://within-test/$path").also {
        shadowOf(app.contentResolver).registerInputStream(it, ByteArrayInputStream(bytes))
    }

    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAIAAAB7QOjdAAAAD0lEQVR4nGOYoPC/weEBAArjA1DL0a/KAAAAAElFTkSuQmCC"
    )
}
