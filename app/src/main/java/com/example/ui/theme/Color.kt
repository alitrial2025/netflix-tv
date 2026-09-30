package com.example.ui.theme

import androidx.compose.ui.graphics.Color

val NetflixRed = Color(0xFFE50914)
val NetflixBlack = Color(0xFF000000)
val NetflixDarkGrey = Color(0xFF141414)
val NetflixLightGrey = Color(0xFF2F2F2F)
val NetflixWhite = Color(0xFFFFFFFF)

// Warm amber used for KIDS-profile accents (avatar ring, badge fill, radial glow).
// Per audit §6d, promotes what was previously a hardcoded `Color(0xFFFF9D2B)`
// at five call-sites. Call-sites are intentionally not migrated in this pass.
val KidsAccent = Color(0xFFFF9D2B)

// TV Material Palette
val Primary = NetflixRed
val Background = NetflixBlack
val Surface = NetflixDarkGrey
val OnPrimary = NetflixWhite
val OnBackground = NetflixWhite
val OnSurface = NetflixWhite

// Profile screen tokens (polish pass)
// Muted foreground color for non-focused profile names.
val ProfileNameDim = Color(0xBFFFFFFF) // White @ 75% — matches the 0.75f spec.
// Soft scrim for the backplate behind profile cards when the backdrop is busy.
val ProfileScrim = Color(0x66000000) // Black @ 40%.
// Faint placeholder for the Add Profile tile when not focused.
val ProfileAddTileDim = Color(0x14FFFFFF) // White @ 8%.
// Slightly stronger fill for the Add Profile tile when focused.
val ProfileAddTileBright = Color(0x33FFFFFF) // White @ 20%.
// Subtle border tint for the unfocused Add Profile dashed edge.
val ProfileAddTileBorderDim = Color(0x40FFFFFF) // White @ 25%.
// PIN screen chrome — top-of-page scrim and PIN-dot empty ring.
val ProfilePinScrimTop = Color(0x99000000)
val ProfilePinScrimMid = Color(0x66000000)
val ProfilePinScrimBottom = Color(0xCC000000)
val ProfilePinKeyBg = Color(0xFF2B2B2B)
val ProfilePinPanelBg = Color(0xEE181818)
val ProfilePinPanelBorder = Color(0x26FFFFFF) // White @ 15%.
val ProfilePinDotEmpty = Color(0xFF666666)

// TV Auth screen tokens
// Brand red used for the activation pin box, primary CTAs, and QR border.
val TvAuthRed = NetflixRed
// Background scrim used behind the pin box to give it depth over the page bg.
val TvAuthRedScrim = Color(0x26E50914) // NetflixRed @ 15%.
// Translucent white for secondary buttons ("New Code", "Back to QR Code").
val TvAuthSecondaryBg = Color(0x26FFFFFF) // White @ 15%.
val TvAuthTertiaryBg = Color(0x1AFFFFFF) // White @ 10%.
// Unfocused outline color for OutlinedTextField in the direct email form.
val TvAuthFieldOutline = Color(0x66FFFFFF) // White @ 40%.
// Status badge colors — paired/unpaired.
val TvAuthStatusPaired = Color(0xFF46D369)
val TvAuthStatusPairedBg = Color(0x3346D369) // paired green @ 20%.
// Error text color used for invalid email/password validation messages.
val TvAuthErrorText = NetflixRed

// Subscription / billing screen tokens
// Per audit §1, §9 — banners need distinct colors for grace, past-due, trial,
// and cancellation. Locked-content overlay gets a gold accent (matches the
// Lock icon tint already in the upgrade modal).
val SubscriptionLockAccent = Color(0xFFFFC107) // Gold for the lock icon in upgrade modal.
val SubscriptionTrialAccent = Color(0xFFB388FF) // Soft purple for "Free trial" badges.
val SubscriptionGraceAccent = Color(0xFFFFB300) // Amber for "Please update payment method" banners.
val SubscriptionPastDueAccent = Color(0xFFFF5252) // Red for "Payment failed" banners.
val SubscriptionCancelledAccent = Color(0xFF9E9E9E) // Grey for "Subscription ends on {date}".
val SubscriptionModalScrim = Color(0xD9000000) // Black @ 85% — matches the existing upgrade modal backdrop.
// Plan card tokens — used by DetailsScreen.kt UpgradePlanModal. Pinned here so
// the colour vocabulary lives in one place and the modal can grow new states
// (e.g. selected ring, focused row tint) without re-deriving hex codes.
val SubscriptionPlanCardBg = Color(0xFF202020) // Unfocused, unselected row.
val SubscriptionPlanCardBgSelected = Color(0xFF2B2B2B) // Selected row.
val SubscriptionPlanCardBgFocused = Color(0xFF383838) // D-pad focused row.
val SubscriptionPlanCardBorder = Color(0xFF444444) // Unfocused border.
val SubscriptionPlanCardBorderSelected = NetflixRed // Selected border accent.
val SubscriptionPlanModalBg = Color(0xFF181818) // Surface behind the modal body.
val SubscriptionPlanModalBorder = Color(0xFF333333) // Modal border, unfocused.
val SubscriptionPlanSecondaryButtonBg = Color(0xFF333333) // "Watch Trailer" / "Cancel" button.

// My List (audit §1, §5, §8)
// Toast / snackbar surfaces. Keep the brand red accent in the chip but use
// a near-black background so the chip + text read against the dimmed
// content beneath, matching the SubscriptionModalScrim vocabulary above.
val MyListToastBg = Color(0xEE181818) // Surface @ ~93% — pairs with the modal scrim.
val MyListToastText = NetflixWhite
val MyListToastChipAdded = NetflixRed
val MyListToastChipRemoved = Color(0xFF666666) // Neutral grey for the "Removed" chip.
