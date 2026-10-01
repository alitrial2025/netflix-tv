package com.example.model

import androidx.compose.runtime.Immutable

/**
 * Tier enum — the four billing plans.
 *
 * `priority` is used to sort plans in the upgrade modal: higher = more featured.
 * `priceUsd` is a raw Double so the UI can localize the currency via
 * [java.text.NumberFormat.getCurrencyInstance] instead of a hardcoded "$10.00"
 * String (which previously prevented non-USD locales from rendering correctly).
 */
@Immutable
enum class SubscriptionTier(
    val displayName: String,
    val priceUsd: Double,
    val maxScreens: Int,
    val maxDownloadQuality: String,
    val hasAds: Boolean,
    val hasLiveContent: Boolean,
    val priority: Int
) {
    GUEST(displayName = "Guest", priceUsd = 0.0, maxScreens = 0, maxDownloadQuality = "None",
        hasAds = true, hasLiveContent = false, priority = 0),
    MOBILE(displayName = "Mobile", priceUsd = 2.0, maxScreens = 1, maxDownloadQuality = "SD",
        hasAds = true, hasLiveContent = false, priority = 1),
    BASIC(displayName = "Basic", priceUsd = 6.0, maxScreens = 1, maxDownloadQuality = "HD",
        hasAds = true, hasLiveContent = false, priority = 2),
    STANDARD(displayName = "Standard", priceUsd = 10.0, maxScreens = 2, maxDownloadQuality = "Full HD",
        hasAds = false, hasLiveContent = true, priority = 3),
    PREMIUM(displayName = "Premium", priceUsd = 14.0, maxScreens = 4, maxDownloadQuality = "4K HDR",
        hasAds = false, hasLiveContent = true, priority = 4);

    companion object {
        /** Resolve a planId (e.g. "plan_standard", "plan_premium") to a tier. */
        fun fromPlanId(planId: String?): SubscriptionTier {
            if (planId.isNullOrBlank()) return GUEST
            val normalized = planId.trim().lowercase()
            return when {
                normalized == "plan_guest" || normalized == "guest" -> GUEST
                normalized == "plan_mobile" || normalized == "mobile" -> MOBILE
                normalized == "plan_basic" || normalized == "basic" -> BASIC
                normalized == "plan_standard" || normalized == "standard" -> STANDARD
                normalized == "plan_premium" || normalized == "premium" -> PREMIUM
                else -> GUEST
            }
        }
    }
}

/**
 * Subscription status — the billing-side lifecycle.
 *
 * Only ACTIVE with a valid expiry grants access in either app.
 * The other labels are retained for reading existing subscription records.
 */
@Immutable
enum class SubscriptionStatus {
    ACTIVE,
    TRIAL,
    GRACE_PERIOD,
    PAST_DUE,
    CANCELLED,
    EXPIRED,
    PENDING_ACTIVATION,
    SUSPENDED;

    companion object {
        fun fromString(raw: String?): SubscriptionStatus {
            // Security: an unknown / blank status defaults to
            // PENDING_ACTIVATION rather than ACTIVE. Previously the
            // blanket fallback to ACTIVE meant a backend bug or a
            // malicious Firestore write with `status = "🐛"` would
            // silently grant free access. PENDING means the server has
            // not confirmed the user's plan, so the user sees an
            // upgrade prompt until the server pushes a real status.
            if (raw.isNullOrBlank()) return PENDING_ACTIVATION
            return when (raw.trim().uppercase()) {
                "ACTIVE" -> ACTIVE
                "TRIAL", "FREE_TRIAL" -> TRIAL
                "GRACE_PERIOD", "GRACE" -> GRACE_PERIOD
                "PAST_DUE", "PASTDUE", "PAYMENT_FAILED" -> PAST_DUE
                "CANCELLED", "CANCELED" -> CANCELLED
                "EXPIRED" -> EXPIRED
                "PENDING", "PENDING_ACTIVATION" -> PENDING_ACTIVATION
                "SUSPENDED", "FRAUD" -> SUSPENDED
                else -> PENDING_ACTIVATION
            }
        }
    }
}

/**
 * Payment method — minimal identifier; the actual PAN is on the server.
 *
 * `lastFour` is the only PII we ever store or display client-side; the
 * network tokenization flow keeps the full PAN off-device.
 */
@Immutable
enum class PaymentMethod(val displayName: String) {
    MPESA("M-Pesa"),
    CARD("Credit / Debit Card"),
    PAYPAL("PayPal"),
    PLAY_BILLING("Google Play"),
    UNKNOWN("Unknown");

    companion object {
        fun fromString(raw: String?): PaymentMethod {
            if (raw.isNullOrBlank()) return UNKNOWN
            return when (raw.trim().uppercase()) {
                "MPESA", "M-PESA", "M_PESA" -> MPESA
                "CARD", "VISA", "MASTERCARD", "AMEX" -> CARD
                "PAYPAL" -> PAYPAL
                "PLAY", "GOOGLE_PLAY", "PLAY_BILLING" -> PLAY_BILLING
                else -> UNKNOWN
            }
        }
    }
}

@Immutable
data class SubscriptionPlan(
    val id: String,
    val name: String,
    val priceKes: Int,
    /** Pre-formatted display string (e.g. "$10.00"). Kept for back-compat with
     *  any UI that still shows this verbatim. Prefer [priceUsdRaw] for new
     *  code paths so currency can be localized. */
    val priceUsd: String,
    /** Raw USD amount as a Double — locale-independent. The UI should format
     *  this through [java.text.NumberFormat.getCurrencyInstance] per locale. */
    val priceUsdRaw: Double = priceUsd.replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: 0.0,
    val quality: String,
    val resolution: String,
    val supportedDevices: String,
    val screens: Int,
    val downloadDevices: Int,
    val spatialAudio: Boolean,
    val isPopular: Boolean = false,
    /** Tier enum — the canonical plan classification. UI can still use
     *  [name] for display. */
    val tier: SubscriptionTier = SubscriptionTier.fromPlanId(id)
) {
    val durationDays: Int get() = 30
    val maxVideoHeight: Int get() = when (id) {
        "plan_mobile" -> 480; "plan_basic" -> 720; "plan_standard" -> 1080
        "plan_premium" -> 2160; else -> 0
    }
    val maxDownloads: Int get() = when (id) {
        "plan_mobile" -> 3; "plan_basic" -> 5; "plan_standard" -> 25
        "plan_premium" -> 999; else -> 0
    }
    val smartNextEpisode: Boolean get() = id == "plan_standard" || id == "plan_premium"
    val downloadsForYou: Boolean get() = id == "plan_premium"
    val clips: Boolean get() = id == "plan_premium"
    val games: Boolean get() = id == "plan_standard" || id == "plan_premium"
}


object SubscriptionPlans {
    val PLANS = listOf(
        SubscriptionPlan(
            id = "plan_mobile",
            name = "Mobile",
            priceKes = 150,
            priceUsd = "$2.00",
            quality = "Good",
            resolution = "480p (SD)",
            supportedDevices = "Mobile phone, tablet",
            screens = 1,
            downloadDevices = 1,
            spatialAudio = false,
            isPopular = false
        ),
        SubscriptionPlan(
            id = "plan_basic",
            name = "Basic",
            priceKes = 550,
            priceUsd = "$6.00",
            quality = "Good",
            resolution = "720p (HD)",
            supportedDevices = "Android TV, mobile phone, tablet",
            screens = 1,
            downloadDevices = 1,
            spatialAudio = false,
            isPopular = false
        ),
        SubscriptionPlan(
            id = "plan_standard",
            name = "Standard",
            priceKes = 950,
            priceUsd = "$10.00",
            quality = "Great",
            resolution = "1080p (Full HD)",
            supportedDevices = "Android TV, mobile phone, tablet",
            screens = 2,
            downloadDevices = 2,
            spatialAudio = false,
            isPopular = true
        ),
        SubscriptionPlan(
            id = "plan_premium",
            name = "Premium",
            priceKes = 1350,
            priceUsd = "$14.00",
            quality = "Best",
            resolution = "Up to 4K + HDR",
            supportedDevices = "Android TV, mobile phone, tablet",
            screens = 4,
            downloadDevices = 6,
            spatialAudio = true,
            isPopular = false
        )
    )

    fun getById(id: String): SubscriptionPlan {
        return PLANS.find { it.id == id } ?: PLANS[2]
    }
}

/**
 * The user's current subscription state.
 *
 * `expiresAt` is stored as a UTC epoch-millis Long (NOT LocalDateTime) to
 * avoid the time-zone / DST bugs the audit flagged. Every clock comparison
 * goes through [nowMillis] (a single injection point) so tests can swap a
 * fake clock without rewiring call sites.
 *
 * Backwards-compat: the old constructor (status, planId, planName, expiresAt)
 * still works; the new fields default to safe values (no trial, no grace
 * period, not cancelled, etc.).
 */
@Immutable
data class UserSubscription(
    val status: String = "NONE", // NONE, PENDING, ACTIVE, EXPIRED, GRACE_PERIOD, TRIAL, PAST_DUE, CANCELLED, SUSPENDED
    val planId: String = "plan_guest",
    val planName: String = "Guest",
    val amount: Int = 0,
    val currency: String = "KES",
    val paymentReference: String = "",
    val mpesaReceipt: String = "",
    val subscribedAt: Long = 0L,
    val expiresAt: Long = 0L,
    /** When the user entered their current billing period (UTC epoch ms). */
    val renewsAt: Long? = null,
    /** When the trial ends (UTC epoch ms); null if not on a trial. */
    val trialEndsAt: Long? = null,
    /** When the user first subscribed (UTC epoch ms). */
    val startedAt: Long = 0L,
    /** Last payment method the user used. */
    val paymentMethod: String? = null,
    /** Last time the server confirmed the subscription (UTC epoch ms). */
    val lastVerifiedAt: Long? = null,
    /** Monthly M-Pesa payments require the customer to renew on their phone. */
    val autoRenew: Boolean = false,
    /** Server-reported cancellation reason (only set when [status] is CANCELLED). */
    val cancellationReason: String? = null,
    /** End of the grace period (UTC epoch ms); null if not in grace period. */
    val gracePeriodEndsAt: Long? = null
) {
    val isGuest: Boolean
        get() = planId.equals("plan_guest", ignoreCase = true) ||
                status.equals("NONE", ignoreCase = true) ||
                tier == SubscriptionTier.GUEST

    /**
     * `true` when the user is entitled to stream full content.
     *
     * Both apps require a known paid plan, ACTIVE status and an expiry.
     * A status label alone cannot grant a monthly membership.
     */
    val isActive: Boolean
        get() {
            if (isGuest || SubscriptionPlans.PLANS.none { it.id == planId }) return false
            val now = nowMillis()
            val resolved = SubscriptionStatus.fromString(status)
            return com.example.data.RenewalPolicy.grantsAccess(status, expiresAt, now)
        }

    /** True while the subscription is in a billing-retry window — user
     *  still has access but a banner should be shown ("Please update
     *  your payment method"). */
    val isInGracePeriod: Boolean
        get() = isInRenewalGrace

    val accessEndsAt: Long get() = com.example.data.RenewalPolicy.accessEndsAt(expiresAt)
    val isInRenewalGrace: Boolean get() = isActive && expiresAt <= nowMillis()
    val renewalReminderDue: Boolean get() = isActive && com.example.data.RenewalPolicy.reminderDue(expiresAt, nowMillis())

    /** True while the user is on a free trial. */
    val isInTrial: Boolean
        get() {
            val resolved = SubscriptionStatus.fromString(status)
            if (resolved != SubscriptionStatus.TRIAL) return false
            val now = nowMillis()
            return trialEndsAt == null || trialEndsAt > now
        }

    /** True when the user's payment failed and access is now revoked. */
    val isPastDue: Boolean
        get() = SubscriptionStatus.fromString(status) == SubscriptionStatus.PAST_DUE

    /** True when the user has cancelled but the period isn't over yet. */
    val isCancelledButNotExpired: Boolean
        get() {
            val resolved = SubscriptionStatus.fromString(status)
            if (resolved != SubscriptionStatus.CANCELLED) return false
            val now = nowMillis()
            return expiresAt > 0L && expiresAt > now
        }

    /** Canonical plan tier — single source of truth for gating logic. */
    val tier: SubscriptionTier
        get() = SubscriptionTier.fromPlanId(planId)

    val isTvAllowed: Boolean
        get() = isActive && !isGuest && tier != SubscriptionTier.MOBILE

    val maxProfiles: Int
        get() = if (!isActive) 1 else when (tier) {
            SubscriptionTier.GUEST -> 1
            SubscriptionTier.MOBILE -> 1
            SubscriptionTier.BASIC -> 2
            SubscriptionTier.STANDARD -> 4
            SubscriptionTier.PREMIUM -> 5
        }

    val maxDownloads: Int
        get() = if (!isActive) 0 else SubscriptionPlans.PLANS.firstOrNull { it.id == planId }?.maxDownloads ?: 0

    val maxVideoHeight: Int
        get() = if (!isActive) 0 else SubscriptionPlans.PLANS.firstOrNull { it.id == planId }?.maxVideoHeight ?: 0

    val isSmartNextEpisodeAllowed: Boolean
        get() = isActive && SubscriptionPlans.PLANS.firstOrNull { it.id == planId }?.smartNextEpisode == true

    val isDownloadsForYouAllowed: Boolean
        get() = isActive && SubscriptionPlans.PLANS.firstOrNull { it.id == planId }?.downloadsForYou == true

    val isSpatialAudioAllowed: Boolean
        get() = isActive && SubscriptionPlans.PLANS.firstOrNull { it.id == planId }?.spatialAudio == true


    val daysRemaining: Int
        get() {
            if (!isActive) return 0
            val now = nowMillis()
            if (expiresAt <= now) return 0
            val remainingMs = expiresAt - now
            val days = remainingMs / (1000L * 60 * 60 * 24L)
            val remainderHours = (remainingMs % (1000L * 60 * 60 * 24L)) > 0L
            return (days + if (remainderHours) 1L else 0L).toInt().coerceAtLeast(0)
        }

    /**
     * Guest = full movie/series streaming locked (plays trailer preview only).
     * Inactive/Expired = locked.
     * Mobile Plan = TV streaming locked; selected catalog on mobile.
     * Basic Plan = selected catalog, using the same policy as mobile.
     * Standard/Premium = All content unlocked.
     *
     * Compatibility arguments for year and VIP status are retained for callers.
     * Access is determined by the account's tier and the title identifier.
     */
    fun isMovieLocked(
        movieId: String,
        releaseYear: String = "",
        isTrendingOrVip: Boolean = false,
        isTvDevice: Boolean = false,
        movieTitle: String = ""
    ): Boolean {
        if (isGuest) return true // Guest: lock full movie, route to trailer
        if (!isActive) return true // Expired / No active subscription

        if (isTvDevice && tier == SubscriptionTier.MOBILE) return true
        if (tier == SubscriptionTier.STANDARD || tier == SubscriptionTier.PREMIUM) return false
        // Match the phone's catalog policy for the same title and media ID.
        val hash = kotlin.math.abs((movieId.hashCode() * 31 + movieTitle.hashCode() * 17 +
            "netflix_tier_catalog_lock".hashCode()).toLong())
        val pct = hash % 100
        return when (tier) {
            SubscriptionTier.MOBILE -> pct < 38
            SubscriptionTier.BASIC -> pct < 18
            else -> true
        }
    }

    fun getLockReason(
        releaseYear: String = "",
        isTrendingOrVip: Boolean = false,
        isTvDevice: Boolean = false
    ): String {
        if (isGuest) return "Guest Mode: Watch Official Trailer or Sign In to stream full title."
        if (isInGracePeriod) return "Please update your payment method to keep streaming."
        if (isPastDue) return "Your last payment failed. Please update your payment method to resume streaming."
        if (isCancelledButNotExpired) {
            val days = daysRemaining
            return "Your subscription ends in $days day(s). Renew to keep streaming."
        }
        if (!isActive) return "Subscription Expired: Please renew to continue streaming."
        val normalizedPlan = planId.lowercase()
        val normalizedName = planName.lowercase()

        if (normalizedPlan == "plan_mobile" || normalizedPlan == "mobile" || normalizedName.contains("mobile")) {
            return if (isTvDevice) "The Mobile plan plays on phones and tablets. Upgrade on your phone for TV playback."
                else "This title is locked on Mobile. Upgrade on your phone to stream it."
        }
        if (normalizedPlan == "plan_basic" || normalizedPlan == "basic" || normalizedName.contains("basic")) {
            return "This title is locked on Basic. Upgrade on your phone to Standard or Premium."
        }
        return "Upgrade plan to unlock this title."
    }

    companion object {
        /**
         * Clock injection point. The ViewModel sets this once at construction;
         * production uses `System.currentTimeMillis()` and tests inject a fake.
         * Default is the production clock.
         */
        @Volatile
        var clock: () -> Long = { System.currentTimeMillis() }

        internal fun nowMillis(): Long = clock()
    }
}
