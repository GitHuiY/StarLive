package io.github.githuiy.starlive.data

import android.content.Context
import android.util.AtomicFile
import io.github.githuiy.starlive.core.BackupCodec
import io.github.githuiy.starlive.core.ReminderSettings
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID

internal enum class PlanSlot(val fileName: String) {
    PRIMARY("starlive-plans-v1.json"),
    PREVIOUS("starlive-before-import-v1.json"),
    STAGED("starlive-before-import-pending-v1.bin")
}

/** Injectable boundary for deterministic pre-/post-commit filesystem failures. */
internal interface PlanStorage {
    fun read(slot: PlanSlot): ByteArray?
    fun write(slot: PlanSlot, bytes: ByteArray)
    fun remove(slot: PlanSlot)
    fun preserveCorrupt(bytes: ByteArray)
}

internal class AtomicPlanStorage(context: Context) : PlanStorage {
    private val application = context.applicationContext
    private companion object {
        // On older Android versions openRead may restore .bak while startWrite is active.
        val fileLock = Any()
        var cachedSettings: Pair<File, ReminderSettings>? = null
    }

    override fun read(slot: PlanSlot): ByteArray? = synchronized(fileLock) {
        readFile(file(slot.fileName), limit(slot))
    }

    override fun write(slot: PlanSlot, bytes: ByteArray) = synchronized(fileLock) {
        if (slot == PlanSlot.PRIMARY) cachedSettings = null
        writeFile(file(slot.fileName), bytes, limit(slot))
    }

    override fun remove(slot: PlanSlot) = synchronized(fileLock) {
        if (slot == PlanSlot.PRIMARY) cachedSettings = null
        val target = file(slot.fileName)
        target.delete()
        if (target.baseFile.exists()) throw IOException("临时恢复点清理失败")
    }

    override fun preserveCorrupt(bytes: ByteArray) = synchronized(fileLock) {
        writeFile(file("starlive-corrupt-original-${UUID.randomUUID()}.json"), bytes,
            BackupCodec.MAX_BYTES)
    }

    fun reminderSettings(): ReminderSettings = synchronized(fileLock) {
        val primary = file(PlanSlot.PRIMARY.fileName)
        cachedSettings?.takeIf { it.first == primary.baseFile }?.let { return@synchronized it.second }
        val bytes = readFile(primary, BackupCodec.MAX_BYTES)
        val settings = if (bytes == null) {
            ReminderSettings.defaults()
        } else {
            val json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
            BackupCodec.decode(json).data.settings
        }
        cachedSettings = primary.baseFile to settings
        settings
    }

    private fun file(name: String) = AtomicFile(File(application.filesDir, name))
    private fun limit(slot: PlanSlot) = BackupCodec.MAX_BYTES + if (slot == PlanSlot.STAGED) 128 else 0

    private fun readFile(target: AtomicFile, limit: Int): ByteArray? {
        val source = try { target.openRead() }
        catch (missing: FileNotFoundException) {
            if (target.baseFile.exists()) throw missing
            return null
        }
        return source.use {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = it.read(buffer)
                if (count == -1) break
                if (output.size() + count > limit) throw IOException("本地文件超过安全读取上限，原件已保留")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun writeFile(target: AtomicFile, bytes: ByteArray, limit: Int) {
        if (bytes.size > limit) throw IOException("本地文件超过大小上限")
        var stream: FileOutputStream? = null
        try {
            stream = target.startWrite()
            stream.write(bytes)
            stream.fd.sync()
            target.finishWrite(stream)
            stream = null
            if (readFile(target, limit)?.contentEquals(bytes) != true) {
                throw IOException("本地保存提交后校验失败")
            }
        } catch (failure: Exception) {
            if (stream != null) target.failWrite(stream)
            throw failure
        }
    }
}

/** Worker-thread read of the same durable settings used by the planner and local backups. */
internal fun readCommittedReminderSettings(context: Context): ReminderSettings =
    AtomicPlanStorage(context).reminderSettings()
