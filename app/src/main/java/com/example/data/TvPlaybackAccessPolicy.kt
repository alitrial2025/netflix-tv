package com.example.data

import com.example.model.Movie
import com.example.model.UserSubscription

/** A missing playback proof does not change the account's paid plan. */
internal object TvPlaybackAccessPolicy {
    enum class Decision { UPGRADE, VERIFY, ALLOW }

    fun decide(subscription: UserSubscription, movie: Movie, authenticated: Boolean,
        leaseVerified: Boolean = false): Decision {
        if (subscription.isMovieLocked(movie.id, movie.year, isTvDevice = true, movieTitle = movie.title))
            return Decision.UPGRADE
        return if (authenticated && leaseVerified) Decision.ALLOW else Decision.VERIFY
    }

    fun verificationMessage(error: Throwable): String {
        val restriction = generateSequence(error) { it.cause }.take(16)
            .filterIsInstance<DeviceAccessException>().firstOrNull()
        return restriction?.message ?: "We couldn't verify your membership and screens. Reconnect and retry playback."
    }
}
