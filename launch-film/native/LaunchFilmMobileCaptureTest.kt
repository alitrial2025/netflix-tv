package com.example

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.WatchProgressEntity
import com.example.data.model.AvatarType
import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.model.UserProfile
import com.example.data.model.UserSubscription
import com.example.ui.components.NetflixBottomNav
import com.example.ui.components.NetflixTopBar
import com.example.ui.screens.DetailScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.theme.NetflixTheme
import com.example.ui.viewmodel.CategoryFilter
import com.example.ui.viewmodel.NavigationTab
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.json.JSONArray
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/** Real production Compose screens with local catalogue fixtures and a synthetic profile. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h895dp-xxhdpi", sdk = [34], application = Application::class)
class LaunchFilmMobileCaptureTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var output: File
    private lateinit var catalogue: List<MediaItem>
    private val details = mutableStateOf(false)
    private val browseStep = mutableIntStateOf(0)
    private val profile = UserProfile("launch_demo", "Home", avatarColorHex = 0xFFE50914L, avatarType = AvatarType.SMILEY)
    private val subscription = UserSubscription(planId = "plan_premium", status = "ACTIVE", expiresAt = Long.MAX_VALUE)

    @Before fun prepare() {
        assumeTrue("Opt in to the launch-film export", System.getenv("NPRO_NATIVE_CAPTURE") == "1")
        val fixtures = File(requireNotNull(System.getenv("NPRO_NATIVE_FIXTURES")))
        output = File(requireNotNull(System.getenv("NPRO_NATIVE_OUTPUT"))).apply { mkdirs() }
        val application = ApplicationProvider.getApplicationContext<Application>()
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(application).crossfade(false).build())
        val source = JSONArray(File(fixtures, "catalogue.json").readText())
        val titles = (0 until source.length()).map { index ->
            val item = source.getJSONObject(index)
            val series = item.getString("type") == "tv"
            MediaItem("launch_${item.getString("id")}", item.getString("title"), if (series) MediaType.TV_SHOW else MediaType.MOVIE,
                item.getString("overview"), "", 96, "16+", item.getInt("year"), if (series) "3 Seasons" else "2h 32m",
                isOriginal = false, genres = listOf("Drama", "Thriller"), cast = emptyList(), director = "",
                posterUrl = File(fixtures, item.getString("poster_file")).toURI().toString(),
                backdropUrl = File(fixtures, item.getString("backdrop_file")).toURI().toString())
        }
        val show = MediaItem("launch_squid", "Squid Game", MediaType.TV_SHOW,
            "Hundreds of cash-strapped players accept a strange invitation to compete in children's games. Inside, a tempting prize awaits — with deadly high stakes.",
            "The game never ends.", 96, "16+", 2021, "3 Seasons", top10Rank = 1,
            genres = listOf("Violent", "Suspenseful", "Thriller", "Korean"), cast = listOf("Lee Jung-jae", "Lee Byung-hun"), director = "Hwang Dong-hyuk",
            isTrending = true, posterUrl = File(fixtures, "squid-game.jpg").toURI().toString(),
            backdropUrl = File(fixtures, "squid-game.jpg").toURI().toString(), logoUrl = File(fixtures, "squid-game-logo.png").toURI().toString(),
            episodes = (1..6).map { Episode("ep_launch_squid_S1_$it", it, if (it == 1) "The Invitation" else "Chapter $it", 60, "A new chapter unfolds.", stillUrl = File(fixtures, "squid-game.jpg").toURI().toString()) },
            totalSeasons = 3, similarMedia = titles)
        catalogue = listOf(show) + titles
    }

    private fun mount() {
        rule.mainClock.autoAdvance = false
        rule.setContent { NetflixTheme {
            val listState = rememberLazyListState()
            LaunchedEffect(browseStep.intValue) {
                if (browseStep.intValue > 0) listState.animateScrollToItem(if (browseStep.intValue == 1) 2 else 0)
            }
            Box(Modifier.fillMaxSize()) {
                HomeScreen(profile, CategoryFilter.ALL, null,
                    catalogue.take(3).map { it to WatchProgressEntity(profileId = profile.id, mediaId = it.id, positionSeconds = 360, totalSeconds = 3600) },
                    isWatchlistContains = { false }, onMediaClick = {}, onPlayClick = {}, onWatchlistToggle = {},
                    catalogMedia = catalogue, userSubscription = subscription, listState = listState)
                NetflixTopBar(profile, CategoryFilter.ALL, null, { _, _ -> }, {}, {}, {}, unreadNotificationCount = 0,
                    scrollFractionProvider = { (listState.firstVisibleItemScrollOffset / 180f).coerceIn(0f, 1f) })
                NetflixBottomNav(NavigationTab.HOME, profile, {}, modifier = Modifier.align(Alignment.BottomCenter))
                // These are the production MainActivity detail enter/exit transitions.
                AnimatedVisibility(details.value, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                    DetailScreen(catalogue.first(), false, subscription, downloadProgressMap = emptyMap(),
                        onClose = {}, onPlayClick = { _, _ -> }, onPlayTrailerClick = { _, _ -> }, onWatchlistToggle = {},
                        onDownloadClick = { _, _ -> }, onRatingSelect = {}, onSimilarMediaClick = {})
                }
            }
        } }
        rule.mainClock.advanceTimeBy(1000)
        rule.waitUntil(20_000) { ShadowLooper.idleMainLooper(); com.example.ui.components.posterColorsLoaded(catalogue.first()) }
        repeat(40) { ShadowLooper.idleMainLooper(); Thread.sleep(25) }
        rule.mainClock.advanceTimeBy(600)
    }

    private fun clip(name: String, action: (Int) -> Unit) {
        val directory = File(output, name).apply { mkdirs() }
        var previous = 0L
        for (frame in 0 until 60) {
            action(frame)
            val next = (frame + 1) * 1000L / 15
            rule.mainClock.advanceTimeBy(next - previous, ignoreFrameDuration = true)
            previous = next
            ShadowLooper.idleMainLooper()
            rule.onRoot().captureRoboImage(File(directory, "%05d.png".format(frame)).path)
        }
    }

    @Test fun homeMotion() {
        mount()
        rule.onRoot().captureRoboImage(File(output, "mobile-home.png").path)
        clip("mobile-home") { frame -> when (frame) { 15 -> rule.runOnIdle { browseStep.intValue = 1 }; 42 -> rule.runOnIdle { browseStep.intValue = 2 } } }
    }

    @Test fun detailsMotion() {
        mount()
        rule.runOnIdle { details.value = true }
        clip("mobile-details") { frame ->
            if (frame == 14) rule.onRoot().captureRoboImage(File(output, "mobile-details.png").path)
            if (frame == 40) rule.onNodeWithTag("detail_list").performScrollToNode(hasText("Season 1"))
        }
    }
}
