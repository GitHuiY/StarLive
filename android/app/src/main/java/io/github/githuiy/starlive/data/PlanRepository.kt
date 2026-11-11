package io.github.githuiy.starlive.data

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import io.github.githuiy.starlive.core.AppState
import io.github.githuiy.starlive.core.BackupCodec
import io.github.githuiy.starlive.core.BackupSummary
import io.github.githuiy.starlive.core.PlanEngine
import io.github.githuiy.starlive.core.ReminderSettings
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class RepositoryState(val data: AppState?, val loading: Boolean, val error: String?)
data class BackupPreview(
    val token: String, val summary: BackupSummary,
    val firstWeek: String?, val lastWeek: String?, val settings: ReminderSettings
)

/** All file, URI, validation and mutation work is serialized off the UI thread. */
class PlanRepository internal constructor(context: Context, private val storage: PlanStorage) : AutoCloseable {
    constructor(context: Context) : this(context, AtomicPlanStorage(context))
    private val application = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { action ->
        Thread(action, "starlive-plan-storage")
    }
    private val closed = AtomicBoolean(false)
    private val listeners = CopyOnWriteArraySet<(RepositoryState) -> Unit>()
    @Volatile private var published = RepositoryState(null, true, null)
    // The following fields belong to the IO executor only.
    private var data: AppState? = null
    private var revision = 0L
    private var prepared: PendingPreview? = null
    private var writesBlocked = false

    private data class PendingPreview(
        val token: String, val backup: BackupCodec.Prepared,
        val revision: Long, val restore: Boolean
    )

    init {
        enqueue {
            try {
                // openRead also recovers AtomicFile's own interrupted-write backup.
                val bytes = storage.read(PlanSlot.PRIMARY)
                data = bytes?.let { BackupCodec.decode(readUtf8(it)).data } ?: AppState.empty()
                val recoveryWarning = finishCommittedJournal(bytes)
                publish(RepositoryState(data, false, recoveryWarning))
            } catch (failure: Exception) {
                // Preserve the original file. No empty replacement is written.
                publish(RepositoryState(null, false,
                    "本地计划无法读取，原文件已保留。可预览恢复备份或导入有效备份。${message(failure)}"))
            }
        }
    }

    val currentState: RepositoryState get() = published

    fun observe(listener: (RepositoryState) -> Unit): AutoCloseable {
        if (closed.get()) throw IllegalStateException("计划存储已关闭")
        listeners.add(listener)
        onMain { if (listeners.contains(listener)) listener(published) }
        return AutoCloseable { listeners.remove(listener) }
    }

    fun refreshCalendar() = enqueue {
        // Dates use LocalDate.now() on each operation; historical plans are never removed.
        publish(RepositoryState(data, false, published.error))
    }

    fun toggleCompletion(week: String, day: Int, id: String) = mutate {
        engine().toggleCompletion(it, week, day, id)
    }

    fun setCompletion(week: String, day: Int, id: String, completed: Boolean) = mutate { state ->
        val item = PlanEngine.confirmed(state, week).days[day].firstOrNull { it.id == id }
        if (item == null || item.done == completed) state
        else engine().toggleCompletion(state, week, day, id)
    }

    fun addDraftItem(week: String, day: Int, title: String, category: String) =
        addDraftItem(week, day, title, category, "")

    fun addDraftItem(week: String, day: Int, title: String, category: String, body: String) = mutate {
        engine().addDraftItem(it, week, day, title, category, body)
    }

    fun updateDraftItem(week: String, day: Int, id: String, title: String, category: String) = mutate {
        engine().updateDraftItem(it, week, day, id, title, category)
    }

    fun updateDraftItem(week: String, day: Int, id: String, title: String, category: String, body: String) = mutate {
        engine().updateDraftItem(it, week, day, id, title, category, body)
    }

    fun deleteDraftItem(week: String, day: Int, id: String) = mutate {
        engine().deleteDraftItem(it, week, day, id)
    }

    fun moveDraftItem(week: String, fromDay: Int, id: String, toDay: Int, toIndex: Int) = mutate {
        engine().moveDraftItem(it, week, fromDay, id, toDay, toIndex)
    }

    fun moveDraftItemBy(week: String, day: Int, id: String, delta: Int) = mutate { state ->
        val items = PlanEngine.draft(state, week).days[day]
        val index = items.indexOfFirst { it.id == id }
        require(index >= 0) { "事项不存在，请刷新后重试" }
        val destination = (index.toLong() + delta).coerceIn(0, (items.size - 1).toLong()).toInt()
        if (destination == index) state
        else engine().moveDraftItem(state, week, day, id, day, destination)
    }

    fun moveDraftItemToDay(week: String, fromDay: Int, id: String, toDay: Int) = mutate { state ->
        if (fromDay == toDay) state
        else engine().moveDraftItem(state, week, fromDay, id, toDay,
            PlanEngine.draft(state, week).days[toDay].size)
    }

    fun confirmDraft(week: String) = mutate { engine().confirmDraft(it, week) }

    fun updateSettings(settings: ReminderSettings) = mutate { engine().updateSettings(it, settings) }

    fun updateSettings(transform: (ReminderSettings) -> ReminderSettings) = mutate {
        engine().updateSettings(it, transform(it.settings))
    }

    fun exportJson(callback: (Result<String>) -> Unit) = operation(callback) {
        BackupCodec.encode(requireData())
    }

    fun exportTo(uri: Uri, callback: (Result<Unit>) -> Unit) = operation(callback) {
        val encoded = BackupCodec.encode(requireData()).toByteArray(StandardCharsets.UTF_8)
        val stream = application.contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("无法打开所选导出位置")
        stream.use { it.write(encoded); it.flush() }
    }

    fun prepareImport(json: String, callback: (Result<BackupPreview>) -> Unit) = operation(callback) {
        preview(BackupCodec.decode(json), false)
    }

    fun prepareImport(uri: Uri, callback: (Result<BackupPreview>) -> Unit) = operation(callback) {
        val input = application.contentResolver.openInputStream(uri)
            ?: throw IOException("无法打开所选备份")
        val bytes = input.use { source ->
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = source.read(buffer)
                if (count == -1) break
                if (result.size() + count > BackupCodec.MAX_BYTES) {
                    throw IllegalArgumentException("备份超过 2 MiB 大小上限")
                }
                result.write(buffer, 0, count)
            }
            result.toByteArray()
        }
        preview(BackupCodec.decode(readUtf8(bytes)), false)
    }

    fun prepareRestore(callback: (Result<BackupPreview>) -> Unit) = operation(callback) {
        val journal = committedJournal(storage.read(PlanSlot.PRIMARY))
        val bytes = journal?.original ?: storage.read(PlanSlot.PREVIOUS)
            ?: throw IOException("暂无覆盖前自动备份可恢复")
        preview(BackupCodec.decode(readUtf8(bytes)), true)
    }

    fun confirmImport(token: String, callback: (Result<Unit>) -> Unit = {}) = operation(callback) {
        replacePrepared(token, false)
    }

    fun confirmRestore(token: String, callback: (Result<Unit>) -> Unit = {}) = operation(callback) {
        replacePrepared(token, true)
    }

    private fun preview(backup: BackupCodec.Prepared, restore: Boolean): BackupPreview {
        val token = UUID.randomUUID().toString()
        prepared = PendingPreview(token, backup, revision, restore)
        val weeks = (backup.data.weeks.keys + backup.data.drafts.keys).sorted()
        return BackupPreview(token, backup.summary, weeks.firstOrNull(), weeks.lastOrNull(),
            backup.data.settings)
    }

    private fun replacePrepared(token: String, restore: Boolean) {
        val pending = prepared ?: throw IllegalArgumentException("请先预览备份")
        if (pending.token != token || pending.restore != restore) {
            throw IllegalArgumentException("备份预览已失效，请重新预览")
        }
        if (pending.revision != revision) throw IllegalArgumentException("计划已变化，请重新预览后再确认")
        val newBytes = BackupCodec.encode(pending.backup.data).toByteArray(StandardCharsets.UTF_8)
        val original = storage.read(PlanSlot.PRIMARY)
        // A prior import may have committed while promoting its restore point failed.
        // Finalize that journal before overwriting the single staging slot; otherwise
        // a failed retry could destroy the only copy of the requested restore target.
        val unfinished = finishCommittedJournal(original)
        if (unfinished != null) throw IOException("原恢复点仍待整理，已保留该恢复目标，暂不执行新的覆盖。$unfinished")
        var journal: ReplacementJournal? = null
        if (original != null) {
            val validOriginal = try { BackupCodec.decode(readUtf8(original)); true }
                catch (invalid: Exception) { false }
            if (validOriginal) {
                journal = ReplacementJournal(digest(newBytes), original)
            } else {
                // Keep a corrupt original separately so a valid last-backup is not destroyed.
                storage.preserveCorrupt(original)
            }
        } else if (data != null) {
            // A first import also preserves the empty/current personal state for undo.
            journal = ReplacementJournal(digest(newBytes),
                BackupCodec.encode(requireData()).toByteArray(StandardCharsets.UTF_8))
        }
        // Staging never replaces the current restore target. The digest identifies whether
        // primary committed, including after process death between the two file operations.
        if (journal != null) storage.write(PlanSlot.STAGED, journal.encode())
        val commitWarning = commitPrimary(newBytes, original)
        data = pending.backup.data
        revision++
        prepared = null
        writesBlocked = false
        val recoveryWarning = finishCommittedJournal(newBytes)
        publish(RepositoryState(data, false, recoveryWarning ?: commitWarning))
    }

    private fun mutate(change: (AppState) -> AppState) = operation<Unit>({}) {
        val current = requireWritableData()
        val before = storage.read(PlanSlot.PRIMARY)
        val unfinished = finishCommittedJournal(before)
        if (unfinished != null) throw IOException(unfinished)
        val candidate = change(current)
        if (candidate !== current) {
            val encoded = BackupCodec.encode(candidate).toByteArray(StandardCharsets.UTF_8)
            val warning = commitPrimary(encoded, before)
            // Publish only after the atomic write succeeds.
            data = candidate
            revision++
            publish(RepositoryState(data, false, warning))
        } else {
            publish(RepositoryState(data, false, null))
        }
    }

    private fun commitPrimary(bytes: ByteArray, before: ByteArray?): String? {
        try {
            storage.write(PlanSlot.PRIMARY, bytes)
            return null
        } catch (failure: Exception) {
            // finishWrite may already have renamed the file before verification failed.
            // Read the actual disk state instead of falsely promising that the old file won.
            val observed = try { storage.read(PlanSlot.PRIMARY) }
            catch (unreadable: Exception) {
                blockUncertainWrites(null)
                throw IOException("保存结果无法确认，磁盘可能已更新；已暂停编辑，请重新打开应用核对或导入恢复。", failure)
            }
            if (observed != null && observed.contentEquals(bytes)) {
                return "保存校验曾报错，已重新读取确认新数据已保存。"
            }
            if (sameBytes(before, observed)) {
                throw IOException("保存失败，已确认原文件未改变：${message(failure)}", failure)
            }
            blockUncertainWrites(observed)
            throw IOException("磁盘数据与预期不一致，已重新读取可用内容并暂停编辑；请重新打开应用核对或导入恢复。", failure)
        }
    }

    private fun blockUncertainWrites(observed: ByteArray?) {
        writesBlocked = true
        revision++
        prepared = null
        data = try { observed?.let { BackupCodec.decode(readUtf8(it)).data } }
        catch (invalid: Exception) { null }
    }

    private data class ReplacementJournal(val expectedDigest: ByteArray, val original: ByteArray) {
        fun encode(): ByteArray = ByteBuffer.allocate(4 + 32 + 4 + original.size)
            .putInt(0x53544c31).put(expectedDigest).putInt(original.size).put(original).array()
    }

    private fun committedJournal(primary: ByteArray?): ReplacementJournal? {
        val journal = readJournal() ?: return null
        return if (primary != null && digest(primary).contentEquals(journal.expectedDigest)) journal else null
    }

    private fun readJournal(): ReplacementJournal? {
        val bytes = storage.read(PlanSlot.STAGED) ?: return null
        val buffer = ByteBuffer.wrap(bytes)
        if (bytes.size < 40 || buffer.int != 0x53544c31) throw IOException("暂存恢复点损坏，原件已保留")
        val expected = ByteArray(32)
        buffer.get(expected)
        val length = buffer.int
        if (length < 0 || length > BackupCodec.MAX_BYTES || buffer.remaining() != length) {
            throw IOException("暂存恢复点长度无效，原件已保留")
        }
        val original = ByteArray(length)
        buffer.get(original)
        return ReplacementJournal(expected, original)
    }

    private fun finishCommittedJournal(primary: ByteArray?): String? {
        try {
            val journal = readJournal() ?: return null
            if (primary == null || !digest(primary).contentEquals(journal.expectedDigest)) {
                if (primary != null && primary.contentEquals(journal.original)) {
                    // Only the exact original proves this transaction never committed.
                    // Remove its intent before a later write could match the old candidate.
                    storage.remove(PlanSlot.STAGED)
                    return null
                }
                // A third state is not evidence of either commit or rollback. Its journal
                // may contain the only surviving original; reopening must not discard it.
                writesBlocked = true
                return "主计划既不等于覆盖前原件，也不等于预期新数据；暂存原件已保留，无法自动判定保存结果。编辑已暂停，重新打开不会自动覆盖修复，请保留文件并核对恢复。"
            }
            BackupCodec.decode(readUtf8(journal.original))
            storage.write(PlanSlot.PREVIOUS, journal.original)
            storage.remove(PlanSlot.STAGED)
            return null
        } catch (failure: Exception) {
            // Primary is already known to be committed. Keep the journal reachable and
            // reject ordinary edits until its restore point can be finalized on reopen.
            writesBlocked = true
            return "计划已读取／保存，但恢复点整理失败；原数据仍保存在暂存恢复点，已暂停编辑。${message(failure)}"
        }
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun sameBytes(first: ByteArray?, second: ByteArray?) =
        if (first == null) second == null else second != null && first.contentEquals(second)

    private fun readUtf8(bytes: ByteArray): String {
        if (bytes.size > BackupCodec.MAX_BYTES) throw IllegalArgumentException("备份超过 2 MiB 大小上限")
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun engine() = PlanEngine(LocalDate.now())
    private fun requireData() = data ?: throw IllegalStateException("本地计划尚未成功读取，已阻止覆盖原文件")
    private fun requireWritableData(): AppState {
        check(!writesBlocked) { "保存结果或恢复点尚未确认，编辑已暂停；请重新打开应用核对或先预览恢复。" }
        return requireData()
    }

    private fun <T> operation(callback: (Result<T>) -> Unit, action: () -> T) = enqueue {
        val result = try { Result.success(action()) }
        catch (failure: Exception) {
            publish(RepositoryState(data, false, message(failure)))
            Result.failure(failure)
        }
        onMain { callback(result) }
    }

    private fun enqueue(action: () -> Unit) {
        if (closed.get()) return
        io.execute(action)
    }

    private fun publish(state: RepositoryState) {
        published = state
        val recipients = listeners.toList()
        onMain {
            recipients.forEach { listener -> if (listeners.contains(listener)) listener(state) }
        }
    }

    private fun onMain(action: () -> Unit) {
        if (closed.get()) return
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else main.post { if (!closed.get()) action() }
    }

    private fun message(failure: Exception) = failure.message ?: failure.javaClass.simpleName

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            listeners.clear()
            // Already accepted writes drain; closing the UI does not drop a pending save.
            io.shutdown()
        }
    }
}
