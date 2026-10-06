package com.example

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.sync.SyncScheduler
import com.example.ui.util.HomeStartupGate
import com.example.ui.util.TvImagePolicy
import com.google.firebase.FirebaseApp
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class NetflixApplication : Application(), ImageLoaderFactory, Configuration.Provider {
    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Custom WorkManager config. We keep the default executor and only
     * tighten logging to INFO so the `adb logcat` output isn't spammed
     * with WorkManager's debug chatter on TV. `Configuration.Provider` is
     * implemented below; WorkManager will pick this up at first
     * `WorkManager.getInstance(...)` call.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        HomeStartupGate.configureLowMemory(TvImagePolicy.isLowMemoryDevice(this))
        com.example.ui.util.AppDiagnosticsLogger.init(this)
        com.example.ui.util.AppDiagnosticsLogger.event("AppStartup", "Application onCreate launched.")
        // Firebase first — its analytics transport warms up early, and any
        // crash from here is captured by Crashlytics if installed.
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this)
            }
        } catch (e: Exception) {
            android.util.Log.e("NetflixApp", "Firebase init error: ${e.message}")
        }
        // Schedule the periodic offline-first outbox worker. The one-shot
        // path is triggered by `SyncScheduler.enqueueWrite` from the
        // ViewModel; the periodic path is the safety net for any rows
        // that were enqueued while the app was force-killed before the
        // one-shot could run. See PendingWriteRetryWorker for retry
        // policy and SyncScheduler for the scheduling helpers.
        // Initializing WorkManager opens its database and creates executors. The
        // periodic safety net can wait until Home has drawn and input is quiet.
        // One-shot writes still schedule their durable retry immediately.
        maintenanceScope.launch {
            HomeStartupGate.awaitIdle()
            try {
                SyncScheduler.schedulePeriodic(this@NetflixApplication)
            } catch (e: Exception) {
                android.util.Log.e("NetflixApp", "SyncScheduler.schedulePeriodic failed: ${e.message}")
            }
        }
    }

    /**
     * Single source of truth for the global Coil [ImageLoader].
     *
     * Tuning rationale (TV / leanback):
     *  - `okHttpClient` timeouts kept short (5/12/12s) so a slow mirror never
     *    stalls the first-paint of Home rows.
     *  - `User-Agent` header prevents image hosts (TMDB, Netflix mirrors) from
     *    returning 403 to unidentified clients.
     *  - `crossfade(false)` — TV doesn't benefit from a 150ms fade on every
     *    bitmap swap, and the fade is the most expensive frame on weaker SoCs.
     *  - `bitmapConfig(RGB_565)` — halves the bitmap memory footprint vs
     *    ARGB_8888. For poster art (no alpha) the visual loss is invisible.
     *  - `respectCacheHeaders(false)` — the client controls TTL, not the
     *    server. Server Cache-Control is wildly inconsistent across mirrors.
     *  - Bound decoded artwork to 12% / 16MiB on low-RAM TVs and 18% / 32MiB
     *    elsewhere. The rest of the heap is shared by Compose, catalog and video.
     *  - Decode one image at a time so the hero and first poster row do not
     *    allocate bitmaps together during the first Home frames.
     *  - Keep compressed files in the existing 100MiB disk cache for repeat visits.
     *
     * This [ImageLoaderFactory] hook is the ONLY place the loader is built.
     * `MainActivity.onCreate` previously called `Coil.setImageLoader(...)` with
     * a *different* (35% memory, 100MB disk, no RGB_565) builder, which shadowed
     * this factory and made the two configs drift silently. The MainActivity
     * duplicate has been removed in the same audit pass.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun newImageLoader(): ImageLoader {
        val isLowRamDevice = TvImagePolicy.isLowMemoryDevice(this)
        return ImageLoader.Builder(this)
            // The lazy overload builds TLS/client state on Coil's fetch thread,
            // rather than on the main thread during the first AsyncImage.
            .okHttpClient {
                val imageDispatcher = okhttp3.Dispatcher().apply {
                    maxRequests = if (isLowRamDevice) 2 else 4
                    maxRequestsPerHost = maxRequests
                }
                OkHttpClient.Builder()
                    .dispatcher(imageDispatcher)
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(12, TimeUnit.SECONDS)
                    .writeTimeout(12, TimeUnit.SECONDS)
                    .addInterceptor { chain ->
                        val req = chain.request().newBuilder()
                            .header("User-Agent", "NetflixProTV/1.0 (Android TV)")
                            .build()
                        chain.proceed(req)
                    }
                    .build()
            }
            // Also bound compressed-file copying after the HTTP response arrives.
            .fetcherDispatcher(Dispatchers.IO.limitedParallelism(if (isLowRamDevice) 1 else 2))
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizeBytes(TvImagePolicy.memoryCacheBytes(Runtime.getRuntime().maxMemory(), isLowRamDevice))
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            .bitmapConfig(Bitmap.Config.RGB_565)
            .allowRgb565(true)
            .bitmapFactoryMaxParallelism(1)
            .crossfade(false)
            .respectCacheHeaders(false)
            .build()
    }
}
