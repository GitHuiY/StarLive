package io.github.githuiy.starlive.ui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.IOException

@Composable
internal fun rememberBackgroundMusic(onError: (String) -> Unit): BackgroundMusic {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestOnError by rememberUpdatedState(onError)
    val music = remember(context, lifecycle) {
        BackgroundMusic(context, { lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }) {
            latestOnError(it)
        }
    }
    DisposableEffect(context, lifecycle, music) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) music.pause()
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) music.pause()
            }
        }
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
            music.release()
        }
    }
    return music
}

internal class BackgroundMusic(private val context: Context, private val isForeground: () -> Boolean,
    private val onError: (String) -> Unit) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private var audioFocus: AudioFocusRequest? = null
    private var player: MediaPlayer? = null
    private var prepared = false
    private var playRequested = false

    var isPlaying by mutableStateOf(false)
        private set

    fun toggle() {
        if (playRequested) {
            pause()
            return
        }
        if (!isForeground()) return
        playRequested = true
        try {
            if (!acquireFocus()) {
                playRequested = false
                onError("暂时无法播放背景音乐，请稍后重试")
                return
            }
            val current = player
            if (current == null) preparePlayer() else if (prepared) start(current)
        } catch (_: IOException) {
            failPlayback()
        } catch (_: RuntimeException) {
            failPlayback()
        }
    }

    fun pause() {
        playRequested = false
        if (isPlaying) {
            try {
                player?.pause()
            } catch (_: IllegalStateException) {
                releasePlayer()
            }
        }
        isPlaying = false
        abandonFocus()
    }

    fun release() {
        pause()
        releasePlayer()
    }

    @SuppressLint("DiscouragedApi")
    private fun preparePlayer() {
        // Resolve the optional audio at runtime so a build without the local track can still run.
        val sound = context.resources.getIdentifier("river_flows_in_you", "raw", context.packageName)
        if (sound == 0) {
            pause()
            onError("没有找到背景音乐文件")
            return
        }
        val media = MediaPlayer()
        player = media
        media.setAudioAttributes(attributes)
        val descriptor = context.resources.openRawResourceFd(sound)
            ?: throw IOException("Can't open bundled background music")
        descriptor.use { source ->
            media.setDataSource(source.fileDescriptor, source.startOffset, source.length)
        }
        media.setOnPreparedListener { ready ->
            if (ready === player) {
                prepared = true
                try {
                    ready.isLooping = true
                    if (playRequested && isForeground()) start(ready) else pause()
                } catch (_: IllegalStateException) {
                    failPlayback()
                }
            }
        }
        media.setOnErrorListener { failed, _, _ ->
            if (failed === player) failPlayback()
            true
        }
        media.prepareAsync()
    }

    private fun start(media: MediaPlayer) {
        media.start()
        isPlaying = true
    }

    private fun acquireFocus(): Boolean {
        lateinit var request: AudioFocusRequest
        request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (audioFocus === request && change < 0) pause()
            }, Handler(Looper.getMainLooper()))
            .build()
        if (audioManager?.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        audioFocus = request
        return true
    }

    private fun abandonFocus() {
        val request = audioFocus ?: return
        audioFocus = null
        audioManager?.abandonAudioFocusRequest(request)
    }

    private fun releasePlayer() {
        val media = player
        player = null
        prepared = false
        media?.release()
    }

    private fun failPlayback() {
        val notify = playRequested && isForeground()
        releasePlayer()
        pause()
        if (notify) onError("背景音乐播放失败，请重试")
    }
}
