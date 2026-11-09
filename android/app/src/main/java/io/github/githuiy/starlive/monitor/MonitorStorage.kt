package io.github.githuiy.starlive.monitor

import android.util.AtomicFile
import io.github.githuiy.starlive.core.monitor.ReminderPolicy.PendingSnooze
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

internal data class MonitorControl(
    val enabled: Boolean = false,
    val selected: Set<String> = emptySet(),
    val scopeStartWall: Long = 0,
    val bootCount: Long = -1,
    val clockUncertainUntilWall: Long = 0,
    val sentKeys: Set<String> = emptySet(),
    val pending: PendingSnooze? = null,
    val alertId: String? = null,
    val snoozeOutcome: MonitorSnoozeOutcome = MonitorSnoozeOutcome.NONE,
    val snoozeDelay: Long? = null
)

/** This file belongs to the controller worker and never enters a plan or diagnostic export. */
internal class MonitorStorage(files: File) {
    private val file = MonitorAtomicFile(File(files, "starlive-monitor-control-v1.json"), 8 * 1024 * 1024)

    fun read(): MonitorControl {
        val json = file.read() ?: return MonitorControl()
        check(json.getInt("schema") == 1) { "Unsupported monitor state" }
        val pending = json.optJSONObject("pending")?.let {
            PendingSnooze(it.getString("id"), it.getLong("scope_wall"), it.getLong("boot"),
                it.getLong("scheduled_wall"), it.getLong("scheduled_elapsed"))
        }
        return MonitorControl(
            enabled = json.getBoolean("enabled"), selected = json.getJSONArray("selected").stringSet(),
            scopeStartWall = json.getLong("scope_wall"), bootCount = json.getLong("boot"),
            clockUncertainUntilWall = json.optLong("clock_uncertain_until"),
            sentKeys = json.getJSONArray("sent_keys").stringSet(), pending = pending,
            alertId = if (json.isNull("alert_id")) null else json.getString("alert_id"),
            snoozeOutcome = MonitorSnoozeOutcome.valueOf(json.getString("snooze_outcome")),
            snoozeDelay = if (json.isNull("snooze_delay")) null else json.getLong("snooze_delay")
        )
    }

    fun write(state: MonitorControl) {
        file.write(JSONObject().put("schema", 1).put("enabled", state.enabled)
            .put("selected", JSONArray(state.selected)).put("scope_wall", state.scopeStartWall)
            .put("boot", state.bootCount).put("clock_uncertain_until", state.clockUncertainUntilWall)
            .put("sent_keys", JSONArray(state.sentKeys)).put("alert_id", state.alertId ?: JSONObject.NULL)
            .put("snooze_outcome", state.snoozeOutcome.name)
            .put("snooze_delay", state.snoozeDelay ?: JSONObject.NULL)
            .put("pending", state.pending?.let {
                JSONObject().put("id", it.id).put("scope_wall", it.scopeStartMillis)
                    .put("boot", it.bootCount).put("scheduled_wall", it.scheduledWallMillis)
                    .put("scheduled_elapsed", it.scheduledElapsedMillis)
            } ?: JSONObject.NULL))
    }

    private fun JSONArray.stringSet(): Set<String> = buildSet {
        for (index in 0 until length()) add(getString(index))
    }
}

/** Calls are confined to the worker. Verify commits before a caller submits a notification. */
internal class MonitorAtomicFile(path: File, private val limit: Int) {
    private val file = AtomicFile(path)

    fun read(): JSONObject? = readBytes()?.let { JSONObject(it.toString(Charsets.UTF_8)) }

    private fun readBytes(): ByteArray? {
        val stream = try { file.openRead() }
        catch (missing: FileNotFoundException) {
            if (file.baseFile.exists()) throw missing
            return null
        }
        return stream.use {
            val length = it.channel.size()
            if (length > limit) throw IOException("Monitor file exceeds storage limit")
            val bytes = ByteArray(length.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val count = it.read(bytes, offset, bytes.size - offset)
                if (count <= 0) throw IOException("Incomplete monitor file")
                offset += count
            }
            if (it.read() != -1) throw IOException("Monitor file changed while reading")
            bytes
        }
    }

    fun write(json: JSONObject, syncBeforeFinish: Boolean = true) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > limit) throw IOException("Monitor file exceeds storage limit")
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            if (syncBeforeFinish) stream.fd.sync()
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
        file.finishWrite(stream)
        if (readBytes()?.contentEquals(bytes) != true) throw IOException("Monitor commit did not verify")
    }
}
