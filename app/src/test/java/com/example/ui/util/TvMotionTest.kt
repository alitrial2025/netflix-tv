package com.example.ui.util

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvMotionTest {
    private fun glide(target: Float = 1f) = TargetBasedAnimation(
        animationSpec = TvMotion.carouselSpring(0.005f),
        typeConverter = Float.VectorConverter, initialValue = 0f, targetValue = target
    )

    @Test fun glideStaysUnhurriedAndDoesNotOvershoot() {
        val old = TargetBasedAnimation(
            animationSpec = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = TvMotion.stiffness(550f), visibilityThreshold = 0.005f),
            typeConverter = Float.VectorConverter, initialValue = 0f, targetValue = 1f
        )
        val current = glide()
        assertTrue("The shared glide must not become faster", current.durationNanos >= old.durationNanos)
        assertTrue("Keep the adjustment subtle", current.durationNanos < old.durationNanos * 1.25)
        var previous = 0f
        for (time in 0L..current.durationNanos step 16_000_000L) {
            val position = current.getValueFromNanos(time)
            assertTrue(position >= previous && position <= 1f)
            previous = position
        }
        assertEquals(1f, current.getValueFromNanos(current.durationNanos), 0.001f)
    }

    @Test fun retargetKeepsPositionAndVelocityInsteadOfRestartingAtRest() {
        val first = glide()
        val cut = 120_000_000L
        val position = first.getValueFromNanos(cut)
        val velocity = first.getVelocityVectorFromNanos(cut).value
        val continuation = TargetBasedAnimation(
            animationSpec = TvMotion.carouselSpring(0.005f), typeConverter = Float.VectorConverter,
            initialValue = position, targetValue = 2f,
            initialVelocityVector = AnimationVector1D(velocity)
        )
        assertTrue(velocity > 0f)
        assertEquals(position, continuation.getValueFromNanos(0L), 0.0001f)
        assertEquals(velocity, continuation.getVelocityVectorFromNanos(0L).value, 0.0001f)
        assertTrue(continuation.getValueFromNanos(16_000_000L) > position)
    }

    @Test fun verticalAndHorizontalUseTheSameNormalizedResponse() {
        val row = glide()
        val viewport = glide(400f)
        for (time in 0L..300_000_000L step 16_000_000L) {
            assertEquals(row.getValueFromNanos(time), viewport.getValueFromNanos(time) / 400f, 0.0001f)
        }
        // Border movement retains its existing stiffness, rather than being sped up.
        assertEquals(TvMotion.stiffness(600f), TvMotion.focusRingStiffness(), 0.0001f)
    }
}
