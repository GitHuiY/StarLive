package io.github.githuiy.starlive.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.time.LocalDate
import java.util.UUID

// These names identify the original profile metadata in both file versions.
internal enum class WithinDomain { ABILITY, LEARNING, RELATIONSHIPS, INTERESTS, WELLBEING, VALUES }
internal enum class WithinRecordType { TRAIT, STRENGTH, IMPROVEMENT, EXPLORATION }
internal enum class WithinSource { SELF, FEEDBACK, AI }

internal enum class WithinCategory { SELF, WORLD, MOMENT, ECHO, FOOTPRINT, BEYOND }

internal data class WithinLegacy(
    val domain: WithinDomain,
    val type: WithinRecordType,
    val example: String? = null,
    val source: WithinSource? = null
)

internal data class WithinEssayDraft(
    val body: String,
    val date: LocalDate?,
    val categories: Set<WithinCategory> = emptySet()
)

internal data class WithinEssay(
    val id: String,
    val draft: WithinEssayDraft,
    val legacy: WithinLegacy? = null
)

internal class WithinRepository(context: Context) {
    private val directory = context.applicationContext.filesDir
    private val file = AtomicFile(File(directory, "starlive-within-v2.json"))
    private val legacyFile = AtomicFile(File(directory, "starlive-within-v1.json"))

    suspend fun load(): List<WithinEssay> = withContext(Dispatchers.IO) {
        synchronized(fileLock) { readRecords().sortedByDescending { it.draft.date } }
    }

    suspend fun save(recordId: String?, draft: WithinEssayDraft): List<WithinEssay> {
        require(draft.body.isNotBlank()) { "请填写随笔正文" }
        val content = draft.copy(categories = draft.categories.toSet())
        return updateRecords { records ->
            if (recordId == null) {
                requireNotNull(content.date) { "请选择记录日期" }
                records.add(0, WithinEssay(UUID.randomUUID().toString(), content))
            } else {
                val index = records.indexOfFirst { it.id == recordId }
                check(index >= 0) { "这篇随笔已不存在" }
                require(content.date != null || records[index].legacy != null) { "请选择记录日期" }
                records[index] = records[index].copy(draft = content)
            }
        }
    }

    suspend fun delete(recordId: String): List<WithinEssay> = updateRecords { records ->
        records.removeAll { it.id == recordId }
    }

    private suspend fun updateRecords(update: (MutableList<WithinEssay>) -> Unit): List<WithinEssay> =
        withContext(Dispatchers.IO) {
            synchronized(fileLock) {
                val original = readRecords()
                val records = original.toMutableList()
                update(records)
                if (records != original) writeRecords(records)
                // Sort only the returned list so matching dates retain their creation order on disk.
                records.sortedByDescending { it.draft.date }
            }
        }

    private fun AtomicFile.readBytes(): ByteArray? = try {
        readFully()
    } catch (missing: FileNotFoundException) {
        if (baseFile.exists() || File(baseFile.path + ".bak").exists()) throw missing
        null
    }

    private fun readRecords(): List<WithinEssay> {
        try {
            // The v2 file is authoritative even when it contains zero essays.
            val current = file.readBytes()
            val bytes = current ?: legacyFile.readBytes() ?: return emptyList()
            val fromProfile = current == null
            val version = if (fromProfile) 1 else 2
            val json = JSONTokener(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString())
            val root = json.nextValue() as JSONObject
            check(json.nextClean() == '\u0000' && root.get("version") == version)
            val records = root.getJSONArray("records")
            val ids = mutableSetOf<String>()
            return List(records.length()) { index ->
                val record = records.getJSONObject(index)
                val entry = if (fromProfile) record.readProfile() else record.readEssay()
                check(entry.id.isNotBlank() && ids.add(entry.id) && entry.draft.body.isNotBlank())
                check(entry.draft.date != null || entry.legacy != null)
                entry
            }
        } catch (failure: Exception) {
            throw IOException("无法读取 Within 记录，原文件已保留。", failure)
        }
    }

    private fun JSONObject.readProfile(): WithinEssay = WithinEssay(
        id = get("id") as String,
        draft = WithinEssayDraft(
            body = get("description") as String,
            date = optionalText("recordedOn")?.let(LocalDate::parse)
        ),
        legacy = readProfileMetadata()
    )

    private fun JSONObject.readEssay(): WithinEssay {
        val categories = getJSONArray("categories")
        return WithinEssay(
            id = get("id") as String,
            draft = WithinEssayDraft(
                body = get("body") as String,
                date = optionalText("date")?.let(LocalDate::parse),
                categories = buildSet {
                    for (index in 0 until categories.length()) {
                        add(WithinCategory.valueOf(categories.get(index) as String))
                    }
                }
            ),
            legacy = if (isNull("legacy")) null else getJSONObject("legacy").readProfileMetadata()
        )
    }

    private fun JSONObject.readProfileMetadata() = WithinLegacy(
        domain = WithinDomain.valueOf(get("domain") as String),
        type = WithinRecordType.valueOf(get("type") as String),
        example = optionalText("example"),
        source = optionalText("source")?.let(WithinSource::valueOf)
    )

    private fun writeRecords(records: List<WithinEssay>) {
        val entries = JSONArray()
        records.forEach { record ->
            val legacy = record.legacy?.let {
                JSONObject()
                    .put("domain", it.domain.name)
                    .put("type", it.type.name)
                    .put("example", it.example)
                    .put("source", it.source?.name)
            }
            entries.put(JSONObject()
                .put("id", record.id)
                .put("body", record.draft.body)
                .put("date", record.draft.date?.toString())
                .put("categories", JSONArray(record.draft.categories.map { it.name }))
                .put("legacy", legacy))
        }
        val bytes = JSONObject().put("version", 2).put("records", entries).toString().toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
        file.finishWrite(stream)
        if (file.readBytes()?.contentEquals(bytes) != true) throw IOException("无法确认 Within 记录已保存。")
    }

    private fun JSONObject.optionalText(key: String): String? = if (isNull(key)) null else get(key) as String

    private companion object {
        // An activity recreation can overlap an in-flight write from the previous instance.
        val fileLock = Any()
    }
}
