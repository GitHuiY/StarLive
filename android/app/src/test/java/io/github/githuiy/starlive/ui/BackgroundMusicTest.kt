package io.github.githuiy.starlive.ui

import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackgroundMusicTest {
    @Test fun missingTrackReportsErrorWithoutRequestingAudioFocus() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val withoutTrack = object : ContextWrapper(app) {
            override fun getPackageName() = "io.github.githuiy.starlive.withouttrack"
        }
        val audioManager = shadowOf(app.getSystemService(AudioManager::class.java))
        val errors = mutableListOf<String>()
        val music = BackgroundMusic(withoutTrack, isForeground = { true }) { errors.add(it) }

        music.toggle()

        assertEquals(listOf("没有找到背景音乐文件"), errors)
        assertFalse(music.isPlaying)
        assertNull(audioManager.lastAudioFocusRequest)
    }
}
