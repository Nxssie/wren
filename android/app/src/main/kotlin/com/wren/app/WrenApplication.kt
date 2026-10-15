package com.wren.app

import android.app.Application
import api.HttpStreamResolver
import api.Streams
import api.loadProtectedStreams
import auth.OAuthConfig
import com.wren.app.player.ExoPlayerEngine
import com.wren.app.ui.globalDark
import com.wren.app.ui.allowSoundCloudDownloads
import com.wren.app.update.ApkInstaller
import com.wren.app.widget.WrenWidgetUpdater
import provider.LibraryWarmup
import update.UpdateChecker
import util.AppDirs
import util.DownloadPreference
import util.ThemePreference
import java.io.File

/**
 * Wires the platform pieces the shared code cannot know about: app-private storage for
 * its config/state files and the HTTP stream resolver (Android cannot spawn yt-dlp).
 */
class WrenApplication : Application() {

    lateinit var engine: ExoPlayerEngine
        private set

    lateinit var apkInstaller: ApkInstaller
        private set

    lateinit var updateChecker: UpdateChecker
        private set

    override fun onCreate() {
        super.onCreate()
        // Lets chrome://inspect (or the DevTools protocol over adb) reach the login WebView.
        if (BuildConfig.DEBUG) android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        AppDirs.init(
            configDir = File(filesDir, "config"),
            stateDir = File(filesDir, "state"),
        )
        // Before any listing is parsed, so protected tracks are locked from the first list.
        loadProtectedStreams()
        OAuthConfig.clientId = BuildConfig.WREN_GOOGLE_CLIENT_ID
        OAuthConfig.clientSecret = BuildConfig.WREN_GOOGLE_CLIENT_SECRET
        // The theme is a user preference, not screen state: without this it flipped back to the
        // default on every cold start and on any activity recreate.
        ThemePreference.load()?.let { globalDark = it }
        // Saving SoundCloud tracks is off unless it has been asked for; see DownloadPreference.
        allowSoundCloudDownloads = DownloadPreference.load()
        Streams.resolver = HttpStreamResolver()
        engine = ExoPlayerEngine(this)
        engine.start()
        // The updater is glue the shared code cannot build for itself: it needs the installed
        // version, which only the platform knows. An unreadable version becomes null, which the
        // checker treats as "offer nothing" (covered by its own tests). The real clock and the
        // default fetch are injected here precisely so no HTTP call is reimplemented on Android.
        apkInstaller = ApkInstaller(this)
        updateChecker = UpdateChecker(
            currentVersion = installedVersionName(),
            now = System::currentTimeMillis,
        )
        // A leftover APK from a previous run is dead weight; clearing it at startup keeps the
        // cache from accumulating a sibling per launch.
        apkInstaller.clearStale()
        // Repaints the home-screen widget from the engine's state; starts with no widget placed too,
        // because the collector only reacts to track/transport changes, not to the clock.
        WrenWidgetUpdater.start(this)
        // The library costs a walk over every page of it; build it while the user is elsewhere.
        LibraryWarmup.start()
    }

    /** The installed `versionName`; null when it cannot be read, which offers no update. */
    private fun installedVersionName(): String? = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull()
}
