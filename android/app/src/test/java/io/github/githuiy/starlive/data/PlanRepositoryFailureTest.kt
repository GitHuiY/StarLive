package io.github.githuiy.starlive.data

import android.content.Context
import android.os.Looper
import io.github.githuiy.starlive.core.AppState
import io.github.githuiy.starlive.core.BackupCodec
import io.github.githuiy.starlive.core.PlanEngine
import io.github.githuiy.starlive.core.ReminderSettings
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class PlanRepositoryFailureTest {
    private val repositories = mutableListOf<PlanRepository>()
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @After fun closeRepositories() { repositories.forEach { it.close() } }

    @Test fun failureBeforePrimaryCommitKeepsDiskAndPublishedData() {
        val original = stateWithItems(1)
        val storage = FaultStorage(original)
        val repo = open(storage)
        val oldSnapshot = repo.currentState.data
        storage.failPrimaryBefore.set(1)

        repo.updateSettings(ReminderSettings(90, 60, 180, true, true, true))
        await { repo.currentState.error?.contains("原文件未改变") == true }

        assertSame(oldSnapshot, repo.currentState.data)
        assertEquals(60, repo.currentState.data!!.settings.continuousMinutes)
        assertArrayEquals(bytes(original), storage.read(PlanSlot.PRIMARY))
    }

    @Test fun restorePrimaryFailureDoesNotDestroyTheRequestedRestoreTarget() {
        val desired = stateWithItems(1)
        val current = stateWithItems(2)
        val storage = FaultStorage(current)
        storage.seed(PlanSlot.PREVIOUS, bytes(desired))
        val repo = open(storage)
        val preview = result<BackupPreview> { repo.prepareRestore(it) }.getOrThrow()
        storage.failPrimaryBefore.set(1)

        assertTrue(result<Unit> { repo.confirmRestore(preview.token, it) }.isFailure)

        assertArrayEquals(bytes(current), storage.read(PlanSlot.PRIMARY))
        assertArrayEquals(bytes(desired), storage.read(PlanSlot.PREVIOUS))
        val stillAvailable = result<BackupPreview> { repo.prepareRestore(it) }.getOrThrow()
        assertEquals(1, stillAvailable.summary.itemCount)
        assertEquals(2, repo.currentState.data!!.weeks.values.sumOf { it.days.sumOf { day -> day.size } })
    }

    @Test fun stagingFailureAbortsImportBeforeAnyPrimaryReplacement() {
        val current = stateWithItems(1)
        val previous = stateWithItems(0)
        val storage = FaultStorage(current)
        storage.seed(PlanSlot.PREVIOUS, bytes(previous))
        val repo = open(storage)
        val preview = result<BackupPreview> { repo.prepareImport(BackupCodec.encode(stateWithItems(2)), it) }.getOrThrow()
        storage.failStaging.set(1)

        assertTrue(result<Unit> { repo.confirmImport(preview.token, it) }.isFailure)

        assertArrayEquals(bytes(current), storage.read(PlanSlot.PRIMARY))
        assertArrayEquals(bytes(previous), storage.read(PlanSlot.PREVIOUS))
        assertEquals(0, storage.primaryWriteCount.get())
    }

    @Test fun postCommitVerificationErrorReconcilesKnownDiskInsteadOfReportingOldState() {
        val storage = FaultStorage(stateWithItems(1))
        val repo = open(storage)
        storage.failPrimaryAfterCommit.set(1)

        repo.updateSettings(ReminderSettings(90, 60, 180, true, true, true))
        await { repo.currentState.data?.settings?.continuousMinutes == 90 }

        assertEquals(90, BackupCodec.decode(storage.read(PlanSlot.PRIMARY)!!.toString(StandardCharsets.UTF_8))
            .data.settings.continuousMinutes)
        assertTrue(repo.currentState.error!!.contains("重新读取确认"))
        assertFalse(repo.currentState.error!!.contains("原文件未改变"))
    }

    @Test fun unreadablePostCommitStatePausesEditsUntilTheActualFileCanBeReloaded() {
        val storage = FaultStorage(stateWithItems(1))
        val repo = open(storage)
        storage.failPrimaryAfterCommit.set(1)
        storage.failReadAfterCommit.set(1)

        repo.updateSettings(ReminderSettings(90, 60, 180, true, true, true))
        await { repo.currentState.error?.contains("无法确认") == true }
        assertNull(repo.currentState.data)
        val writes = storage.primaryWriteCount.get()

        repo.updateSettings(ReminderSettings(120, 60, 180, true, true, true))
        result<String> { repo.exportJson(it) } // serial-queue barrier; failure is expected.
        assertEquals(writes, storage.primaryWriteCount.get())
        repo.close()
        val reopened = open(storage)
        assertEquals(90, reopened.currentState.data!!.settings.continuousMinutes)
    }

    @Test fun failedRestorePointPromotionKeepsJournalReachableAndRecoversOnReopen() {
        val original = stateWithItems(1)
        val imported = stateWithItems(2)
        val older = stateWithItems(0)
        val storage = FaultStorage(original)
        storage.seed(PlanSlot.PREVIOUS, bytes(older))
        val repo = open(storage)
        val preview = result<BackupPreview> { repo.prepareImport(BackupCodec.encode(imported), it) }.getOrThrow()
        storage.failPrevious.set(1)

        assertTrue(result<Unit> { repo.confirmImport(preview.token, it) }.isSuccess)
        assertTrue(repo.currentState.error!!.contains("恢复点整理失败"))
        assertArrayEquals(bytes(imported), storage.read(PlanSlot.PRIMARY))
        assertArrayEquals(bytes(older), storage.read(PlanSlot.PREVIOUS))
        assertNotNull(storage.read(PlanSlot.STAGED))
        val staged = result<BackupPreview> { repo.prepareRestore(it) }.getOrThrow()
        assertEquals(1, staged.summary.itemCount)

        repo.close()
        val reopened = open(storage)
        assertNull(reopened.currentState.error)
        assertArrayEquals(bytes(original), storage.read(PlanSlot.PREVIOUS))
        assertNull(storage.read(PlanSlot.STAGED))
        val restore = result<BackupPreview> { reopened.prepareRestore(it) }.getOrThrow()
        assertTrue(result<Unit> { reopened.confirmRestore(restore.token, it) }.isSuccess)
        assertArrayEquals(bytes(original), storage.read(PlanSlot.PRIMARY))
    }

    @Test fun corruptPrimaryIsNeverResetOrOverwrittenByOrdinaryEditing() {
        val storage = FaultStorage(stateWithItems(0))
        val corrupt = "{broken".toByteArray(StandardCharsets.UTF_8)
        storage.seed(PlanSlot.PRIMARY, corrupt)
        storage.seed(PlanSlot.PREVIOUS, bytes(stateWithItems(1)))
        val repo = open(storage)
        assertNull(repo.currentState.data)
        assertNotNull(repo.currentState.error)

        repo.addDraftItem(PlanEngine.weekKey(LocalDate.now()), 0, "不应覆盖", "喜欢")
        result<String> { repo.exportJson(it) }
        assertArrayEquals(corrupt, storage.read(PlanSlot.PRIMARY))
        assertEquals(0, storage.primaryWriteCount.get())

        val restore = result<BackupPreview> { repo.prepareRestore(it) }.getOrThrow()
        assertArrayEquals(corrupt, storage.read(PlanSlot.PRIMARY))
        assertTrue(result<Unit> { repo.confirmRestore(restore.token, it) }.isSuccess)
        assertEquals(1, storage.rescued.size)
        assertArrayEquals(corrupt, storage.rescued[0])
        assertNotNull(repo.currentState.data)
    }

    @Test fun thirdDiskStateNeverDiscardsTheOnlyOriginalJournalOnReopen() {
        val original = stateWithItems(1)
        val candidate = stateWithItems(2)
        val thirdState = stateWithItems(3)
        val storage = FaultStorage(original)
        val repo = open(storage)
        val preview = result<BackupPreview> {
            repo.prepareImport(BackupCodec.encode(candidate), it)
        }.getOrThrow()
        storage.replacePrimaryAfterCommit = bytes(thirdState)
        storage.failPrimaryAfterCommit.set(1)

        assertTrue(result<Unit> { repo.confirmImport(preview.token, it) }.isFailure)
        val retainedJournal = storage.read(PlanSlot.STAGED)
        assertNotNull(retainedJournal)
        assertArrayEquals(bytes(thirdState), storage.read(PlanSlot.PRIMARY))
        repo.close()

        val reopened = open(storage)
        assertTrue(reopened.currentState.error!!.contains("无法自动判定"))
        assertArrayEquals(retainedJournal, storage.read(PlanSlot.STAGED))
        assertEquals(3, reopened.currentState.data!!.weeks.values.sumOf {
            it.days.sumOf { day -> day.size }
        })
        val writes = storage.primaryWriteCount.get()
        reopened.updateSettings(ReminderSettings(120, 60, 180, true, true, true))
        result<String> { reopened.exportJson(it) }
        assertEquals(writes, storage.primaryWriteCount.get())
        assertArrayEquals(retainedJournal, storage.read(PlanSlot.STAGED))
        assertArrayEquals(bytes(thirdState), storage.read(PlanSlot.PRIMARY))
    }

    private fun open(storage: FaultStorage): PlanRepository {
        val repo = PlanRepository(context, storage)
        repositories.add(repo)
        await { !repo.currentState.loading }
        return repo
    }

    private fun stateWithItems(count: Int): AppState {
        val today = LocalDate.now()
        val week = PlanEngine.weekKey(today)
        val engine = PlanEngine(today)
        var state = AppState.empty()
        repeat(count) { state = engine.addDraftItem(state, week, 0, "事项 $it", "喜欢") }
        return engine.confirmDraft(state, week)
    }

    private fun bytes(state: AppState) = BackupCodec.encode(state).toByteArray(StandardCharsets.UTF_8)

    private fun <T> result(start: ((Result<T>) -> Unit) -> Unit): Result<T> {
        val value = AtomicReference<Result<T>?>(null)
        start { value.set(it) }
        await { value.get() != null }
        return value.get()!!
    }

    private fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.nanoTime() >= until) fail("Repository operation did not complete")
            Thread.sleep(2)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private class FaultStorage(initial: AppState) : PlanStorage {
        private val files = ConcurrentHashMap<PlanSlot, ByteArray>()
        val rescued = CopyOnWriteArrayList<ByteArray>()
        val failPrimaryBefore = AtomicInteger()
        val failPrimaryAfterCommit = AtomicInteger()
        val failReadAfterCommit = AtomicInteger()
        val failStaging = AtomicInteger()
        val failPrevious = AtomicInteger()
        val primaryWriteCount = AtomicInteger()
        @Volatile var replacePrimaryAfterCommit: ByteArray? = null
        private val remainingReadFailures = AtomicInteger()

        init { seed(PlanSlot.PRIMARY, BackupCodec.encode(initial).toByteArray(StandardCharsets.UTF_8)) }
        fun seed(slot: PlanSlot, bytes: ByteArray) { files[slot] = bytes.copyOf() }

        override fun read(slot: PlanSlot): ByteArray? {
            if (slot == PlanSlot.PRIMARY && consume(remainingReadFailures)) throw IOException("injected read failure")
            return files[slot]?.copyOf()
        }

        override fun write(slot: PlanSlot, bytes: ByteArray) {
            if (slot == PlanSlot.STAGED && consume(failStaging)) throw IOException("injected staging failure")
            if (slot == PlanSlot.PREVIOUS && consume(failPrevious)) throw IOException("injected promotion failure")
            if (slot == PlanSlot.PRIMARY) {
                primaryWriteCount.incrementAndGet()
                if (consume(failPrimaryBefore)) throw IOException("injected pre-commit failure")
            }
            files[slot] = bytes.copyOf()
            if (slot == PlanSlot.PRIMARY && consume(failPrimaryAfterCommit)) {
                replacePrimaryAfterCommit?.let { files[slot] = it.copyOf() }
                if (consume(failReadAfterCommit)) remainingReadFailures.incrementAndGet()
                throw IOException("injected post-commit verification failure")
            }
        }

        override fun remove(slot: PlanSlot) { files.remove(slot) }
        override fun preserveCorrupt(bytes: ByteArray) { rescued.add(bytes.copyOf()) }

        private fun consume(counter: AtomicInteger): Boolean {
            while (true) {
                val count = counter.get()
                if (count <= 0) return false
                if (counter.compareAndSet(count, count - 1)) return true
            }
        }
    }
}
