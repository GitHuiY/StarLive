package io.github.githuiy.starlive.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.githuiy.starlive.data.WithinEssayDraft
import io.github.githuiy.starlive.data.WithinImageAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private sealed interface WithinImagePreview {
    data object Loading : WithinImagePreview
    data object Unavailable : WithinImagePreview
    data class Ready(val bitmap: ImageBitmap) : WithinImagePreview
}

private val WithinImageReads = Semaphore(2)

@Composable
internal fun WithinAttachmentImage(
    attachment: WithinImageAttachment,
    loadImage: suspend (WithinImageAttachment) -> ByteArray,
    modifier: Modifier = Modifier
) {
    val readImage by rememberUpdatedState(loadImage)
    val preview by produceState<WithinImagePreview>(WithinImagePreview.Loading, attachment.id) {
        value = WithinImagePreview.Loading
        value = try {
            WithinImageReads.withPermit {
                withContext(Dispatchers.IO) { decodeWithinImage(readImage(attachment)) }
            }?.let { WithinImagePreview.Ready(it) } ?: WithinImagePreview.Unavailable
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            WithinImagePreview.Unavailable
        }
    }
    when (val result = preview) {
        WithinImagePreview.Loading -> Text("正在读取图片…", modifier, color = Muted,
            style = MaterialTheme.typography.bodySmall)
        WithinImagePreview.Unavailable -> Text("图片不可用 · ${attachment.name}", modifier,
            color = Ink.copy(alpha = .85f), style = MaterialTheme.typography.bodySmall)
        is WithinImagePreview.Ready -> BoxWithConstraints(modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center) {
            val bitmap = result.bitmap
            val height = (maxWidth * (bitmap.height.toFloat() / bitmap.width)).coerceIn(1.dp, 360.dp)
            Image(bitmap, contentDescription = attachment.name, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(height).testTag("within_image_${attachment.id}"))
        }
    }
}

@Composable
internal fun WithinEssayPreview(draft: WithinEssayDraft) {
    if (draft.body.isBlank() && draft.images.isNotEmpty()) {
        val summary = draft.images.singleOrNull()?.name ?: "${draft.images.size} 张图片"
        Text(summary, color = Ink, style = MaterialTheme.typography.bodyLarge,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    } else {
        Text(withinStyledText(draft.body, draft.spans), color = Ink, style = MaterialTheme.typography.bodyLarge,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun WithinEssayContent(draft: WithinEssayDraft, loadImage: suspend (WithinImageAttachment) -> ByteArray) {
    val bodyTag = Modifier.testTag("within_essay_body")
    val images = remember(draft.images) { draft.images.sortedBy { it.offset } }
    if (images.isEmpty()) {
        WithinEssayText(draft, 0, draft.body.length, bodyTag)
    } else {
        Column(bodyTag.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            var start = 0
            images.forEach { attachment ->
                val end = attachment.offset.coerceIn(start, draft.body.length)
                key(attachment.id) {
                    if (end > start) WithinEssayText(draft, start, end)
                    WithinAttachmentImage(attachment, loadImage)
                }
                start = end
            }
            if (start < draft.body.length) WithinEssayText(draft, start, draft.body.length)
        }
    }
}

@Composable
private fun WithinEssayText(draft: WithinEssayDraft, start: Int, end: Int, modifier: Modifier = Modifier) {
    Text(withinStyledText(draft.body, draft.spans, start, end), modifier, color = Ink,
        style = MaterialTheme.typography.bodyLarge)
}

private fun decodeWithinImage(bytes: ByteArray): ImageBitmap? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    val longest = maxOf(options.outWidth, options.outHeight)
    var sample = 1
    while (longest / sample > 1280) sample *= 2
    options.inSampleSize = sample
    options.inJustDecodeBounds = false
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}
