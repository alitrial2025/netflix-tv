package com.example.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import com.example.ui.util.TvMotion

/**
 * Shared one-shot transitions. Durations below are unscaled design timings;
 * [TvMotion] supplies the same flowing cadence used by navigation and browsing.
 * Focus and click handlers still act immediately, before these visuals settle.
 */
object AnimationSpecs {
    private fun transition(baseMillis: Int) =
        tween<Float>(TvMotion.duration(baseMillis), easing = FastOutSlowInEasing)

    /** Master horizontal/vertical glide. */
    val GlobalTransition = transition(700)

    /** Focused-pill slide on tab switch. */
    val PillSlide = transition(180)

    /** Billboard subtitle/description block reveal. */
    val SubtitleExpand = transition(280)

    /** Categories chip focus ring fade-in/out. */
    val RingFade = transition(200)

    /** Skeleton → content cross-fade when category cache hydrates. */
    val SkeletonCrossfade = transition(300)

    /** Profile screen polish pass — micro durations that all share the same easing. */
    /** Focus ring + avatar text color transition. */
    val FocusMicro = transition(200)
    /** Title "Who's watching?" first-paint fade + slide. */
    val TitleIntro = transition(400)
    /** Profile row staggered entrance after the title. */
    val RowIntro = transition(220)
    /** Brief click-down feedback (e.g. card press). */
    val PressDown = transition(100)
    /** Whole-screen exit fade when navigating away. */
    val ScreenExit = transition(180)
    /** Underline / micro-ornament width growth. */
    val UnderlineGrow = transition(180)

    /** TV Auth screen — QR code fade-in (was previously missing an enter spec). */
    val QrFadeIn = transition(220)
    /** TV Auth screen — sign-in form enter/transition timing. */
    val AuthFormEnter = transition(220)

    // Subscription / upgrade modal
    /** Upgrade modal scrim fade-in (was previously a hardcoded 220ms tween at the
     *  call site). */
    val UpgradeModalScrim = transition(220)
    /** Upgrade modal body enter — the panel slide-up + fade. */
    val UpgradeModalBody = transition(280)
    /** Plan row focus ring transition (matches CategoriesBar.RingFade cadence). */
    val UpgradePlanRowFocus = transition(200)
    /** Billing status banner enter — slightly slower so the user notices the
     *  grace / past-due / cancelled banner on screen entry. */
    val BillingBannerEnter = transition(320)
    /** Success checkmark scale-in after a confirmed plan upgrade. */
    val UpgradeSuccessCheck = transition(260)

    // My List (Add/Remove) — audit §1, §5, §8
    /** Press-down scale feedback on the My List action button. Brief 100ms snap
     *  to 0.95 then back to 1.0. Match [PressDown] cadence. */
    val MyListPressBounce = transition(200)
    /** Outlined-heart → filled-heart scale bounce when the user adds an item. */
    val MyListIconBounce = transition(200)
    /** "Added to My List" / "Removed from My List" toast slide-in / slide-out. */
    val MyListToast = transition(200)
    /** Card fade-out after a remove (200ms enter / exit paired with AnimatedContent). */
    val MyListCardFade = transition(200)
}
