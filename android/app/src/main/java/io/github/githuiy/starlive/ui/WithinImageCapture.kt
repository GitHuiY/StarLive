package io.github.githuiy.starlive.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WithinImageFileProvider : FileProvider()

private const val WithinTemporaryDirectory = "within-images"
private const val WithinTemporaryRoot = "within"
private const val WithinMaximumPhotoBytes = 8L * 1024 * 1024

private fun withinTemporaryDirectory(context: Context): File = File(context.cacheDir, WithinTemporaryDirectory)

private fun withinTemporaryFile(context: Context, uri: Uri): File? {
    if (uri.scheme != "content" || uri.authority != "${context.packageName}.within.images") return null
    val segments = uri.pathSegments
    if (segments.size != 2 || segments[0] != WithinTemporaryRoot) return null
    val name = segments[1]
    if (!name.startsWith("camera-") && !name.startsWith("doodle-")) return null
    val directory = withinTemporaryDirectory(context).canonicalFile
    return File(directory, name).canonicalFile.takeIf { it.parentFile == directory }
}

private fun createWithinTemporaryFile(context: Context, prefix: String, suffix: String): File {
    val directory = withinTemporaryDirectory(context)
    if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
        throw IOException("无法准备图片，请重试。")
    }
    return File(directory, "$prefix-${UUID.randomUUID()}.$suffix").apply {
        if (!createNewFile()) throw IOException("无法准备图片，请重试。")
    }
}

private fun withinTemporaryUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.within.images", file)

internal suspend fun discardWithinTemporaryImage(context: Context, uri: Uri) {
    withContext(NonCancellable + Dispatchers.IO) { withinTemporaryFile(context, uri)?.delete() }
}

internal suspend fun writeWithinDoodleImage(context: Context, bitmap: Bitmap): Uri {
    var temporary: File? = null
    return try {
        withContext(Dispatchers.IO) {
            val file = createWithinTemporaryFile(context, "doodle", "png")
            temporary = file
            file.outputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw IOException("无法保存涂鸦，请重试。")
                }
            }
            if (file.length() > WithinMaximumPhotoBytes) throw IOException("这张涂鸦超过 8 MB，请减少笔画后重试。")
            withinTemporaryUri(context, file)
        }
    } catch (failure: Exception) {
        withContext(NonCancellable + Dispatchers.IO) { temporary?.delete() }
        throw failure
    }
}

@Composable
internal fun rememberWithinPhotoCapture(onCaptured: (Uri) -> Unit, onError: (String) -> Unit): () -> Unit {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val captured by rememberUpdatedState(onCaptured)
    val reportError by rememberUpdatedState(onError)
    var pendingUri by rememberSaveable { mutableStateOf<String?>(null) }
    var preparing by remember { mutableStateOf(false) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { successful ->
        val uri = pendingUri?.let(Uri::parse)
        pendingUri = null
        if (uri != null) {
            preparing = true
            scope.launch {
                var handedOff = false
                try {
                    if (successful) {
                        withContext(Dispatchers.IO) {
                            val file = withinTemporaryFile(context, uri)
                            if (file == null || !file.isFile || file.length() == 0L) {
                                throw IOException("相机没有返回照片，请重试。")
                            }
                            if (file.length() > WithinMaximumPhotoBytes) {
                                throw IOException("拍摄的照片超过 8 MB，请调整相机分辨率后重试。")
                            }
                        }
                        captured(uri)
                        handedOff = true
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    reportError(failure.message ?: "无法读取拍摄的照片，请重试。")
                } finally {
                    if (!handedOff) discardWithinTemporaryImage(context, uri)
                    preparing = false
                }
            }
        }
    }

    return {
        if (!preparing && pendingUri == null) {
            preparing = true
            scope.launch {
                var temporary: File? = null
                try {
                    val uri = withContext(Dispatchers.IO) {
                        createWithinTemporaryFile(context, "camera", "jpg").also { temporary = it }
                            .let { withinTemporaryUri(context, it) }
                    }
                    pendingUri = uri.toString()
                    camera.launch(uri)
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable + Dispatchers.IO) { temporary?.delete() }
                    pendingUri = null
                    throw cancelled
                } catch (_: ActivityNotFoundException) {
                    withContext(NonCancellable + Dispatchers.IO) { temporary?.delete() }
                    pendingUri = null
                    reportError("没有找到可用的相机。")
                } catch (failure: Exception) {
                    withContext(NonCancellable + Dispatchers.IO) { temporary?.delete() }
                    pendingUri = null
                    reportError(failure.message ?: "无法打开相机，请重试。")
                } finally {
                    preparing = false
                }
            }
        }
    }
}
