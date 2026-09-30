package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.tv.material3.ColorScheme
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme

// Top-level val so the ColorScheme is allocated once at class init, NOT every
// recomposition. darkColorScheme / lightColorScheme build a fresh object each
// call, so hoisting them out of the composable saves an allocation per frame.
@OptIn(ExperimentalTvMaterial3Api::class)
private val NetflixDarkColorScheme: ColorScheme = darkColorScheme(
    primary = NetflixRed,
    onPrimary = NetflixWhite,
    background = NetflixBlack,
    onBackground = NetflixWhite,
    surface = NetflixDarkGrey,
    onSurface = NetflixWhite,
    secondaryContainer = NetflixLightGrey,
    onSecondaryContainer = NetflixWhite
)

/**
 * Light scheme is here for parity with the dark scheme but is intentionally
 * not the default. The Netflix TV experience is dark-first (cinema-mode
 * contrast at couch distance); opting in via a system-level "Use light theme"
 * toggle is a follow-up.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
private val NetflixLightColorScheme: ColorScheme = lightColorScheme(
    primary = NetflixRed,
    onPrimary = NetflixWhite,
    background = NetflixWhite,
    onBackground = NetflixBlack,
    surface = NetflixLightGrey,
    onSurface = NetflixBlack,
    secondaryContainer = NetflixDarkGrey,
    onSecondaryContainer = NetflixWhite
)

/**
 * Single source of truth for the app's design tokens.
 *
 * @param darkTheme When null, follows the system dark-mode setting. TV devices
 *   are overwhelmingly dark-mode in their out-of-box settings, so the
 *   no-arg call site gets the right default for ~99% of installs.
 * @param content Composable children that read tokens via [MaterialTheme].
 *
 * Intentionally does NOT override `LocalDensity` (TV is standard density) or
 * `LocalLayoutDirection` (no RTL opt-in is shipped today).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NetflixProTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) NetflixDarkColorScheme else NetflixLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

/**
 * Single source of truth for rating-pill defaults when a [com.example.model.Movie]
 * has no explicit rating. Per audit §7a, the previous code defaulted missing
 * ratings to "18" in the Billboard and "13" in the row — two different fallback
 * values for the same Movie is a trust bug. Mid-teen content is the modal
 * rating in the catalogue, so "13+" is the consistent default.
 *
 * NOTE: this object is additive. Existing call-sites still use their local
 * `ifBlank { "13" }` / hardcoded "18" defaults and are intentionally not
 * migrated in this pass — that refactor is out of scope.
 */
object RatingDefaults {
    const val MISSING = "13+"
}
