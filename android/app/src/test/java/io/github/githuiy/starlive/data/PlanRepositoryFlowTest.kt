package io.github.githuiy.starlive.data

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.githuiy.starlive.core.AppState
import io.github.githuiy.starlive.core.BackupCodec
import io.github.githuiy.starlive.core.PlanEngine
import io.github.githuiy.starlive.core.ReminderSettings
import io.github.githuiy.starlive.PosixAtomicFileShadow
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real repository flows in isolated host files; no Activity, ADB or device is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [PosixAtomicFileShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class PlanRepositoryFlowTest {
    private lateinit var application: Application
    private lateinit var today: LocalDate
    private lateinit var week: String
    private val opened = mutableListOf<PlanRepository>()

    @Before fun resetIsolatedStore() {
        application = ApplicationProvider.getApplicationContext()
        // Only Robolectric's isolated app directory, never files on a physical phone.
        application.filesDir.listFiles()
            ?.filter { it.name.startsWith("starlive-") }
            ?.forEach { check(it.delete()) { "Unable to reset isolated file ${it.name}" } }
        today = LocalDate.now()
        week = PlanEngine.weekKey(today)
    }

    @After fun closeRepositories() {
        opened.forEach { it.close() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun confirmedCompletionAndSeparateDraftSurviveRepositoryReload() {
        val repository = openRepository()
        val id = createConfirmed(repository, "整理衣物")
        repository.toggleCompletion(week, 0, id)
        val nextWeek = PlanEngine.weekKey(today.plusWeeks(1))
        repository.addDraftItem(nextWeek, 2, "下周读书", "阅读")

        val saved = snapshot(repository)
        assertTrue(saved.weeks.getValue(week).days[0].single().done)
        assertFalse(saved.weeks.containsKey(nextWeek))
        assertEquals("下周读书", saved.drafts.getValue(nextWeek).days[2].single().title)
        repository.close()

        val reloaded = snapshot(openRepository())
        assertEquals(id, reloaded.weeks.getValue(week).days[0].single().id)
        assertTrue(reloaded.weeks.getValue(week).days[0].single().done)
        assertFalse(reloaded.weeks.containsKey(nextWeek))
        assertEquals("下周读书", reloaded.drafts.getValue(nextWeek).days[2].single().title)
    }

    @Test fun importPreviewAndCancellingItDoNotWritePersonalFiles() {
        val repository = openRepository()
        createConfirmed(repository, "保留当前计划")
        val originalJson = exported(repository)
        val originalFiles = personalFiles()

        val preview = completed<BackupPreview> { callback ->
            repository.prepareImport(backup("尚未导入的计划", continuousMinutes = 75), callback)
        }.getOrThrow()

        assertTrue(preview.token.isNotBlank())
        assertEquals(week, preview.firstWeek)
        assertEquals(week, preview.lastWeek)
        assertEquals(75, preview.settings.continuousMinutes)
        assertEquals(originalJson, exported(repository))
        assertEquals(originalFiles, personalFiles())

        // Cancelling a preview means not confirming its token. Closing also discards it.
        repository.close()
        val reopened = openRepository()
        assertEquals(originalJson, exported(reopened))
        assertEquals(originalFiles, personalFiles())
        assertEquals("保留当前计划", snapshot(reopened).weeks.getValue(week).days[0].single().title)
    }

    @Test fun importPreservesPreOverwriteBackupAndRestoreCanBePreviewedAndConfirmed() {
        val repository = openRepository()
        val originalId = createConfirmed(repository, "覆盖前的计划")
        repository.toggleCompletion(week, 0, originalId)
        val original = exported(repository)
        assertEquals(60, readCommittedReminderSettings(application).continuousMinutes)

        val imported = completed<BackupPreview> { callback ->
            repository.prepareImport(backup("导入后的计划", continuousMinutes = 75), callback)
        }.getOrThrow()
        completed<Unit> { repository.confirmImport(imported.token, it) }.getOrThrow()
        assertEquals("导入后的计划", snapshot(repository).weeks.getValue(week).days[0].single().title)
        assertEquals(75, snapshot(repository).settings.continuousMinutes)
        assertEquals(75, readCommittedReminderSettings(application).continuousMinutes)
        val importedJson = exported(repository)

        val restore = completed<BackupPreview> { repository.prepareRestore(it) }.getOrThrow()
        assertEquals(60, restore.settings.continuousMinutes)
        assertEquals(1, restore.summary.completedItemCount)
        assertEquals(importedJson, exported(repository))
        completed<Unit> { repository.confirmRestore(restore.token, it) }.getOrThrow()
        assertEquals(original, exported(repository))
        assertEquals(60, readCommittedReminderSettings(application).continuousMinutes)

        // Restoring is itself an overwrite: its pre-overwrite state remains recoverable.
        val undoRestore = completed<BackupPreview> { repository.prepareRestore(it) }.getOrThrow()
        assertEquals(75, undoRestore.settings.continuousMinutes)
        completed<Unit> { repository.confirmRestore(undoRestore.token, it) }.getOrThrow()
        assertEquals(importedJson, exported(repository))
    }

    @Test fun previewBecomesInvalidWhenPersonalDataChangesBeforeConfirmation() {
        val repository = openRepository()
        createConfirmed(repository, "当前正式计划")
        val preview = completed<BackupPreview> { callback ->
            repository.prepareImport(backup("不应覆盖的新计划"), callback)
        }.getOrThrow()

        repository.addDraftItem(week, 1, "预览之后的新草稿", "阅读")
        val changedJson = exported(repository) // IO barrier after the accepted edit.
        val changedFiles = personalFiles()
        val rejected = completed<Unit> { repository.confirmImport(preview.token, it) }

        assertTrue("An obsolete preview must be rejected", rejected.isFailure)
        assertNotNull("The expected stale-preview rejection must be visible", repository.currentState.error)
        // The rejected operation's callback is already an IO barrier. Its expected error
        // must remain visible, rather than being mistaken for an unexpected save failure.
        val current = checkNotNull(repository.currentState.data)
        assertEquals(changedJson, BackupCodec.encode(current))
        assertEquals(changedFiles, personalFiles())
        assertEquals("当前正式计划", current.weeks.getValue(week).days[0].single().title)
        assertEquals("预览之后的新草稿", current.drafts.getValue(week).days[1].single().title)
    }

    @Test fun backToBackSettingTransformsMergeAgainstLatestQueuedState() {
        val repository = openRepository()
        // Deliberately do not await a callback or publication between these two actions.
        repository.updateSettings { current ->
            ReminderSettings(75, current.perAppDailyMinutes, current.totalDailyMinutes,
                false, current.perAppDailyEnabled, current.totalDailyEnabled)
        }
        repository.updateSettings { current ->
            ReminderSettings(current.continuousMinutes, 90, current.totalDailyMinutes,
                current.continuousEnabled, false, current.totalDailyEnabled)
        }

        val settings = snapshot(repository).settings
        assertEquals(75, settings.continuousMinutes)
        assertEquals(90, settings.perAppDailyMinutes)
        assertEquals(180, settings.totalDailyMinutes)
        assertFalse(settings.continuousEnabled)
        assertFalse(settings.perAppDailyEnabled)
        assertTrue(settings.totalDailyEnabled)
        repository.close()
        val persisted = snapshot(openRepository()).settings
        assertEquals(75, persisted.continuousMinutes)
        assertEquals(90, persisted.perAppDailyMinutes)
        assertFalse(persisted.continuousEnabled)
        assertFalse(persisted.perAppDailyEnabled)
    }

    @Test fun backToBackRelativeMovesExecuteBothActionsOnLatestOrder() {
        val repository = openRepository()
        listOf("第一件", "第二件", "第三件", "第四件").forEach { title ->
            repository.addDraftItem(week, 0, title, "喜欢")
        }
        val initial = snapshot(repository).drafts.getValue(week).days[0]
        val id = initial[0].id

        repository.moveDraftItemBy(week, 0, id, 1)
        repository.moveDraftItemBy(week, 0, id, 1)
        val moved = snapshot(repository)

        assertEquals(listOf("第二件", "第三件", "第一件", "第四件"),
            moved.drafts.getValue(week).days[0].map { it.title })
        assertEquals(id, moved.drafts.getValue(week).days[0][2].id)
        assertTrue("Moving a draft must not confirm it", moved.weeks.isEmpty())
    }

    private fun openRepository(): PlanRepository {
        val repository = PlanRepository(application)
        opened += repository
        await("repository initialization") { !repository.currentState.loading }
        assertNull(repository.currentState.error)
        assertNotNull(repository.currentState.data)
        return repository
    }

    private fun createConfirmed(repository: PlanRepository, title: String): String {
        repository.addDraftItem(week, 0, title, "喜欢")
        repository.confirmDraft(week)
        return snapshot(repository).weeks.getValue(week).days[0].single().id
    }

    private fun backup(title: String, continuousMinutes: Int = 60): String {
        val engine = PlanEngine(today)
        val draft = engine.addDraftItem(AppState.empty(), week, 0, title, "阅读")
        val confirmed = engine.confirmDraft(draft, week)
        return BackupCodec.encode(engine.updateSettings(confirmed,
            ReminderSettings(continuousMinutes, 60, 180, true, true, true)))
    }

    private fun exported(repository: PlanRepository): String {
        val result = completed<String> { repository.exportJson(it) }
        val repositoryError = repository.currentState.error
        assertNull("Repository operation failed before export barrier: $repositoryError", repositoryError)
        return result.getOrThrow()
    }

    private fun snapshot(repository: PlanRepository): AppState =
        BackupCodec.decode(exported(repository)).data

    private fun personalFiles(): Map<String, String> = application.filesDir.listFiles()
        .orEmpty().filter { it.name.startsWith("starlive-") && it.isFile }
        .sortedBy { it.name }.associate { it.name to it.readText(Charsets.UTF_8) }

    private fun <T> completed(start: ((Result<T>) -> Unit) -> Unit): Result<T> {
        var result: Result<T>? = null
        start { result = it }
        await("repository operation callback") { result != null }
        return checkNotNull(result)
    }

    private fun await(description: String, condition: () -> Boolean) {
        // Only immediate main-Looper work is drained. The IO executor runs normally.
        // Bounded 5 ms yields avoid both long sleeps and unbounded readiness loops.
        repeat(2_000) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Timed out waiting for $description")
    }
}
