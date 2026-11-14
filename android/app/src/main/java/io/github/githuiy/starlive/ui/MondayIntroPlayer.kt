package io.github.githuiy.starlive.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface as ComposeSurface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.githuiy.starlive.BuildConfig
import io.github.githuiy.starlive.R
import java.io.IOException
import kotlinx.coroutines.delay

private const val FirstFrameTimeoutMillis = 3_500L
private const val PlaybackTimeoutMillis = 12_000L

@Composable
internal fun MondayIntroPlayer(modifier: Modifier = Modifier, onFinished: (completed: Boolean) -> Unit) {
    val onFinishedLatest by rememberUpdatedState(onFinished)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var firstFrame by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }

    fun finish(completed: Boolean = false, reason: String) {
        if (finished) return
        finished = true
        if (BuildConfig.DEBUG) Log.d("StarLiveIntro", reason)
        onFinishedLatest(completed)
    }

    if (finished) return

    BackHandler { finish(reason = "back") }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                finish(reason = "backgrounded")
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(firstFrame) {
        delay(if (firstFrame) PlaybackTimeoutMillis else FirstFrameTimeoutMillis)
        finish(reason = "playback timeout")
    }

    val skipInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout).union(WindowInsets.navigationBars)
    Box(modifier.fillMaxSize()
        .testTag("monday_intro")
        .background(Night)
        .pointerInput(Unit) { detectTapGestures { } }) {
        AndroidView(
            factory = { context ->
                IntroTextureView(context, onFirstFrame = {
                    if (!finished) {
                        firstFrame = true
                        if (BuildConfig.DEBUG) Log.d("StarLiveIntro", "first frame")
                    }
                }, onFinished = { completed ->
                    finish(completed, if (completed) "completed" else "playback unavailable")
                })
            },
            modifier = Modifier.fillMaxSize().graphicsLayer {
                // A fully transparent graphics layer can skip drawing and
                // prevent TextureView from ever creating its surface.
                alpha = if (firstFrame) 1f else .001f
            },
            onRelease = IntroTextureView::releasePlayback
        )
        ComposeSurface(
            onClick = { finish(reason = "skipped") },
            shape = RoundedCornerShape(24.dp),
            color = Night.copy(alpha = .86f),
            contentColor = Ink,
            modifier = Modifier.align(Alignment.TopEnd)
                .windowInsetsPadding(skipInsets)
                .padding(top = 10.dp, end = 16.dp)
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .testTag("intro_skip")
        ) {
            Box(Modifier.padding(horizontal = 15.dp), contentAlignment = Alignment.Center) {
                Text("跳过")
            }
        }
    }
}

private class IntroTextureView(
    context: Context,
    private val onFirstFrame: () -> Unit,
    private val onFinished: (completed: Boolean) -> Unit
) : TextureView(context), TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var started = false
    private var startingTimestamp = 0L
    private var firstFrame = false
    private var released = false

    init {
        isOpaque = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        if (released || player != null) return
        try {
            surface = Surface(texture)
            val playback = MediaPlayer()
            player = playback
            playback.setSurface(surface)
            playback.setOnVideoSizeChangedListener { current, w, h ->
                if (current == player) {
                    videoWidth = w
                    videoHeight = h
                    fitVideo()
                }
            }
            playback.setOnPreparedListener { current ->
                if (current == player) {
                    try {
                        videoWidth = current.videoWidth
                        videoHeight = current.videoHeight
                        fitVideo()
                        current.setVolume(0f, 0f)
                        startingTimestamp = texture.timestamp
                        current.start()
                        started = true
                    } catch (_: IllegalStateException) {
                        finishPlayback()
                    }
                }
            }
            playback.setOnCompletionListener { if (it == player) finishPlayback(completed = firstFrame) }
            playback.setOnErrorListener { current, _, _ ->
                if (current == player) finishPlayback()
                true
            }
            playback.setOnInfoListener { current, what, _ ->
                val firstVideoFrame = current == player && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START
                if (firstVideoFrame && !firstFrame) {
                    // Wait one display frame so TextureView can latch the decoded buffer.
                    postOnAnimation { if (current == player) showFrame() }
                }
                firstVideoFrame
            }
            resources.openRawResourceFd(R.raw.monday_intro).use { descriptor ->
                playback.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
            }
            playback.prepareAsync()
        } catch (_: IOException) {
            finishPlayback()
        } catch (_: RuntimeException) {
            finishPlayback()
        }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        fitVideo()
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        if (!firstFrame && started && videoWidth > 0 && videoHeight > 0 &&
            texture.timestamp != startingTimestamp
        ) {
            showFrame()
        }
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        if (player != null) finishPlayback()
        return true
    }

    private fun fitVideo() {
        if (width <= 0 || height <= 0 || videoWidth <= 0 || videoHeight <= 0) return
        val factor = minOf(width.toFloat() / videoWidth, height.toFloat() / videoHeight)
        setTransform(Matrix().apply {
            setScale(videoWidth * factor / width, videoHeight * factor / height, width / 2f, height / 2f)
        })
    }

    private fun showFrame() {
        if (firstFrame) return
        firstFrame = true
        onFirstFrame()
    }

    private fun finishPlayback(completed: Boolean = false) {
        releasePlayback()
        onFinished(completed)
    }

    fun releasePlayback() {
        released = true
        started = false
        val activePlayer = player
        player = null
        activePlayer?.setOnPreparedListener(null)
        activePlayer?.setOnVideoSizeChangedListener(null)
        activePlayer?.setOnCompletionListener(null)
        activePlayer?.setOnErrorListener(null)
        activePlayer?.setOnInfoListener(null)
        activePlayer?.release()
        surface?.release()
        surface = null
    }
}
