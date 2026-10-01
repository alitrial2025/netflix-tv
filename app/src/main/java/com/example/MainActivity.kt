package com.example

import android.os.Bundle
import android.content.Intent
import android.widget.Toast
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import androidx.tv.material3.Surface
import com.example.model.Movie
import com.example.model.catalogMediaKind
import com.example.ui.screens.SplashScreen
import com.example.ui.NetflixViewModel
import com.example.ui.screens.TvAuthScreen
import com.example.ui.screens.AmbientWallpaperScreen
import com.example.ui.screens.CategoryScreen
import com.example.ui.screens.details.DetailsScreen
import com.example.ui.screens.EditProfileScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.player.PlayerScreen
import com.example.ui.screens.ProfileScreen
import com.example.ui.screens.ProfileSetupWalkthroughScreen
import com.example.ui.audio.DpadSoundManager
import com.example.ui.theme.NetflixProTheme
import com.example.ui.util.TvMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

private fun detailsRouteFor(movie: Movie): String =
    "details/${movie.id}?mediaKind=${movie.catalogMediaKind()}&title=${android.net.Uri.encode(movie.title)}"

private class PreviewPlaybackFlag {
    var value = false
}

class MainActivity : ComponentActivity() {
    private val _userInteractionTimestamp = MutableStateFlow(System.currentTimeMillis())
    private val _isWallpaperActiveState = MutableStateFlow(false)

    override fun onUserInteraction() {
        super.onUserInteraction()
        _userInteractionTimestamp.value = System.currentTimeMillis()
        com.example.ui.util.HomeStartupGate.onInteraction()
    }

    // Android's public Activity override must delegate to the AndroidX superclass.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        _userInteractionTimestamp.value = System.currentTimeMillis()
        com.example.ui.util.HomeStartupGate.onInteraction()
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> DpadSoundManager.playNavigation(event.repeatCount)
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_TAB -> {
                    if (event.repeatCount == 0) DpadSoundManager.playConfirm()
                }
            }
        }

        if (_isWallpaperActiveState.value && event.action == KeyEvent.ACTION_DOWN) {
            // Dismiss wallpaper on any TV remote button without triggering click behind
            _isWallpaperActiveState.value = false
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        _userInteractionTimestamp.value = System.currentTimeMillis()
        com.example.ui.util.HomeStartupGate.onInteraction()
        if (_isWallpaperActiveState.value && event.action == MotionEvent.ACTION_DOWN) {
            _isWallpaperActiveState.value = false
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL) {
            _userInteractionTimestamp.value = System.currentTimeMillis()
            com.example.ui.util.HomeStartupGate.onInteraction()
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.ui.util.AppDiagnosticsLogger.event("MainActivity", "MainActivity onCreate initialized.")
        lifecycleScope.launch(Dispatchers.IO) { DpadSoundManager.init(applicationContext) }

        // NOTE: The Coil ImageLoader is configured in `NetflixApplication.newImageLoader()`
        // (via the `ImageLoaderFactory` interface). Coil's default `imageLoader` lookup
        // will discover the Application's factory and use that single, authoritative
        // configuration. Previously this onCreate called `Coil.setImageLoader(...)`
        // with a *different* builder (35% memory, 100MB disk, no RGB_565, no User-Agent)
        // that silently shadowed the Application's loader. The two configs drifted, and
        // the MainActivity's loader was the one that actually ran. Removed in the
        // shared-infrastructure audit pass.

        setContent {
            NetflixProTheme {
                com.example.update.UpdateGateHost {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    colors = androidx.tv.material3.SurfaceDefaults.colors(
                        containerColor = com.example.ui.theme.NetflixBlack
                    )
                ) {
                    val navController = rememberNavController()
                    val viewModel: NetflixViewModel = viewModel()
                    DisposableEffect(navController, viewModel) {
                        val listener = androidx.core.util.Consumer<Intent> { incoming ->
                            viewModel.stopHomePreviews()
                            this@MainActivity.intent = incoming
                            navController.handleDeepLink(incoming)
                        }
                        val destinationListener = androidx.navigation.NavController.OnDestinationChangedListener { _, _, _ ->
                            viewModel.stopHomePreviews()
                        }
                        navController.addOnDestinationChangedListener(destinationListener)
                        addOnNewIntentListener(listener)
                        onDispose {
                            removeOnNewIntentListener(listener)
                            navController.removeOnDestinationChangedListener(destinationListener)
                        }
                    }

                    val currentBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentRoute = currentBackStackEntry?.destination?.route ?: ""
                    val isPlayerScreen = currentRoute.contains("player", ignoreCase = true)
                    // Keep auth, splash, profile selection and editing visible while idle.
                    val canShowWallpaper = currentRoute == "home" ||
                        currentRoute.startsWith("category/") || currentRoute.startsWith("details/")

                    LaunchedEffect(currentRoute) {
                        if (currentRoute.isNotEmpty()) {
                            com.example.ui.util.AppDiagnosticsLogger.event("Navigation", "User navigated to screen: $currentRoute")
                            if (currentRoute != "splash" && currentRoute != "tvAuth") {
                                viewModel.ensureCatalogStarted()
                            }
                        }
                    }

                    val isWallpaperActive by _isWallpaperActiveState.collectAsState()
                    val isBillboardPlaying by viewModel.isBillboardPlaying.collectAsState()
                    val previewWasPlaying = remember { PreviewPlaybackFlag() }

                    // App-wide Idle Inactivity Monitor:
                    // Triggers Ambient Wallpaper after 60s only while browsing, outside playback.
                    // The loop reads the latest timestamp directly. Collecting it here
                    // would recompose the entire navigation host on every remote press.
                    LaunchedEffect(canShowWallpaper, isBillboardPlaying) {
                        if (previewWasPlaying.value && !isBillboardPlaying) {
                            _userInteractionTimestamp.value = System.currentTimeMillis()
                        }
                        previewWasPlaying.value = isBillboardPlaying
                        if (!canShowWallpaper) {
                            _isWallpaperActiveState.value = false
                        } else if (!isBillboardPlaying) {
                            val idleTimeoutMs = 60_000L // 60 seconds of inactivity
                            while (true) {
                                val elapsed = System.currentTimeMillis() - _userInteractionTimestamp.value
                                if (elapsed >= idleTimeoutMs && !_isWallpaperActiveState.value) {
                                    _isWallpaperActiveState.value = true
                                }
                                delay(1000L)
                            }
                        } else {
                            _isWallpaperActiveState.value = false
                        }
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        NavHost(
                            navController = navController,
                            startDestination = "splash"
                        ) {
                            composable(
                                route = "splash",
                                enterTransition = { fadeIn(animationSpec = tween(TvMotion.duration(140), easing = FastOutSlowInEasing)) },
                                exitTransition = { fadeOut(animationSpec = tween(TvMotion.duration(130), easing = FastOutSlowInEasing)) }
                            ) {
                                // Splash holds until the ViewModel's initial data (profiles +
                                // home rows) has loaded, so the transition into the app is
                                // instant — like the real Netflix TV app.
                                LaunchedEffect(viewModel) { viewModel.prepareStartup() }
                                val isWarmupFinished by viewModel.startupReady.collectAsState()
                                SplashScreen(
                                    isWarmupFinished = isWarmupFinished,
                                    onSplashComplete = {
                                        val isLoggedIn = viewModel.isUserLoggedInOrGuest()
                                        val destination = if (isLoggedIn) "profiles" else "tvAuth"
                                        navController.navigate(destination) {
                                            popUpTo("splash") { inclusive = true }
                                        }
                                    }
                                )
                            }

                            composable(
                                route = "tvAuth",
                                deepLinks = listOf(
                                    navDeepLink { uriPattern = "https://netflixpro.app/pair?code={code}" },
                                    navDeepLink { uriPattern = "https://www.netflixpro.app/pair?code={code}" },
                                    navDeepLink { uriPattern = "https://netflixpro.app/pair/{code}" },
                                    navDeepLink { uriPattern = "netflixpro://pair?code={code}" },
                                    navDeepLink { uriPattern = "netflixpro://pair/{code}" }
                                ),
                                enterTransition = { fadeIn(animationSpec = tween(TvMotion.duration(250), easing = FastOutSlowInEasing)) },
                                exitTransition = { fadeOut(animationSpec = tween(TvMotion.duration(200), easing = FastOutSlowInEasing)) }
                            ) {
                                TvAuthScreen(
                                    viewModel = viewModel,
                                    onAuthenticated = { _ ->
                                        // Firestore profiles arrive asynchronously. The picker
                                        // owns loading; an empty first frame is not a new account.
                                        navController.navigate("profiles") {
                                            popUpTo("tvAuth") { inclusive = true }
                                        }
                                    },
                                    onSkipToGuest = {
                                        viewModel.ensureProfilesLoaded()
                                        val hasProfiles = viewModel.profiles.value.isNotEmpty()
                                        val destination = if (hasProfiles) "profiles" else "profileSetup"
                                        navController.navigate(destination) {
                                            popUpTo("tvAuth") { inclusive = true }
                                        }
                                    }
                                )
                            }

                            composable(
                                route = "profileSetup",
                                enterTransition = {
                                    fadeIn(animationSpec = tween(TvMotion.duration(220), easing = FastOutSlowInEasing))
                                },
                                exitTransition = {
                                    fadeOut(animationSpec = tween(TvMotion.duration(180), easing = FastOutSlowInEasing))
                                }
                            ) {
                                val currentProfiles by viewModel.profiles.collectAsState()
                                val hasProfiles = currentProfiles.isNotEmpty()
                                ProfileSetupWalkthroughScreen(
                                    viewModel = viewModel,
                                    onComplete = { _ ->
                                        navController.navigate("home") {
                                            popUpTo("profileSetup") { inclusive = true }
                                            popUpTo("profiles") { inclusive = true }
                                        }
                                    },
                                    onCancel = if (hasProfiles) {
                                        { navController.popBackStack() }
                                    } else null
                                )
                            }

                            composable(
                                route = "profiles",
                                enterTransition = {
                                    fadeIn(animationSpec = tween(TvMotion.duration(140), easing = LinearOutSlowInEasing))
                                },
                                exitTransition = {
                                    fadeOut(animationSpec = tween(TvMotion.duration(110), easing = FastOutSlowInEasing))
                                },
                                popEnterTransition = {
                                    fadeIn(animationSpec = tween(TvMotion.duration(140), easing = LinearOutSlowInEasing))
                                },
                                popExitTransition = {
                                    fadeOut(animationSpec = tween(TvMotion.duration(110), easing = FastOutSlowInEasing))
                                }
                            ) {
                                val isLoggedIn = viewModel.isUserLoggedInOrGuest()
                                LaunchedEffect(isLoggedIn) {
                                    if (!isLoggedIn) {
                                        navController.navigate("tvAuth") {
                                            popUpTo("profiles") { inclusive = true }
                                        }
                                    }
                                }
                                if (isLoggedIn) {
                                    ProfileScreen(
                                        viewModel = viewModel,
                                        onProfileSelected = { _ ->
                                            // ProfileLoadingScreen already selected and hydrated this
                                            // profile before its transition. Do not repeat that work here.
                                            navController.navigate("home") {
                                                popUpTo("profiles") { inclusive = true }
                                            }
                                        },
                                        onEditProfile = { profileId ->
                                            if (navController.currentDestination?.route == "profiles") {
                                                navController.navigate("editProfile/$profileId") { launchSingleTop = true }
                                            }
                                        }
                                    )
                                }
                            }

                            composable(
                                route = "editProfile/{profileId}",
                                arguments = listOf(navArgument("profileId") { type = NavType.StringType }),
                                enterTransition = {
                                    slideInHorizontally(
                                        initialOffsetX = { (it * 0.04f).toInt() },
                                        animationSpec = tween(TvMotion.duration(280), easing = FastOutSlowInEasing)
                                    ) + fadeIn(animationSpec = tween(TvMotion.duration(260), easing = FastOutSlowInEasing))
                                },
                                exitTransition = {
                                    slideOutHorizontally(
                                        targetOffsetX = { -(it * 0.03f).toInt() },
                                        animationSpec = tween(TvMotion.duration(220), easing = FastOutSlowInEasing)
                                    ) + fadeOut(animationSpec = tween(TvMotion.duration(200), easing = FastOutSlowInEasing))
                                },
                                popEnterTransition = {
                                    slideInHorizontally(
                                        initialOffsetX = { -(it * 0.03f).toInt() },
                                        animationSpec = tween(TvMotion.duration(280), easing = FastOutSlowInEasing)
                                    ) + fadeIn(animationSpec = tween(TvMotion.duration(260), easing = FastOutSlowInEasing))
                                },
                                popExitTransition = {
                                    slideOutHorizontally(
                                        targetOffsetX = { (it * 0.04f).toInt() },
                                        animationSpec = tween(TvMotion.duration(220), easing = FastOutSlowInEasing)
                                    ) + fadeOut(animationSpec = tween(TvMotion.duration(200), easing = FastOutSlowInEasing))
                                }
                            ) { backStackEntry ->
                                val profileId = backStackEntry.arguments?.getString("profileId") ?: ""
                                EditProfileScreen(
                                    profileId = profileId,
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            composable(
                                route = "home",
                                deepLinks = listOf(navDeepLink { uriPattern = "netflixpro://home" }),
                                enterTransition = {
                                    fadeIn(animationSpec = tween(TvMotion.duration(140), easing = LinearOutSlowInEasing))
                                },
                                exitTransition = {
                                    fadeOut(animationSpec = tween(TvMotion.duration(110), easing = FastOutSlowInEasing))
                                },
                                popEnterTransition = {
                                    fadeIn(animationSpec = tween(TvMotion.duration(140), easing = LinearOutSlowInEasing))
                                },
                                popExitTransition = {
                                    fadeOut(animationSpec = tween(TvMotion.duration(110), easing = FastOutSlowInEasing))
                                }
                            ) {
                                HomeScreen(
                                    viewModel = viewModel,
                                    onPlayMovie = { movie ->
                                        _isWallpaperActiveState.value = false
                                        viewModel.cacheMovie(movie)
                                        val cw = viewModel.continueWatchingList.value.find {
                                            it.movieId == movie.id && it.title.equals(movie.title, true) &&
                                                it.toMovie().catalogMediaKind() == movie.catalogMediaKind()
                                        }
                                        val season = cw?.season ?: 1
                                        val episode = cw?.episode ?: 1
                                        val epName = cw?.episodeName ?: ""
                                        navController.navigate("player/${movie.id}?season=$season&episode=$episode&episodeName=${android.net.Uri.encode(epName)}&mediaKind=${movie.catalogMediaKind()}&title=${android.net.Uri.encode(movie.title)}")
                                    },
                                    onMovieClick = { movie ->
                                        viewModel.cacheMovie(movie)
                                        navController.navigate(detailsRouteFor(movie))
                                    },
                                    onCategoryClick = { categoryName ->
                                        navController.navigate("category/$categoryName")
                                    },
                                    onNavigateToProfiles = {
                                        navController.navigate("profiles")
                                    }
                                )
                            }

                            composable(
                                route = "category/{categoryName}",
                                arguments = listOf(navArgument("categoryName") { type = NavType.StringType }),
                                enterTransition = {
                                    slideInHorizontally(
                                        initialOffsetX = { (it * 0.04f).toInt() },
                                        animationSpec = tween(TvMotion.duration(320), easing = FastOutSlowInEasing)
                                    ) + fadeIn(animationSpec = tween(TvMotion.duration(280), easing = FastOutSlowInEasing))
                                },
                                exitTransition = {
                                    slideOutHorizontally(
                                        targetOffsetX = { -(it * 0.03f).toInt() },
                                        animationSpec = tween(TvMotion.duration(260), easing = FastOutSlowInEasing)
                                    ) + fadeOut(animationSpec = tween(TvMotion.duration(240), easing = FastOutSlowInEasing))
                                },
                                popEnterTransition = {
                                    slideInHorizontally(
                                        initialOffsetX = { -(it * 0.03f).toInt() },
                                        animationSpec = tween(TvMotion.duration(320), easing = FastOutSlowInEasing)
                                    ) + fadeIn(animationSpec = tween(TvMotion.duration(280), easing = FastOutSlowInEasing))
                                },
                                popExitTransition = {
                                    slideOutHorizontally(
                                        targetOffsetX = { (it * 0.04f).toInt() },
                                        animationSpec = tween(TvMotion.duration(260), easing = FastOutSlowInEasing)
                                    ) + fadeOut(animationSpec = tween(TvMotion.duration(240), easing = FastOutSlowInEasing))
                                }
                            ) { backStackEntry ->
                                val categoryName = backStackEntry.arguments?.getString("categoryName") ?: ""
                                CategoryScreen(
                                    categoryName = categoryName,
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() },
                                    onPlayMovie = { movie ->
                                        _isWallpaperActiveState.value = false
                                        viewModel.cacheMovie(movie)
                                        val cw = viewModel.continueWatchingList.value.find {
                                            it.movieId == movie.id && it.title.equals(movie.title, true) &&
                                                it.toMovie().catalogMediaKind() == movie.catalogMediaKind()
                                        }
                                        val season = cw?.season ?: 1
                                        val episode = cw?.episode ?: 1
                                        val epName = cw?.episodeName ?: ""
                                        navController.navigate("player/${movie.id}?season=$season&episode=$episode&episodeName=${android.net.Uri.encode(epName)}&mediaKind=${movie.catalogMediaKind()}&title=${android.net.Uri.encode(movie.title)}")
                                    },
                                    onMovieClick = { movie ->
                                        viewModel.cacheMovie(movie)
                                        navController.navigate(detailsRouteFor(movie))
                                    }
                                )
                            }

                            composable(
                                route = "details/{movieId}?mediaKind={mediaKind}&title={title}",
                                arguments = listOf(
                                    navArgument("movieId") { type = NavType.StringType },
                                    navArgument("mediaKind") { type = NavType.StringType; defaultValue = "" },
                                    navArgument("title") { type = NavType.StringType; defaultValue = "" }
                                ),
                                deepLinks = listOf(
                                    navDeepLink { uriPattern = "netflixpro://movie/{movieId}" },
                                    navDeepLink { uriPattern = "netflixpro://title/{movieId}" },
                                    navDeepLink { uriPattern = "netflixpro://details/{movieId}" },
                                    navDeepLink { uriPattern = "https://netflixpro.app/movie/{movieId}" },
                                    navDeepLink { uriPattern = "https://www.netflixpro.app/movie/{movieId}" },
                                    navDeepLink { uriPattern = "https://netflixpro.app/title/{movieId}" },
                                    navDeepLink { uriPattern = "https://www.netflixpro.app/title/{movieId}" }
                                ),
                                enterTransition = {
                                    slideInHorizontally(
                                        initialOffsetX = { (it * 0.03f).toInt() },
                                        animationSpec = tween(TvMotion.duration(350), easing = FastOutSlowInEasing)
                                    ) + fadeIn(animationSpec = tween(TvMotion.duration(320), easing = FastOutSlowInEasing)) +
                                    scaleIn(initialScale = 0.97f, animationSpec = tween(TvMotion.duration(350), easing = FastOutSlowInEasing))
                                },
                                exitTransition = {
                                    slideOutHorizontally(
                                        targetOffsetX = { -(it * 0.02f).toInt() },
                                        animationSpec = tween(TvMotion.duration(280), easing = FastOutSlowInEasing)
                                    ) + fadeOut(animationSpec = tween(TvMotion.duration(260), easing = FastOutSlowInEasing)) +
                                    scaleOut(targetScale = 1.02f, animationSpec = tween(TvMotion.duration(280), easing = FastOutSlowInEasing))
                                },
                                popEnterTransition = {
                                    slideInHorizontally(
                                        initialOffsetX = { -(it * 0.03f).toInt() },
                                        animationSpec = tween(TvMotion.duration(350), easing = FastOutSlowInEasing)
                                    ) + fadeIn(animationSpec = tween(TvMotion.duration(320), easing = FastOutSlowInEasing)) +
                                    scaleIn(initialScale = 1.02f, animationSpec = tween(TvMotion.duration(350), easing = FastOutSlowInEasing))
                                },
                                popExitTransition = {
                                    slideOutHorizontally(
                                        targetOffsetX = { (it * 0.03f).toInt() },
                                        animationSpec = tween(TvMotion.duration(280), easing = FastOutSlowInEasing)
                                    ) + fadeOut(animationSpec = tween(TvMotion.duration(260), easing = FastOutSlowInEasing)) +
                                    scaleOut(targetScale = 0.97f, animationSpec = tween(TvMotion.duration(280), easing = FastOutSlowInEasing))
                                }
                            ) { backStackEntry ->
                                val movieId = backStackEntry.arguments?.getString("movieId")
                                val expectedTitle = backStackEntry.arguments?.getString("title")?.takeIf { it.isNotBlank() }
                                val mediaKind = backStackEntry.arguments?.getString("mediaKind")?.takeIf { it == "tv" || it == "movie" }
                                // Catalog rows are observable; the lookup map alone is not.
                                // This lets cold-start deep links wait for their actual title.
                                val catalogRows by viewModel.categoryRows.collectAsState()
                                val catalogLoading by viewModel.isLoading.collectAsState()
                                val movie = viewModel.getMovieById(movieId, expectedTitle, mediaKind)
                                if (movie != null) {
                                    DetailsScreen(
                                        movie = movie,
                                        onBack = { navController.popBackStack() },
                                        onPlayMovie = { m, season, episode, epName ->
                                            _isWallpaperActiveState.value = false
                                            viewModel.cacheMovie(m)
                                            navController.navigate("player/${m.id}?season=$season&episode=$episode&episodeName=${android.net.Uri.encode(epName)}&mediaKind=${m.catalogMediaKind()}&title=${android.net.Uri.encode(m.title)}")
                                        },
                                        onPlayTrailer = { m, season, episode, epName ->
                                            _isWallpaperActiveState.value = false
                                            viewModel.cacheMovie(m)
                                            navController.navigate("player/${m.id}?season=$season&episode=$episode&episodeName=${android.net.Uri.encode(epName)}&mediaKind=${m.catalogMediaKind()}&title=${android.net.Uri.encode(m.title)}&trailer=true")
                                        },
                                        onNavigateToDetails = { m ->
                                            viewModel.cacheMovie(m)
                                            navController.navigate(detailsRouteFor(m)) {
                                                popUpTo("home")
                                            }
                                        },
                                        viewModel = viewModel
                                    )
                                } else {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        androidx.compose.material3.Text(
                                            if (catalogLoading) "Loading title…" else "Title unavailable. Press Back to browse.",
                                            color = androidx.compose.ui.graphics.Color.White
                                        )
                                    }
                                }
                            }
                            composable(
                                route = "player/{movieId}?season={season}&episode={episode}&episodeName={episodeName}&mediaKind={mediaKind}&title={title}&pos={pos}&trailer={trailer}",
                                deepLinks = listOf(
                                    navDeepLink { uriPattern = "netflixpro://play/{movieId}?season={season}&episode={episode}&pos={pos}&mediaKind={mediaKind}&title={title}" },
                                    navDeepLink { uriPattern = "netflixpro://play/{movieId}?season={season}&episode={episode}&pos={pos}" },
                                    navDeepLink { uriPattern = "netflixpro://play/{movieId}?season={season}&episode={episode}" },
                                    navDeepLink { uriPattern = "netflixpro://play/{movieId}" }
                                ),
                                enterTransition = {
                                    fadeIn(animationSpec = tween(TvMotion.duration(380), easing = LinearOutSlowInEasing)) +
                                    scaleIn(initialScale = 0.96f, animationSpec = tween(TvMotion.duration(380), easing = FastOutSlowInEasing))
                                },
                                exitTransition = {
                                    fadeOut(animationSpec = tween(TvMotion.duration(300), easing = FastOutSlowInEasing)) +
                                    scaleOut(targetScale = 1.03f, animationSpec = tween(TvMotion.duration(300), easing = FastOutSlowInEasing))
                                },
                                popEnterTransition = {
                                    fadeIn(animationSpec = tween(TvMotion.duration(350), easing = LinearOutSlowInEasing)) +
                                    scaleIn(initialScale = 1.03f, animationSpec = tween(TvMotion.duration(350), easing = FastOutSlowInEasing))
                                },
                                popExitTransition = {
                                    fadeOut(animationSpec = tween(TvMotion.duration(300), easing = FastOutSlowInEasing)) +
                                    scaleOut(targetScale = 0.96f, animationSpec = tween(TvMotion.duration(300), easing = FastOutSlowInEasing))
                                },
                                arguments = listOf(
                                    navArgument("movieId") { type = NavType.StringType },
                                    navArgument("season") { type = NavType.IntType; defaultValue = 1 },
                                    navArgument("episode") { type = NavType.IntType; defaultValue = 1 },
                                    navArgument("episodeName") { type = NavType.StringType; defaultValue = "" },
                                    navArgument("mediaKind") { type = NavType.StringType; defaultValue = "" },
                                    navArgument("title") { type = NavType.StringType; defaultValue = "" },
                                    navArgument("pos") { type = NavType.LongType; defaultValue = 0L },
                                    navArgument("trailer") { type = NavType.BoolType; defaultValue = false }
                                )
                            ) { backStackEntry ->
                                val movieId = backStackEntry.arguments?.getString("movieId")
                                val season = backStackEntry.arguments?.getInt("season") ?: 1
                                val episode = backStackEntry.arguments?.getInt("episode") ?: 1
                                val episodeName = backStackEntry.arguments?.getString("episodeName") ?: ""
                                val mediaKind = backStackEntry.arguments?.getString("mediaKind")?.takeIf { it == "tv" || it == "movie" }
                                val expectedTitle = backStackEntry.arguments?.getString("title")?.takeIf { it.isNotBlank() }
                                val resumePositionMs = backStackEntry.arguments?.getLong("pos") ?: 0L
                                val clickedLink = this@MainActivity.intent?.data?.takeIf {
                                    it.scheme == "netflixpro" && it.host == "play" && it.lastPathSegment == movieId
                                }
                                val needsLegacyIdentity = clickedLink != null && (expectedTitle == null || mediaKind == null)
                                var legacyIdentity by remember(movieId, clickedLink) { mutableStateOf<Pair<String, String>?>(null) }
                                var legacyLoading by remember(movieId, clickedLink) { mutableStateOf(needsLegacyIdentity) }
                                LaunchedEffect(movieId, clickedLink, needsLegacyIdentity) {
                                    if (needsLegacyIdentity && clickedLink != null) {
                                        try {
                                            legacyIdentity = com.example.tv.TvHomeChannelManager.identityForLegacyLink(applicationContext, clickedLink)
                                        } finally { legacyLoading = false }
                                    }
                                }
                                val resolvedTitle = expectedTitle ?: legacyIdentity?.first
                                val resolvedKind = mediaKind ?: legacyIdentity?.second
                                val catalogRows by viewModel.categoryRows.collectAsState()
                                val catalogLoading by viewModel.isLoading.collectAsState()
                                val movie = if (needsLegacyIdentity && (resolvedTitle == null || resolvedKind == null)) null
                                    else viewModel.getMovieById(movieId, resolvedTitle, resolvedKind)
                                    ?: if (movieId != null && resolvedTitle != null && resolvedKind != null) {
                                        // The TV launcher can reopen a completed title after it
                                        // has left the in-app Continue Watching list.
                                        Movie(
                                            id = movieId,
                                            title = resolvedTitle,
                                            description = "",
                                            backdropUrl = "",
                                            posterUrl = "",
                                            type = if (resolvedKind == "tv") "Series" else "Movie",
                                            duration = if (resolvedKind == "tv") "Series" else "Feature"
                                        )
                                    } else null
                                if (movie != null) {
                                    PlayerScreen(
                                        movie = movie,
                                        season = season,
                                        episode = episode,
                                        episodeName = episodeName,
                                        initialPositionMs = resumePositionMs,
                                        trailerOnly = backStackEntry.arguments?.getBoolean("trailer") ?: false,
                                        onBack = {
                                            if (!navController.popBackStack()) {
                                                val destination = if (viewModel.selectedProfile.value == null) "profiles" else "home"
                                                navController.navigate(destination) { launchSingleTop = true }
                                            }
                                        },
                                        viewModel = viewModel
                                    )
                                } else {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        androidx.compose.material3.Text(
                                            if (legacyLoading || catalogLoading) "Loading title…" else "Title unavailable. Press Back to browse.",
                                            color = androidx.compose.ui.graphics.Color.White
                                        )
                                    }
                                }
                            }
                        }

                        // App-Wide Ambient Wallpaper / Screensaver Overlay
                        AnimatedVisibility(
                            visible = isWallpaperActive && canShowWallpaper,
                            enter = fadeIn(animationSpec = tween(700, easing = FastOutSlowInEasing)),
                            exit = fadeOut(animationSpec = tween(350, easing = FastOutSlowInEasing))
                        ) {
                            val categoryRows by viewModel.categoryRows.collectAsState()
                            val allMovies = remember(categoryRows) {
                                categoryRows.flatMap { it.second }.distinctBy { it.id }
                            }
                            AmbientWallpaperScreen(
                                movies = allMovies,
                                onDismiss = {
                                    _userInteractionTimestamp.value = System.currentTimeMillis()
                                    com.example.ui.util.HomeStartupGate.onInteraction()
                                    _isWallpaperActiveState.value = false
                                }
                            )
                        }

                    }
                }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        DpadSoundManager.release()
    }
}
