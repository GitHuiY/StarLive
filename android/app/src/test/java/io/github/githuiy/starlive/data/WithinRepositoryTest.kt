package io.github.githuiy.starlive.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.githuiy.starlive.PosixAtomicFileShadow
import java.io.File
import java.io.IOException
import java.time.LocalDate
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
import org.robolectric.annotation.Config

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
}
