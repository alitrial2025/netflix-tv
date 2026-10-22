package com.example.playback

import android.content.Context
import android.os.Looper
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.TextOutput
import java.util.ArrayList
import java.security.MessageDigest

object SubtitleTiming {
    const val LIMIT_MS = 10_000
    const val STEP_MS = 250
    fun clamp(offsetMs: Int) = offsetMs.coerceIn(-LIMIT_MS, LIMIT_MS)
    /** A positive value displays captions later; audio/video clocks are never adjusted. */
    fun captionPositionUs(positionUs: Long, offsetMs: Int): Long {
        if (positionUs < 0) return positionUs
        val offsetUs = clamp(offsetMs).toLong() * 1000L
        if (offsetUs < 0 && positionUs > Long.MAX_VALUE + offsetUs) return Long.MAX_VALUE
        return (positionUs - offsetUs).coerceAtLeast(0L)
    }
}

class SubtitleTimingStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("subtitle_timing", Context.MODE_PRIVATE)
    private fun key(account: String, profile: String, title: String) = MessageDigest.getInstance("SHA-256")
        .digest("$account\u0000$profile\u0000$title".toByteArray()).joinToString("") { "%02x".format(it) }
    fun get(account: String, profile: String, title: String) = SubtitleTiming.clamp(preferences.getInt(key(account, profile, title), 0))
    fun put(account: String, profile: String, title: String, value: Int) {
        preferences.edit().putInt(key(account, profile, title), SubtitleTiming.clamp(value)).apply()
    }
}

/** Reuses Media3's decoders/track selection; only text rendering receives a shifted clock. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SubtitleTimingRenderersFactory(context: Context, private val delayMs: () -> Int) : DefaultRenderersFactory(context) {
    override fun buildTextRenderers(context: Context, output: TextOutput, outputLooper: Looper,
                                    extensionRendererMode: Int, out: ArrayList<Renderer>) {
        val start = out.size
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
        for (index in start until out.size) {
            val delegate = out[index]
            out[index] = SubtitleTimingRenderer(delegate, delayMs)
        }
    }
}

/** Only render's media clock is shifted; decoder lifecycle and elapsed realtime stay untouched. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class SubtitleTimingRenderer(private val delegate: Renderer, private val delayMs: () -> Int) : Renderer by delegate {
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        delegate.render(SubtitleTiming.captionPositionUs(positionUs, delayMs()), elapsedRealtimeUs)
    }
}
