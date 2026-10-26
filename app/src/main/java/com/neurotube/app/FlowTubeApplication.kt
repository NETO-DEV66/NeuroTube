package com.neurotube.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.neurotube.app.account.YouTubeAccountRepository
import com.neurotube.app.data.local.CONTENT_LANGUAGE_FOLLOW_APP
import com.neurotube.app.data.local.PlayerPreferences
import com.neurotube.app.data.local.SubscriptionRepository
import com.neurotube.app.data.repository.NewPipeDownloader
import com.neurotube.app.data.repository.YouTubeRepository
import com.neurotube.app.discord.DiscordPresenceRuntime
import com.neurotube.app.innertube.YouTube
import com.neurotube.app.innertube.models.YouTubeLocale
import com.neurotube.app.innertube.models.normalizeYouTubeHostLanguage
import com.neurotube.app.network.AppProxyManager
import com.neurotube.app.network.VpnStateMonitor
import com.neurotube.app.notification.NotificationHelper
import com.neurotube.app.notification.SubscriptionCheckWorker
import com.neurotube.app.utils.AppLanguageManager
import com.neurotube.app.utils.FlowCrashHandler
import com.neurotube.app.utils.PerformanceDispatcher
import com.neurotube.app.utils.cipher.PipePipeNsigDecoder
import com.neurotube.app.utils.newPipeContentCountry
import com.neurotube.app.utils.newPipeLocalization
import com.neurotube.app.utils.normalizeYouTubeCountry
import com.neurotube.app.utils.potoken.NewPipePoTokenProvider
import com.neurotube.app.utils.potoken.VisitorIdentityStore
import com.neurotube.app.utils.potoken.WebPoTokenSession
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.conscrypt.Conscrypt
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import java.security.Security
import java.util.Locale
import javax.inject.Inject

@HiltAndroidApp
class FlowTubeApplication :
    Application(),
    SingletonImageLoader.Factory {
    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var vpnStateMonitor: VpnStateMonitor

    @Inject
    lateinit var visitorIdentityStore: VisitorIdentityStore

    @Inject
    lateinit var accountImportCoordinator: com.neurotube.app.account.AccountLibraryImportCoordinator

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader

    companion object {
        private const val TAG = "FlowTubeApplication"
        lateinit var appContext: Context
            private set
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguageManager.wrapContext(base, AppLanguageManager.resolveLanguageTag(base)))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        YouTube.cacheDirectory = cacheDir.resolve("innertube_http_cache")

        // Restores a saved YouTube session before the first request can need it.
        YouTubeAccountRepository.initialize(this)

        // Watches for a signed-in account and brings its library in automatically, once.
        accountImportCoordinator.start()

        DiscordPresenceRuntime.initialize(this, okHttpClient)

        val playerPreferences = PlayerPreferences(this)

        // Injects modern TLS/SSL certificates so OkHttp and Ktor don't crash
        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.N_MR1) {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        }

        // Install crash handler for real-time monitoring
        FlowCrashHandler.install(this)

        try {
            // Seeded from the device locale so the very first extraction is already localized; the
            // stored app-language/region preference takes over as soon as it loads below.
            val localization = newPipeLocalization(Locale.getDefault().toLanguageTag())
            NewPipe.init(
                NewPipeDownloader.getInstance(this),
                localization,
                newPipeContentCountry(Locale.getDefault().country),
            )
            YoutubeStreamExtractor.setPoTokenProvider(NewPipePoTokenProvider)
            Log.d(TAG, "NewPipe initialized with ${localization.localizationCode}")
        } catch (e: Exception) {
            // Log error but don't crash the app
            Log.e(TAG, "Failed to initialize NewPipe", e)
        }

        try {
            com.neurotube.app.utils.cipher.CipherDeobfuscator
                .initialize(this)
            Log.d(TAG, "CipherDeobfuscator initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize CipherDeobfuscator", e)
        }

        PipePipeNsigDecoder.initialize(this)

        // Initialize notification channels
        NotificationHelper.createNotificationChannels(this)
        Log.d(TAG, "Notification channels created")

        /*
        try {
            // Initialize YoutubeDL
            com.yausername.youtubedl_android.YoutubeDL.getInstance().init(this)
            Log.d(TAG, "YoutubeDL initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize YoutubeDL", e)
        }
         */

        // Schedule periodic subscription checks for new videos
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val savedIntervalMinutes = playerPreferences.subscriptionCheckIntervalMinutes.first()
            SubscriptionCheckWorker.schedulePeriodicCheck(
                this@FlowTubeApplication,
                intervalMinutes = savedIntervalMinutes.toLong(),
            )

            // Schedule periodic update checks (every 12 hours) — github flavor only
            if (BuildConfig.UPDATER_ENABLED) {
                com.neurotube.app.notification.UpdateCheckWorker
                    .schedulePeriodicCheck(this@FlowTubeApplication)
            }
        }

        Log.d(TAG, "Workers scheduled successfully")

        // Fetch and cache visitor data for the lifetime of the install.
        // The X-Goog-Visitor-Id header prevents YouTube from returning empty
        // search results on tablets and fresh Android 16 installs (Issue #223).
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var nsigWarmed = false
            playerPreferences.proxyConfig
                .distinctUntilChanged()
                .flatMapLatest { config ->
                    if (config.watchesVpn()) {
                        vpnStateMonitor.vpnActive().map { vpnActive -> config to vpnActive }
                    } else {
                        flowOf(config to false)
                    }
                }.collectLatest { (proxyConfig, vpnActive) ->
                    applyProxyConfig(proxyConfig, vpnActive)
                    // Ordered after the first proxy application so the warm-up honours it. Resolving
                    // the remote n-decoder player id is a round trip that the first video of a session
                    // would otherwise pay on its path to first frame; it is persisted for 24h, so on
                    // most launches this is only a disk read.
                    if (!nsigWarmed) {
                        nsigWarmed = true
                        PipePipeNsigDecoder.warmUp()
                    }
                }
        }

        YouTube.onVisitorDataChanged = visitorIdentityStore::save
        WebPoTokenSession.bindIdentityStore(visitorIdentityStore)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // A signed-in session owns its own identity: the visitor data it was minted with is
                // what InnerTube expects echoed back, so the anonymous one must not replace it.
                if (YouTubeAccountRepository.isSignedIn) {
                    Log.d(TAG, "visitorData kept: a YouTube account is signed in")
                } else {
                    val restored = visitorIdentityStore.restore()
                    if (restored != null) {
                        YouTube.visitorData = restored
                        Log.d(TAG, "visitorData restored")
                    } else {
                        YouTube
                            .visitorData()
                            .onSuccess { data ->
                                if (!data.isNullOrEmpty()) {
                                    YouTube.visitorData = data
                                    Log.d(TAG, "visitorData fetched")
                                }
                            }.onFailure { e ->
                                Log.w(TAG, "visitorData fetch failed: ${e.message}")
                            }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "visitorData init error: ${e.message}")
            }
            try {
                WebPoTokenSession.prewarm()
            } catch (e: Exception) {
                Log.w(TAG, "WebPoTokenSession prewarm failed: ${e.message}")
            }
            // A cold player script is a 3 MB download the first web-client extraction would
            // otherwise wait on.
            com.neurotube.app.utils.cipher.CipherDeobfuscator
                .ensureSignatureTimestamp()
        }

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            AppLanguageManager.languageAdoptedFromSystem.filterNotNull().collect(playerPreferences::setAppLanguage)
        }

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            combine(
                playerPreferences.appLanguage,
                playerPreferences.contentLanguage,
                playerPreferences.trendingRegion,
            ) { appLanguage, contentLanguage, region ->
                val language = if (contentLanguage == CONTENT_LANGUAGE_FOLLOW_APP) appLanguage else contentLanguage
                YouTubeLocale(gl = normalizeYouTubeCountry(region), hl = normalizeYouTubeHostLanguage(language))
            }.collectLatest { newLocale ->
                YouTube.locale = newLocale
                NewPipe.setupLocalization(
                    newPipeLocalization(newLocale.hl),
                    ContentCountry(newLocale.gl),
                )
                Log.d(TAG, "Dynamic YouTube Locale updated: gl=${newLocale.gl}, hl=${newLocale.hl}")
            }
        }

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var lastRegion: String? = null
            playerPreferences.trendingRegion.collectLatest { region ->
                if (lastRegion != null && lastRegion != region) {
                    // The anonymous identity is region-scoped, but a signed-in session's identity
                    // belongs to the account and is what its requests are validated against.
                    if (YouTubeAccountRepository.isSignedIn) {
                        Log.d(TAG, "Trending region changed to $region; keeping the account's visitor data.")
                    } else {
                        Log.d(TAG, "Trending region changed from $lastRegion to $region. Invalidate visitor data.")
                        YouTube.visitorData = null

                        YouTube
                            .visitorData()
                            .onSuccess { data ->
                                if (!data.isNullOrEmpty()) {
                                    YouTube.visitorData = data
                                    Log.d(TAG, "Fresh visitorData fetched for region: $region")
                                }
                            }.onFailure { e ->
                                Log.w(TAG, "Failed to fetch fresh visitorData: ${e.message}")
                            }
                    }
                }
                lastRegion = region
            }
        }

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repository = SubscriptionRepository.getInstance(this@FlowTubeApplication)
                val youtubeRepository = YouTubeRepository.getInstance(playerPreferences)
                val repaired =
                    repository.repairVideoThumbnailSubscriptions { channelId ->
                        // Startup's own fetches hold the InnerTube connections for a while; nothing waits on this.
                        withTimeoutOrNull(20_000L) {
                            youtubeRepository.fetchChannelAvatarById(channelId)
                        }.orEmpty()
                    }
                if (repaired > 0) {
                    Log.i(TAG, "Repaired $repaired subscription thumbnails")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Subscription thumbnail repair failed: ${e.message}")
            }
        }
    }

    private fun applyProxyConfig(
        config: com.neurotube.app.network.AppProxyConfig,
        vpnActive: Boolean,
    ) {
        AppProxyManager.update(config, vpnActive)
        YouTube.proxy = AppProxyManager.currentProxy()
        YouTube.proxyAuth = AppProxyManager.currentHttpProxyAuthorizationHeader()
    }

    override fun onTerminate() {
        DiscordPresenceRuntime.shutdown()
        super.onTerminate()
        // Clean up performance dispatcher resources
        PerformanceDispatcher.shutdown()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        FlowCrashHandler.recordPhase("memory", "FlowTubeApplication.onLowMemory")
        releaseVolatileMemory()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        FlowCrashHandler.recordPhase("memory", "FlowTubeApplication.onTrimMemory level=$level")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            releaseVolatileMemory()
        }
    }

    private fun releaseVolatileMemory() {
        if (::imageLoader.isInitialized) {
            imageLoader.memoryCache?.clear()
        }
        if (::okHttpClient.isInitialized) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                okHttpClient.connectionPool.evictAll()
            }
        }
    }
}
