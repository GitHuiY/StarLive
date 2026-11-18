package io.github.githuiy.starlive.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
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

internal enum class WithinFontFamily { SANS_SERIF, SERIF, MONOSPACE, CURSIVE }

internal data class WithinTextStyle(
    val fontFamily: WithinFontFamily? = null,
    val fontSizeSp: Int? = null,
    val colorArgb: Int? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false
)

/** Text ranges and image anchors use UTF-16 offsets, like Compose text selection. */
internal data class WithinTextSpan(val start: Int, val end: Int, val style: WithinTextStyle)

internal data class WithinImageAttachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val offset: Int = 0
)

internal const val MAX_WITHIN_IMAGES = 8

internal data class WithinLegacy(
    val domain: WithinDomain,
    val type: WithinRecordType,
    val example: String? = null,
    val source: WithinSource? = null
)

internal data class WithinEssayDraft(
    val body: String,
    val date: LocalDate?,
    val categories: Set<WithinCategory> = emptySet(),
    val spans: List<WithinTextSpan> = emptyList(),
    val images: List<WithinImageAttachment> = emptyList()
)

internal data class WithinEssay(
    val id: String,
    val draft: WithinEssayDraft,
    val legacy: WithinLegacy? = null
)

internal class WithinRepository(context: Context) {
    private val application = context.applicationContext
    private val directory = application.filesDir
    private val file = AtomicFile(File(directory, "starlive-within-v2.json"))
    private val legacyFile = AtomicFile(File(directory, "starlive-within-v1.json"))
    private val imageDirectory = File(directory, "starlive-within-images")

    suspend fun load(): List<WithinEssay> = withContext(Dispatchers.IO) {
        synchronized(fileLock) { readRecords().sortedByDescending { it.draft.date } }
    }

    suspend fun save(recordId: String?, draft: WithinEssayDraft): List<WithinEssay> {
        val content = draft.copy(categories = draft.categories.toSet(), spans = draft.spans.toList(),
            images = draft.images.toList())
        validateDraft(content)
        return updateRecords { records ->
            content.images.forEach { image ->
                val stored = imageFile(image.id)
                if (!stored.isFile || stored.length() !in 1L..MAX_IMAGE_BYTES.toLong()) {
                    throw IOException("图片「${image.name}」无法读取，请移除后重新添加。")
                }
            }
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

    suspend fun importImage(uri: Uri, offset: Int): WithinImageAttachment {
        require(offset >= 0) { "图片位置无效。" }
        val id = UUID.randomUUID().toString()
        val target = imageFile(id)
        return try {
            withContext(Dispatchers.IO) {
                if (!imageDirectory.isDirectory && !imageDirectory.mkdirs() && !imageDirectory.isDirectory) {
                    throw IOException("无法创建随笔图片存储位置。")
                }
                val resolver = application.contentResolver
                try {
                    val input = resolver.openInputStream(uri) ?: throw IOException("图片无法打开。")
                    input.use { source ->
                        target.outputStream().use { destination ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var size = 0
                            while (true) {
                                ensureActive()
                                val count = source.read(buffer)
                                if (count == -1) break
                                size += count
                                require(size <= MAX_IMAGE_BYTES) { "单张图片不能超过 8 MB。" }
                                destination.write(buffer, 0, count)
                            }
                            destination.fd.sync()
                        }
                    }
                } catch (failure: SecurityException) {
                    throw IOException("没有权限读取这张图片，请重新选择或复制。", failure)
                } catch (failure: IOException) {
                    throw IOException("无法读取或保存这张图片，请重新选择或复制。", failure)
                }
                val mimeType = checkImage(target)
                val name = runCatching {
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                }.getOrNull()?.trim()?.take(200)?.takeIf(String::isNotBlank) ?: "图片"
                WithinImageAttachment(id, name, mimeType, offset)
            }
        } catch (failure: Exception) {
            // Cancellation can arrive after the copy, before the attachment reaches the editor.
            withContext(NonCancellable + Dispatchers.IO) { target.delete() }
            throw failure
        }
    }

    suspend fun readImage(image: WithinImageAttachment): ByteArray = withContext(Dispatchers.IO) {
        val stored = imageFile(image.id)
        try {
            if (stored.length() !in 1L..MAX_IMAGE_BYTES.toLong()) throw IOException()
            stored.readBytes()
        } catch (failure: IOException) {
            throw IOException("无法读取图片「${image.name}」。", failure)
        }
    }

    suspend fun discardUnreferencedImages(images: Collection<WithinImageAttachment>) {
        if (images.isEmpty()) return
        withContext(Dispatchers.IO) {
            synchronized(fileLock) {
                val retainedIds = imageIds(readRecords())
                images.forEach { image ->
                    if (image.id !in retainedIds) imageFile(image.id).delete()
                }
            }
        }
    }

    private fun imageFile(id: String): File {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "图片标识无效。" }
        return File(imageDirectory, id)
    }

    private fun checkImage(file: File): String {
        val bytes = file.readBytes()
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val mimeType = options.outMimeType
        require(mimeType != null && mimeType in supportedImageTypes && options.outWidth > 0 && options.outHeight > 0) {
            "无法识别这张图片，请选择 JPEG、PNG、WebP 或 GIF 图片。"
        }
        val longestSide = maxOf(options.outWidth, options.outHeight)
        require(longestSide <= 16_384 && options.outWidth.toLong() * options.outHeight <= 100_000_000) {
            "图片分辨率过高，请缩小后重新添加。"
        }
        var sample = 1
        while (longestSide / sample > 1_024) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalArgumentException("图片内容损坏，请重新选择。")
        bitmap.recycle()
        return mimeType
    }

    private suspend fun updateRecords(update: (MutableList<WithinEssay>) -> Unit): List<WithinEssay> =
        withContext(Dispatchers.IO) {
            synchronized(fileLock) {
                val original = readRecords()
                val records = original.toMutableList()
                update(records)
                if (records != original) {
                    writeRecords(records)
                    (imageIds(original) - imageIds(records)).forEach { id -> imageFile(id).delete() }
                }
                // Sort only the returned list so matching dates retain their creation order on disk.
                records.sortedByDescending { it.draft.date }
            }
        }

    private fun imageIds(records: List<WithinEssay>): Set<String> = buildSet {
        records.forEach { record -> record.draft.images.forEach { add(it.id) } }
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
                check(entry.id.isNotBlank() && ids.add(entry.id))
                check(entry.draft.date != null || entry.legacy != null)
                validateDraft(entry.draft)
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
                },
                spans = optionalObjects("spans") { span ->
                    WithinTextSpan(span.get("start") as Int, span.get("end") as Int, span.readTextStyle())
                },
                images = optionalObjects("images") { image ->
                    WithinImageAttachment(image.get("id") as String, image.get("name") as String,
                        image.get("mimeType") as String, image.get("offset") as Int)
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

    private fun JSONObject.readTextStyle() = WithinTextStyle(
        fontFamily = optionalText("fontFamily")?.let(WithinFontFamily::valueOf),
        fontSizeSp = optionalInt("fontSizeSp"),
        colorArgb = optionalInt("colorArgb"),
        bold = optionalBoolean("bold"),
        italic = optionalBoolean("italic"),
        underline = optionalBoolean("underline")
    )

    private fun validateDraft(draft: WithinEssayDraft) {
        require(draft.body.isNotBlank() || draft.images.isNotEmpty()) { "请填写随笔正文或添加图片。" }
        require(draft.images.size <= MAX_WITHIN_IMAGES) { "每篇随笔最多添加 $MAX_WITHIN_IMAGES 张图片。" }
        val imageIds = mutableSetOf<String>()
        for (image in draft.images) {
            imageFile(image.id) // Checks the ID before it can be used as a filename.
            require(imageIds.add(image.id) && image.name.isNotBlank() && image.mimeType in supportedImageTypes) {
                "随笔图片信息无效。"
            }
            require(image.offset in 0..draft.body.length) { "图片位置无效。" }
        }
        var previousEnd = 0
        for (span in draft.spans) {
            require(span.start >= previousEnd && span.start < span.end && span.end <= draft.body.length) {
                "文字样式范围无效。"
            }
            require(span.style.fontSizeSp == null || span.style.fontSizeSp in 8..96) { "字号须在 8 到 96 之间。" }
            previousEnd = span.end
        }
    }

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
                .put("legacy", legacy)
                .apply {
                    if (record.draft.spans.isNotEmpty()) put("spans", JSONArray(record.draft.spans.map { it.toJson() }))
                    if (record.draft.images.isNotEmpty()) put("images", JSONArray(record.draft.images.map { it.toJson() }))
                })
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

    private fun WithinTextSpan.toJson() = JSONObject()
        .put("start", start)
        .put("end", end)
        .put("fontFamily", style.fontFamily?.name)
        .put("fontSizeSp", style.fontSizeSp)
        .put("colorArgb", style.colorArgb)
        .apply {
            if (style.bold) put("bold", true)
            if (style.italic) put("italic", true)
            if (style.underline) put("underline", true)
        }

    private fun WithinImageAttachment.toJson() = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("mimeType", mimeType)
        .put("offset", offset)

    private fun <T> JSONObject.optionalObjects(key: String, readObject: (JSONObject) -> T): List<T> {
        if (isNull(key)) return emptyList()
        val entries = getJSONArray(key)
        return List(entries.length()) { index -> readObject(entries.getJSONObject(index)) }
    }

    private fun JSONObject.optionalText(key: String): String? = if (isNull(key)) null else get(key) as String
    private fun JSONObject.optionalInt(key: String): Int? = if (isNull(key)) null else get(key) as Int
    private fun JSONObject.optionalBoolean(key: String): Boolean = if (isNull(key)) false else get(key) as Boolean

    private companion object {
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
        val supportedImageTypes = setOf("image/jpeg", "image/png", "image/webp", "image/gif",
            "image/heif", "image/heic", "image/avif")
        // An activity recreation can overlap an in-flight write from the previous instance.
        val fileLock = Any()
    }
}
