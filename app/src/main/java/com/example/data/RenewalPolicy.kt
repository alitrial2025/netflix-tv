package com.example.data

/** Paid time remains 30 days; the fixed renewal allowance cannot be extended by a label. */
object RenewalPolicy {
    const val DAY_MS = 86_400_000L
    const val GRACE_MS = 2 * DAY_MS
    fun accessEndsAt(expiresAt: Long): Long = if (expiresAt <= 0L) 0L else
        if (expiresAt > Long.MAX_VALUE - GRACE_MS) Long.MAX_VALUE else expiresAt + GRACE_MS
    fun grantsAccess(status: String, expiresAt: Long, now: Long): Boolean =
        (status.equals("ACTIVE", true) || status.equals("GRACE_PERIOD", true)) &&
            expiresAt > 0L && now < accessEndsAt(expiresAt)
    fun reminderDue(expiresAt: Long, now: Long): Boolean = expiresAt > 0L &&
        now >= expiresAt && now - expiresAt >= DAY_MS && now < accessEndsAt(expiresAt)
}
