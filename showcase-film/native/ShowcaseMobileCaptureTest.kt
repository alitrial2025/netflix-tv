package com.example

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.swipe
import com.example.data.local.WatchProgressEntity
import com.example.data.model.AvatarType
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.model.UserProfile
import com.example.data.model.UserSubscription
import com.example.ui.components.NetflixBottomNav
import com.example.ui.components.NetflixTopBar
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.ProfilePickerSheet
import com.example.ui.theme.NetflixTheme
import com.example.ui.viewmodel.CategoryFilter
import com.example.ui.viewmodel.NavigationTab
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h895dp-xhdpi", sdk = [34], application = Application::class)
class ShowcaseMobileCaptureTest {
    @get:Rule val rule = createComposeRule()
    @Before fun freshImageLoaderForRobolectricApplication() {
        // Robolectric replaces the Application between tests; Coil's singleton otherwise
        // retains the previous sandbox's main-thread dispatcher and stalls new requests.
        org.junit.Assume.assumeTrue("Set NETFLIXPRO_CAPTURE=1 for native film export", System.getenv("NETFLIXPRO_CAPTURE") == "1")
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        val artwork=mutableMapOf<String,android.graphics.Bitmap>()
        val loader=coil.ImageLoader.Builder(context).allowHardware(false).components {
            add(coil.intercept.Interceptor { chain ->
                val request=chain.request.newBuilder().allowHardware(false).crossfade(false)
                artwork[chain.request.data.toString()]?.let { request.data(it).memoryCacheKey(chain.request.data.toString()) }
                val result=chain.proceed(request.build())
                if(result is coil.request.ErrorResult) System.err.println("CAPTURE_ARTWORK_ERROR ${result.throwable.javaClass.simpleName}")
                result
            })
        }.build()
        coil.Coil.setImageLoader(loader)
        for(file in File("src/test/resources/artwork").listFiles().orEmpty().filter{it.extension in listOf("jpg","png","webp")}) {
            val url=fixture(file.name)
            artwork[url]=checkNotNull(android.graphics.BitmapFactory.decodeFile(File(java.net.URI(url)).path)) { "Offline film artwork failed to decode" }
        }
        org.robolectric.shadows.ShadowStatFs.registerStats(context.filesDir.absolutePath, 16_000_000, 9_000_000, 9_000_000)
    }
    private val names = listOf("Jeff", "Brian", "Mom", "Home", "Kids")
    private val colors = listOf(0xFF454545L, 0xFFE2B80CL, 0xFF006477L, 0xFFE50914L, 0xFF00A6BAL)
    private val profiles = names.mapIndexed { index, name -> UserProfile(id = "p$index", name = name,
        avatarColorHex = colors[index], avatarType = if (index == 4) AvatarType.KIDS else AvatarType.SMILEY, isKids = index == 4) }
    private fun fixture(name: String): String {
        val directory = File("build/reports/test-fixtures").apply { mkdirs() }
        val file = File(directory, name)
        javaClass.getResourceAsStream("/artwork/$name")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file.toURI().toString()
    }
    private val show by lazy { MediaItem("42", "Squid Game", MediaType.TV_SHOW, "", "", 96, "16+", 2026, "3 Seasons",
        top10Rank = 1, genres = listOf("Violent", "Suspenseful", "Thriller", "Korean"), cast = emptyList(), director = "",
        posterUrl = fixture("squid-game.jpg"), logoUrl = fixture("squid-game-logo.png"), isTrending = true, episodes = listOf(com.example.data.model.Episode("42_S1_E1",1,"The Invitation",55,"A new game begins.", stillUrl=fixture("squid-game.jpg")),com.example.data.model.Episode("42_S1_E2",2,"A New Chapter",52,"The story continues.", stillUrl=fixture("squid-game.jpg")))) }
    private val membership = UserSubscription(planId = "plan_premium", planName = "Premium", amount = 1350, status = "ACTIVE", expiresAt = Long.MAX_VALUE)

    private fun captureRoot(): File = File(System.getProperty("screenshot.output",
        File(System.getenv("NPRO_SHOWCASE_WORK") ?: "/workspace/artifacts/showcase-20261004", "assets/native-mobile").path))

    private fun capture(name: String) {
        val directory = captureRoot()
        directory.mkdirs()
        rule.onRoot().captureRoboImage(File(directory, "$name.png").path)
    }

    @Test fun profilePickerKeepsEditAndProfileSelectionAccessible() {
        var selected: UserProfile? = null
        val featured = show.copy(title = "Umthetho", posterUrl = fixture("umthetho.jpg"), logoUrl = fixture("umthetho-logo.png"))
        rule.setContent {
            NetflixTheme {
                ProfilePickerSheet(profiles, profiles[3], featured,
                    membership, onSelectProfile = { selected = it }, onEditProfile = {}, onDismiss = {})
            }
        }
        rule.onNodeWithText("Choose your profile").assertExists()
        try {
            rule.waitUntil(10_000) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                com.example.ui.components.posterColorsLoaded(featured)
            }
        } catch (failure: Exception) {
            org.robolectric.shadows.ShadowLog.getLogsForTag("PosterColors").forEach { log ->
                System.err.println(log.msg)
                log.throwable?.printStackTrace()
            }
            throw failure
        }
        rule.mainClock.advanceTimeBy(600)
        capture("profile-picker")
        rule.onNodeWithText("Brian").performClick()
        assertTrue(selected?.name == "Brian")
        rule.onNodeWithText("Edit").performClick()
        rule.onNodeWithText("Tap a profile to edit").assertExists()
    }

    @Test fun homeReferenceLayout() {
        rule.setContent {
            NetflixTheme {
                Box(Modifier.fillMaxSize()) {
                    HomeScreen(profiles[3], CategoryFilter.ALL, null,
                        listOf(show to WatchProgressEntity(profileId = "p3", mediaId = "42", positionSeconds = 60, totalSeconds = 1200)),
                        isWatchlistContains = { false }, onMediaClick = {}, onPlayClick = {}, onWatchlistToggle = {},
                        catalogMedia = listOf(show), userSubscription = membership)
                    NetflixTopBar(profiles[3], CategoryFilter.ALL, null, { _, _ -> }, {}, {}, {}, unreadNotificationCount = 5)
                    NetflixBottomNav(NavigationTab.HOME, profiles[3], {}, modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        rule.onNodeWithText("Play").assertExists()
        rule.onNodeWithText("My List").assertExists()
        rule.waitUntil(10_000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            com.example.ui.components.posterColorsLoaded(show)
        }
        rule.mainClock.advanceTimeBy(600)
        capture("home")
    }

    @Test fun offlineEmptyCatalogOffersDownloadsInsteadOfEndlessSpinner() {
        var downloadsOpened = false
        rule.setContent {
            NetflixTheme {
                HomeScreen(profiles[3], CategoryFilter.ALL, null, emptyList(), { false }, {}, {}, {},
                    onOpenDownloads = { downloadsOpened = true })
            }
        }
        rule.onNodeWithText("Open downloads").performClick()
        assertTrue(downloadsOpened)
        capture("offline-home")
    }

    private fun ready() {
        rule.mainClock.advanceTimeBy(900)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        repeat(40) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(25) }
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1200))
        rule.mainClock.advanceTimeBy(1200)
        rule.waitForIdle()
    }
    @Test fun filmDownloads() {
        com.example.data.CatalogData.allMedia = listOf(show)
        rule.setContent { NetflixTheme {
            com.example.ui.screens.DownloadsScreen(
                downloads = listOf(com.example.data.local.DownloadEntity(downloadKey="42", mediaId="42", mediaTitle="Squid Game", episodeTitle=null, fileSizeMb=485)),
                smartDownloadsEnabled=true, connectedCastDevice=null, onClose={}, onPlayMedia={_,_->}, onDeleteDownload={},
                onClearAllDownloads={}, onToggleSmartDownloads={}, onSetUpDownloadsForYou={}, onOpenSearch={}, onOpenCast={},
                onOpenMediaDetail={}, onShowToast={}, userSubscription=membership)
        } }
        ready(); capture("downloads")
    }
    @Config(qualifiers="w412dp-h895dp-xhdpi",sdk=[34],application=Application::class,
        shadows=[ShowcaseTrailerResolverShadow::class],instrumentedPackages=["com.example.data.TrailerResolver"])
    @Test fun filmClips() {
        com.example.data.CatalogData.allMedia = listOf(show)
        rule.setContent { NetflixTheme { com.example.ui.screens.ClipsScreen(streamingAllowed=true) } }
        ready(); capture("clips")
    }
    @Test fun filmMyList() {
        com.example.data.CatalogData.allMedia = listOf(show)
        rule.setContent { NetflixTheme {
            com.example.ui.screens.MyNetflixScreen(
                activeProfile=profiles[3], watchlist=listOf(show), downloads=emptyList(), likedMedia=listOf(show),
                watchedTrailers=emptyList(), notifications=emptyList(), continueWatchingList=emptyList(), watchHistory=emptyList(),
                reminders=emptyList(), connectedCastDevice=null, smartDownloadsEnabled=true,
                onSwitchProfileClick={}, onMediaClick={}, onPlayClick={}, onPlayTrailerClick={}, onOpenDownloads={},
                onDeleteDownload={}, onClearAllDownloads={}, onWatchlistToggle={}, onReminderToggle={},
                onContinueWatchingOptionsClick={_,_->}, onHistoryClick={}, onOpenNotifications={}, onOpenSettings={},
                onOpenCast={}, onNavigateToSearch={}, onShowToast={}, userSubscription=membership,isAuthenticated=true)
        } }
        ready(); capture("my-list")
    }
    @Test fun filmDetails() {
        com.example.data.CatalogData.allMedia = listOf(show)
        rule.setContent { NetflixTheme {
            com.example.ui.screens.DetailScreen(media=show, isInWatchlist=true, userSubscription=membership,
                downloadProgressMap=emptyMap(), onClose={}, onPlayClick={_,_->}, onPlayTrailerClick={_,_->},
                onWatchlistToggle={}, onDownloadClick={_,_->}, onRatingSelect={}, onSimilarMediaClick={})
        } }
        ready(); capture("details")
    }

    private val motionRoute = mutableStateOf("home")
    private val motionFilter = mutableStateOf(CategoryFilter.ALL)
    private val motionSaved = mutableStateOf(false)
    private fun filmCatalog(): List<MediaItem> {
        val source=File("../../netflix-tv/advertising-video")
        val data=org.json.JSONArray(File(source,"catalogue.json").readText())
        return listOf(show)+(0 until data.length()).map { n ->
            val j=data.getJSONObject(n)
            show.copy(id="${9000+n}", title=j.getString("title"), description=j.getString("overview"),
                posterUrl=File(source,"assets/"+j.getString("poster_file")).toURI().toString(),
                backdropUrl=File(source,"assets/"+j.getString("backdrop_file")).toURI().toString(),logoUrl=null,
                type=if(j.getString("type")=="tv")MediaType.TV_SHOW else MediaType.MOVIE,top10Rank=n+2,
                isTrending=true, releaseYear=j.getInt("year"))
        }
    }
    private fun motionClip(name:String,seconds:Int,events:Map<Int,()->Unit> = emptyMap()) {
        val dir=File(captureRoot(),name).apply{mkdirs()}
        var last=0L
        for(frame in 0 until seconds*30) {
            events[frame]?.invoke()
            val target=(frame+1)*1000L/30
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(target-last))
            rule.mainClock.advanceTimeBy(target-last,ignoreFrameDuration=true);last=target
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            if(frame%2==0) rule.onRoot().captureRoboImage(File(dir,"%05d.png".format(frame/2)).path)
            if(frame%60==0)System.err.println("FILM $name $frame/${seconds*30}")
        }
    }
    @Test fun nativeHomeMotion() {
        val catalogue=filmCatalog()
        com.example.data.CatalogData.allMedia=catalogue
        rule.setContent { NetflixTheme { Box(Modifier.fillMaxSize()) {
            if(motionRoute.value=="details") {
                com.example.ui.screens.DetailScreen(media=show,isInWatchlist=motionSaved.value,userSubscription=membership,
                    downloadProgressMap=emptyMap(),onClose={motionRoute.value="home"},onPlayClick={_,_->},
                    onPlayTrailerClick={_,_->},onWatchlistToggle={motionSaved.value=!motionSaved.value},
                    onDownloadClick={_,_->},onRatingSelect={},onSimilarMediaClick={})
            } else {
                HomeScreen(profiles[3],motionFilter.value,null,listOf(show to WatchProgressEntity(profileId="p3",mediaId="42",positionSeconds=300,totalSeconds=1800)),
                    isWatchlistContains={motionSaved.value},onMediaClick={motionRoute.value="details"},onPlayClick={motionRoute.value="details"},
                    onWatchlistToggle={motionSaved.value=!motionSaved.value},catalogMedia=catalogue,userSubscription=membership)
                NetflixTopBar(profiles[3],motionFilter.value,null,{ filter,_->motionFilter.value=filter},{},{},{},unreadNotificationCount=5)
                NetflixBottomNav(NavigationTab.HOME,profiles[3],{},modifier=Modifier.align(Alignment.BottomCenter))
            }
        } } }
        ready();capture("home");rule.mainClock.autoAdvance=false
        val actions=mutableMapOf<Int,()->Unit>()
        actions[45]={rule.onRoot().performTouchInput{down(androidx.compose.ui.geometry.Offset(600f,1450f))}}
        for(n in 46..60)actions[n]={rule.onRoot().performTouchInput{advanceEventTime(33);moveTo(androidx.compose.ui.geometry.Offset(600f,1450f-(n-45)*42f))}}
        actions[61]={rule.onRoot().performTouchInput{advanceEventTime(33);up()}}
        motionClip("home",8,actions)
        rule.runOnIdle{motionRoute.value="details"};motionClip("details",6);capture("details")
        rule.runOnIdle{motionSaved.value=true};motionClip("save",3)
    }
    @Test fun nativeProfileMotion() {
        rule.setContent { NetflixTheme {
            ProfilePickerSheet(profiles,profiles[3],show.copy(title="Umthetho",posterUrl=fixture("umthetho.jpg"),logoUrl=fixture("umthetho-logo.png")),membership,onSelectProfile={},onEditProfile={},onDismiss={})
        } }
        ready();rule.mainClock.autoAdvance=false
        motionClip("profile-picker",5,mapOf(60 to {rule.onNodeWithText("Edit").performClick()}));capture("profile-picker")
    }
    @Config(qualifiers="w895dp-h412dp-xhdpi", sdk=[34], application=Application::class)
    @Test fun nativePlayerMotion() {
        val state=mutableStateOf(com.example.ui.viewmodel.PlayerState(media=show,isPlaying=false,durationSec=1800,currentPositionSec=480,showControls=true,showSkipIntro=true))
        rule.setContent { NetflixTheme {
            com.example.ui.screens.VideoPlayerScreen(state.value,onClose={},onTogglePlayPause={state.value=state.value.copy(isPlaying=!state.value.isPlaying)},
                onSeek={},onSkipForward10={},onSkipBackward10={},onSkipIntro={},onSetSpeed={},onSetAudio={},onSetSubtitle={},
                onToggleLock={},onToggleControls={},onShowAudioSubtitles={},onShowEpisodesDrawer={},onPlayNextEpisode={},onSelectEpisode={})
        } }
        ready();rule.mainClock.autoAdvance=false;motionClip("player",4);capture("player")
    }

    @Test fun showcaseExtraScreens() {
        val page=mutableStateOf("search")
        val catalogue=filmCatalog();com.example.data.CatalogData.allMedia=catalogue
        val notices=listOf(com.example.data.model.NotificationItem("demo","Ready for your next story","Explore your saved titles.","Just now",mediaId=show.id,imageUrl=show.posterUrl))
        val game=com.example.data.model.GameItem("demo","Monument Valley","ustwo games","Puzzle","Everyone","420 MB","A journey through beautiful architecture.",bannerUrl=File("src/main/res/drawable/img_game_monument_valley_1787646003139.jpg").toURI().toString(),iconUrl=File("src/main/res/drawable/img_game_monument_valley_1787646003139.jpg").toURI().toString())
        rule.setContent { NetflixTheme { Box(Modifier.fillMaxSize().background(Color.Black)) {
          when(page.value) {
           "search" -> com.example.ui.screens.SearchScreen("Squid",null,listOf(show),membership,onQueryChange={},onGenreFilterSelect={},onMediaClick={},onPlayClick={})
           "new-hot" -> com.example.ui.screens.NewAndHotScreen(emptyList(),onToggleReminder={},onMediaClick={},onPlayClick={},onWatchlistToggle={},isWatchlistContains={false},upcomingReleases=catalogue.take(3),recentReleases=catalogue)
           "games" -> com.example.ui.screens.GamesScreen(listOf(game),{})
           "profile-edit" -> com.example.ui.screens.EditProfileScreen(profiles[3],{},{},{},{})
           "profile-setup" -> com.example.ui.screens.ProfileSetupWalkthroughScreen(profiles[3],{},{},{})
           "avatars" -> com.example.ui.screens.AvatarPickerSheet(null,{},{})
           "notifications" -> com.example.ui.screens.NotificationsSheet(notices,{},{},{},{},{})
           "cast" -> com.example.ui.screens.CastDialog(listOf(com.example.data.model.CastDevice("demo","Living Room TV")),null,{},{},{})
           "tv-pair" -> com.example.ui.screens.TvPairScreen(profiles[3],profiles,{_,_,_->},listOf(com.example.data.TvSessionData("DEMO00","PAIRED",deviceName="Living Room TV")),{},onBack={})
           "smart-downloads" -> com.example.ui.screens.SmartDownloadsScreen(profiles,profiles.associate{it.id to 1f},true,true,9_000_000_000,485,{},{},{},{_,_->})
           "membership" -> com.example.ui.screens.SubscriptionSheet(UserSubscription(),"demo",{},{})
           "download-upgrade" -> com.example.ui.components.DownloadMembershipDialog({},{})
           "auth" -> com.example.ui.screens.AuthScreen(null,{_,_,_,_->},{_,_,_,_->},{},{},{})
           "account" -> com.example.ui.screens.AuthScreen("demo@example.invalid",{_,_,_,_->},{_,_,_,_->},{},{},{})
           "settings" -> com.example.ui.screens.NetflixSettingsSheet(profiles[3],true,true,true,true,false,true,"Automatic",false,null,485,{},{},{},{},{},{},{},{},{},{},{},currentEmail="demo@example.invalid",userSubscription=membership)
          }
        } } }
        for(name in listOf("search","new-hot","games","profile-edit","profile-setup","avatars","notifications","cast","tv-pair","smart-downloads","membership","download-upgrade","auth","account","settings")) {
          rule.runOnIdle{page.value=name};ready();capture(name);System.err.println("SHOWCASE mobile captured $name")
          if(name in listOf("membership","settings")) {
           rule.onRoot().performTouchInput { swipe(start=androidx.compose.ui.geometry.Offset(400f,1400f),end=androidx.compose.ui.geometry.Offset(400f,500f),durationMillis=500) };ready();capture("$name-more")
          }
        }
    }
    @Config(qualifiers="w895dp-h412dp-xhdpi", sdk=[34], application=Application::class)
    @Test fun showcasePlayerComponents() {
        val state=mutableStateOf(com.example.ui.viewmodel.PlayerState(media=show,episode=show.episodes.first(),isPlaying=false,durationSec=3300,currentPositionSec=480,showControls=true,showSkipIntro=true))
        rule.setContent { NetflixTheme { com.example.ui.screens.VideoPlayerScreen(state.value,onClose={},onTogglePlayPause={},onSeek={},onSkipForward10={},onSkipBackward10={},onSkipIntro={},onSetSpeed={},onSetAudio={},onSetSubtitle={},onToggleLock={},onToggleControls={},onShowAudioSubtitles={},onShowEpisodesDrawer={},onPlayNextEpisode={},onSelectEpisode={},recommendations=listOf(show)) } }
        ready();capture("player-controls")
        rule.runOnIdle{state.value=state.value.copy(showAudioSubtitleDialog=true)};ready();capture("player-subtitles")
        rule.runOnIdle{state.value=state.value.copy(showAudioSubtitleDialog=false,showEpisodeDrawer=true)};ready();capture("player-episodes")
        rule.runOnIdle{state.value=state.value.copy(showEpisodeDrawer=false,hasEnded=true)};ready();capture("post-play")
    }

}

/** Render the real Clips controls without calling a remote trailer provider. */
@org.robolectric.annotation.Implements(value=com.example.data.TrailerResolver::class,isInAndroidSdk=false)
class ShowcaseTrailerResolverShadow {
    @org.robolectric.annotation.RealObject private lateinit var resolver:com.example.data.TrailerResolver
    @org.robolectric.annotation.Implementation fun start() {
        val field=com.example.data.TrailerResolver::class.java.getDeclaredField("callback").apply{isAccessible=true}
        (field.get(resolver) as com.example.data.TrailerResolverCallback).onError("Local illustrative capture")
    }
}
