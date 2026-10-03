package com.example

import android.app.Application
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.example.model.Movie
import com.example.model.Profile
import com.example.model.UserSubscription
import com.example.ui.NetflixViewModel
import com.example.ui.screens.HomeScreen
import com.example.ui.theme.NetflixProTheme
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
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

/** Offline export of production Kotlin UI. Installed into the test source set only in the film workflow. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1280dp-h720dp-xhdpi", sdk = [34], application = Application::class)
class LaunchFilmTvCaptureTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var viewModel: NetflixViewModel
    private lateinit var output: File

    @Before fun prepare() {
        assumeTrue("Opt in to the launch-film export", System.getenv("NPRO_NATIVE_CAPTURE") == "1")
        val fixtures = File(requireNotNull(System.getenv("NPRO_NATIVE_FIXTURES")))
        output = File(requireNotNull(System.getenv("NPRO_NATIVE_OUTPUT"))).apply { mkdirs() }
        val application = ApplicationProvider.getApplicationContext<Application>()
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(application).crossfade(false).build())
        com.example.ui.util.HomeStartupGate.markHomeHidden()
        val catalogue = JSONArray(File(fixtures, "catalogue.json").readText())
        val movies = (0 until catalogue.length()).map { index ->
            val source = catalogue.getJSONObject(index)
            Movie(
                id = "launch_${source.getString("id")}",
                title = source.getString("title"), description = source.getString("overview"),
                backdropUrl = File(fixtures, source.getString("backdrop_file")).toURI().toString(),
                posterUrl = File(fixtures, source.getString("poster_file")).toURI().toString(),
                rating = "16+", year = source.get("year").toString(),
                type = if (source.getString("type") == "tv") "Series" else "Movie",
                duration = if (source.getString("type") == "tv") "3 Seasons" else "2h 32m"
            )
        }
        viewModel = NetflixViewModel(application)
        val profile = Profile("launch_demo", "Home", autoplayPreviews = false)
        seed("_selectedProfile", profile)
        seed("_profiles", listOf(profile))
        seed("_categoryRows", listOf("Trending Now" to movies, "Popular Movies" to movies.filter { it.type == "Movie" }, "TV Shows" to movies.filter { it.type == "Series" }, "Your next story" to movies.reversed()))
        seed("_isLoading", false)
        seed("_userSubscription", UserSubscription(planId = "plan_premium", status = "ACTIVE", expiresAt = System.currentTimeMillis() + 7 * 86_400_000L))
        seed("_myListMovieIds", movies.take(3).map { it.id }.toSet())
    }

    @Suppress("UNCHECKED_CAST") private fun <T> seed(name: String, value: T) {
        val field = NetflixViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
        (field.get(viewModel) as MutableStateFlow<T>).value = value
    }

    private fun key(code: Int) {
        rule.runOnUiThread {
            val time = SystemClock.uptimeMillis()
            rule.activity.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0))
            rule.activity.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, code, 0))
        }
    }

    @Test fun homeMotion() {
        rule.mainClock.autoAdvance = false
        rule.setContent { NetflixProTheme { HomeScreen(viewModel, onPlayMovie = {}, onMovieClick = {}, onCategoryClick = {}) } }
        rule.mainClock.advanceTimeBy(1800)
        repeat(80) { ShadowLooper.idleMainLooper(); Thread.sleep(25) }
        rule.mainClock.advanceTimeBy(600)
        rule.onRoot().captureRoboImage(File(output, "tv-home.png").path)
        val frames = File(output, "tv-home").apply { mkdirs() }
        var previous = 0L
        for (frame in 0 until 60) {
            when (frame) {
                15 -> key(KeyEvent.KEYCODE_DPAD_DOWN)
                30 -> key(KeyEvent.KEYCODE_DPAD_DOWN)
                42, 51 -> key(KeyEvent.KEYCODE_DPAD_RIGHT)
            }
            val next = (frame + 1) * 1000L / 15
            rule.mainClock.advanceTimeBy(next - previous, ignoreFrameDuration = true)
            previous = next
            ShadowLooper.idleMainLooper()
            rule.onRoot().captureRoboImage(File(frames, "%05d.png".format(frame)).path)
        }
    }
}
