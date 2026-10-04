package com.example

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancelChildren
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.example.model.Movie
import com.example.model.Profile
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

/** Opt-in production export. All visible UI and focus animation comes from Compose. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers="w1280dp-h720dp-hdpi", sdk=[34], application=Application::class, shadows=[ShowcaseVerifiedSessionShadow::class], instrumentedPackages=["com.example.ui.NetflixViewModel"])
class ShowcaseTvCaptureTest {
 @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
 private lateinit var vm:NetflixViewModel
 private lateinit var movies:List<Movie>
 private val route=mutableStateOf("home")
 private val root=File(System.getenv("NPRO_SHOWCASE_WORK") ?: "/workspace/artifacts/showcase-20261004", "assets/native-tv")
 @Before fun prepare() {
  assumeTrue("Set NETFLIXPRO_CAPTURE=1 to export film frames",System.getenv("NETFLIXPRO_CAPTURE")=="1")
  val app=ApplicationProvider.getApplicationContext<Application>()
  coil.Coil.setImageLoader(coil.ImageLoader.Builder(app).build())
  com.example.ui.util.HomeStartupGate.markHomeHidden()
  val assets=File("../advertising-video/assets")
  val catalog=JSONArray(File("../advertising-video/catalogue.json").readText())
  movies=(0 until catalog.length()).map { n ->
   val j=catalog.getJSONObject(n)
   Movie("film_${j.getString("id")}",j.getString("title"),j.getString("overview"),
    File(assets,j.getString("backdrop_file")).toURI().toString(),File(assets,j.getString("poster_file")).toURI().toString(),
    rating="16+",year=j.get("year").toString(),type=if(j.getString("type")=="tv")"Series" else "Movie",duration=if(j.getString("type")=="tv")"3 Seasons" else "2h 32m")
  }
  if(com.google.firebase.FirebaseApp.getApps(app).isEmpty()) {
   com.google.firebase.FirebaseApp.initializeApp(app,com.google.firebase.FirebaseOptions.Builder().setApplicationId("1:123:android:demo").setApiKey("AIzaSyDEMO0000000000000000000000000000000").setProjectId("demo-netflixpro").build())
   com.google.firebase.auth.FirebaseAuth.getInstance().useEmulator("127.0.0.1",9999)
   com.google.firebase.firestore.FirebaseFirestore.getInstance().useEmulator("127.0.0.1",9998)
  }
  vm=NetflixViewModel(app)
  vm.viewModelScope.coroutineContext.cancelChildren()
  seed("_selectedProfile",Profile("film","Home",autoplayPreviews=false))
  seed("_profiles",listOf(Profile("film","Home",autoplayPreviews=false),Profile("kids","Kids",isKid=true,autoplayPreviews=false)))
  seed("_categoryRows",listOf("Trending Now" to movies,"Popular Movies" to movies.filter{it.type=="Movie"},"TV Shows" to movies.filter{it.type=="Series"},"Your next story" to movies.reversed()))
  seed("_isLoading",false)
  seed("_userSubscription",com.example.model.UserSubscription(planId="plan_premium",status="ACTIVE",expiresAt=System.currentTimeMillis()+86_400_000))
  seed("_myListMovieIds",movies.take(3).map{it.id}.toSet())
  root.mkdirs()
 }
 @Suppress("UNCHECKED_CAST") private fun <T> seed(name:String,value:T) {
  val f=NetflixViewModel::class.java.getDeclaredField(name).apply{isAccessible=true}
  (f.get(vm) as MutableStateFlow<T>).value=value
 }
 private fun mount() {
  rule.mainClock.autoAdvance=false
  rule.setContent { NetflixProTheme {
   androidx.compose.runtime.key(route.value) {
   if(route.value=="home") HomeScreen(vm,onPlayMovie={route.value="details"},onMovieClick={route.value="details"},onCategoryClick={})
   else if(route.value=="player") NativePlayer()
   else if(route.value=="episodes") NativeEpisodes()
   else if(route.value.startsWith("extra:")) Extra(route.value.removePrefix("extra:"))
   else com.example.ui.screens.details.DetailsScreen(movies.first {it.type=="Movie"},onBack={route.value="home"},onPlayMovie={_,_,_,_->},onPlayTrailer={_,_,_,_->},onNavigateToDetails={},viewModel=vm)
   }
  } }
  rule.mainClock.advanceTimeBy(1500)
  repeat(50){ShadowLooper.idleMainLooper();Thread.sleep(25)}
  rule.mainClock.advanceTimeBy(100)
  key(KeyEvent.KEYCODE_DPAD_DOWN);rule.mainClock.advanceTimeBy(900)
 }
 private fun key(code:Int) {
  rule.runOnUiThread {
   val now=SystemClock.uptimeMillis()
   rule.activity.dispatchKeyEvent(KeyEvent(now,now,KeyEvent.ACTION_DOWN,code,0))
   rule.activity.dispatchKeyEvent(KeyEvent(now,now,KeyEvent.ACTION_UP,code,0))
  }
 }
 private fun still(name:String) {
  repeat(3) {
   org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1000))
   rule.mainClock.advanceTimeBy(1000);ShadowLooper.idleMainLooper();rule.waitForIdle()
  }
  rule.onRoot().captureRoboImage(File(root,"$name.png").path)
 }
 private fun clip(name:String,seconds:Int,events:Map<Int,()->Unit> = emptyMap()) {
  val dir=File(root,name).apply{mkdirs()};var previous=0L
  for(frame in 0 until seconds*30){
   events[frame]?.invoke()
   val target=((frame+1)*1000L)/30
   rule.mainClock.advanceTimeBy(target-previous,ignoreFrameDuration=true); previous=target
   ShadowLooper.idleMainLooper()
   if(frame%2==0) rule.onRoot().captureRoboImage(File(dir,"%05d.png".format(frame/2)).path)
   if(frame%60==0)System.err.println("FILM $name frame $frame/${seconds*30}")
  }
 }

 @androidx.compose.runtime.Composable private fun NativePlayer() {
  val player=androidx.compose.runtime.remember { androidx.media3.exoplayer.ExoPlayer.Builder(rule.activity).build() }
  androidx.compose.runtime.DisposableEffect(player){onDispose{player.release()}}
  val play=androidx.compose.runtime.remember{androidx.compose.ui.focus.FocusRequester()}
  val progress=androidx.compose.runtime.remember{androidx.compose.ui.focus.FocusRequester()}
  androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Color.Black)) {
   com.example.ui.screens.player.PlayerTopBar(movies.first{it.type=="Series"},1,1,"The beginning",null,true,true,false,{},{},{})
   androidx.compose.foundation.layout.Box(Modifier.align(androidx.compose.ui.Alignment.BottomCenter)) {
    com.example.ui.screens.player.PlayerBottomControls(player,true,null,emptyList(),movie=movies.first{it.type=="Series"},currentSeason=1,currentEpisode=1,isTvShow=true,selectedSubLang="Off",playButtonRequester=play,progressRequester=progress,onTogglePlay={},onUserActivity={},onSelectSubtitle={},onOpenSubtitleSettings={})
   }
   androidx.compose.runtime.LaunchedEffect(Unit){play.requestFocus()}
  }
 }
 @androidx.compose.runtime.Composable private fun NativeEpisodes() {
  val focus=androidx.compose.runtime.remember{androidx.compose.ui.focus.FocusRequester()}
  val selected=androidx.compose.runtime.remember{androidx.compose.runtime.mutableIntStateOf(1)}
  val episodes=(1..8).map{com.example.model.Episode(it.toLong(),it,1,"Episode $it","A new chapter unfolds.",movies[it%movies.size].backdropUrl,"52m")}
  androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().background(Color.Black).then(Modifier)) {
   androidx.compose.material3.Text("Episodes",color=Color.White,modifier=Modifier, fontSize=androidx.compose.ui.unit.TextUnit(28f,androidx.compose.ui.unit.TextUnitType.Sp))
   com.example.ui.screens.details.EpisodesRowSection(episodes,1,selected.intValue,continueWatchingData=null,episodesFocusRequester=focus)
   androidx.compose.runtime.LaunchedEffect(Unit){focus.requestFocus()}
  }
 }
 @Test fun nativePlayer(){mount();rule.runOnIdle{route.value="player"};clip("player",4);still("player")}
 @Test fun nativeEpisodes(){mount();rule.runOnIdle{route.value="episodes"};rule.mainClock.advanceTimeBy(900);clip("episodes",6,mapOf(30 to {key(KeyEvent.KEYCODE_DPAD_RIGHT)},90 to {key(KeyEvent.KEYCODE_DPAD_RIGHT)}));still("episodes")}
 @Test fun nativeHomePreview(){mount();still("home")}
 @Test fun nativeDiscovery(){
  mount();still("home");
  clip("home",4)
  key(KeyEvent.KEYCODE_DPAD_DOWN);rule.mainClock.advanceTimeBy(1200);still("categories")
  clip("categories",7,mapOf(30 to {key(KeyEvent.KEYCODE_DPAD_RIGHT)},80 to {key(KeyEvent.KEYCODE_DPAD_RIGHT)},130 to {key(KeyEvent.KEYCODE_DPAD_RIGHT)}))
  key(KeyEvent.KEYCODE_DPAD_DOWN);rule.mainClock.advanceTimeBy(1200);still("rows")
  clip("rows",8,mapOf(30 to {key(KeyEvent.KEYCODE_DPAD_RIGHT)},85 to {key(KeyEvent.KEYCODE_DPAD_RIGHT)},140 to {key(KeyEvent.KEYCODE_DPAD_RIGHT)}))
  clip("vertical",7,mapOf(30 to {key(KeyEvent.KEYCODE_DPAD_DOWN)},85 to {key(KeyEvent.KEYCODE_DPAD_DOWN)},140 to {key(KeyEvent.KEYCODE_DPAD_UP)}))
 }
 @Test fun nativeDetails(){mount();rule.runOnIdle{route.value="details"};clip("details",6);still("details")}

 @androidx.compose.runtime.Composable private fun Extra(page:String) {
  val player=androidx.compose.runtime.remember { androidx.media3.exoplayer.ExoPlayer.Builder(rule.activity).build() }
  androidx.compose.runtime.DisposableEffect(player){onDispose{player.release()}}
  val focus=androidx.compose.runtime.remember{androidx.compose.ui.focus.FocusRequester()}
  val nav=androidx.compose.runtime.remember{androidx.compose.ui.focus.FocusRequester()}
  androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Color.Black)) {
   when(page) {
    "profiles" -> com.example.ui.screens.ProfileScreen(vm,{},{})
    "profile-edit" -> com.example.ui.screens.EditProfileScreen("film",vm,{})
    "profile-setup" -> com.example.ui.screens.ProfileSetupWalkthroughScreen(vm,{}, {})
    "category" -> com.example.ui.screens.CategoryScreen("TV Shows",vm,{},{},{})
    "ambient" -> com.example.ui.screens.AmbientWallpaperScreen(movies,{})
    "auth" -> com.example.ui.screens.TvAuthScreen(vm,{}, {})
    "search" -> com.example.ui.components.SearchSection(vm,movies,{},{},focus,nav)
    "membership" -> com.example.ui.screens.details.UpgradePlanModal("Guest","Choose a plan for your TV.",{},{_,_->},{})
    "subtitles" -> com.example.ui.screens.player.AudioSubtitlesModal(player,androidx.media3.common.Tracks.EMPTY,null,"Off",{},{},{})
    "details-info", "recommendations", "full-episodes", "extras" -> com.example.ui.screens.details.DetailsModalOverlay(
      movie=movies.first{it.type=="Series"},isTvSeries=true,
      activeTabName=when(page){"details-info"->"Details";"recommendations"->"More like this";"full-episodes"->"Episodes";else->"Previews & Extras"},
      onActiveTabChange={},onCloseModal={},episodesList=(1..8).map{com.example.model.Episode(it.toLong(),it,1,"Episode $it","A new chapter unfolds.",movies[it%movies.size].backdropUrl,"52m")},
      episodesLoading=false,onRetryEpisodes={},currentSeason=1,currentEpisode=1,availableSeasons=listOf(1,2,3),continueWatchingData=null,
      onSeasonSelected={},onEpisodeClick={},extraInfo=com.example.ui.screens.details.getMovieExtraInfo(movies.first()),recommendations=movies,onNavigateToDetails={},
      exoPlayer=player,streamCaptions=emptyList(),selectedSubLang="Off",onSetSelectedSubLang={},onPlayTrailer={_,_,_,_->},modalUpRequester=focus)
   }
  }
 }
 @Test fun showcaseExtraScreens(){
  mount()
  for(page in listOf("profiles","profile-edit","profile-setup","category","search","auth","membership","subtitles","details-info","recommendations","full-episodes","extras","ambient")) {
   rule.runOnIdle{route.value="extra:$page"}
   rule.mainClock.advanceTimeBy(1800); repeat(75){ShadowLooper.idleMainLooper();Thread.sleep(25)}
   still(page);System.err.println("SHOWCASE TV captured $page")
  }
 }

}

@org.robolectric.annotation.Implements(value = NetflixViewModel::class, isInAndroidSdk = false)
class ShowcaseVerifiedSessionShadow {
    @org.robolectric.annotation.RealObject private lateinit var viewModel: NetflixViewModel
    @org.robolectric.annotation.Implementation fun isUserLoggedInOrGuest(): Boolean = sessionReady
    @org.robolectric.annotation.Implementation fun isMovieLocked(movie: Movie): Boolean = viewModel.userSubscription.value.isMovieLocked(
        movieId = movie.id, releaseYear = movie.year, isTrendingOrVip = false,
        isTvDevice = true, movieTitle = movie.title
    )
    companion object { @JvmField var sessionReady = true }
}
