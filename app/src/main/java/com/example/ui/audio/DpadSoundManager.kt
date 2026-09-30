package com.example.ui.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.util.Log
import com.example.R

/** A short, soft navigation pulse. Repeated keys never stack several sounds. */
object DpadSoundManager {
    private val lifecycleLock = Any()
    @Volatile private var soundPool: SoundPool? = null
    @Volatile private var soundId = 0
    @Volatile private var isLoaded = false
    private var lastPlayedMs = 0L
    private var activeStreamId = 0

    // Called off the UI thread: creating the audio service must not delay entry.
    fun init(context: Context) = synchronized(lifecycleLock) {
        if (soundPool != null) return@synchronized
        var createdPool: SoundPool? = null
        try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val pool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(attributes).build()
            createdPool = pool
            soundPool = pool
            pool.setOnLoadCompleteListener { loadedPool, _, status ->
                if (soundPool === loadedPool) isLoaded = status == 0
            }
            soundId = pool.load(context.applicationContext, R.raw.tv_navigation, 1)
        } catch (error: Exception) {
            createdPool?.release()
            soundPool = null
            isLoaded = false
            Log.w("DpadSoundManager", "Navigation audio unavailable", error)
        }
    }

    fun playNavigation(repeatCount: Int = 0) {
        val minimumGap = when {
            repeatCount == 0 -> 75L
            repeatCount < 3 -> 90L
            repeatCount < 6 -> 140L
            else -> 210L
        }
        play(volume = 0.46f, rate = 1f, minimumGapMs = minimumGap)
    }

    fun playConfirm() = play(volume = 0.52f, rate = 0.88f, minimumGapMs = 90L)

    private fun play(volume: Float, rate: Float, minimumGapMs: Long) {
        val pool = soundPool ?: return
        val id = soundId
        if (!isLoaded || id == 0) return
        val now = SystemClock.uptimeMillis()
        if (now - lastPlayedMs < minimumGapMs) return
        try {
            if (activeStreamId != 0) pool.stop(activeStreamId)
            activeStreamId = pool.play(id, volume, volume, 1, 0, rate)
            lastPlayedMs = now
        } catch (error: RuntimeException) {
            Log.w("DpadSoundManager", "Unable to play navigation audio", error)
        }
    }

    fun release() = synchronized(lifecycleLock) {
        val pool = soundPool
        soundPool = null
        isLoaded = false
        soundId = 0
        activeStreamId = 0
        lastPlayedMs = 0L
        pool?.release()
    }
}
