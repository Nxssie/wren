package com.wren.app

import android.app.Application
import api.HttpStreamResolver
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import api.SoundCloud
import api.SoundCloudLikes
import api.Streams
import api.loadProtectedStreams
import auth.OAuthConfig
import com.wren.app.player.ExoPlayerEngine
import com.wren.app.ui.globalDark
import com.wren.app.ui.allowSoundCloudDownloads
import com.wren.app.util.DataDomeChallenge
import com.wren.app.util.WebWrite
import com.wren.app.widget.WrenWidgetUpdater
import provider.LibraryWarmup
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
        // SoundCloud guards its write endpoints with DataDome, which turns down requests that do
        // not look like the web player's; this repeats them from a real page when that happens.
        SoundCloud.webWriteFallback = { method, url, token -> WebWrite.perform(this, method, url, token) }
        // When even that is refused with a captcha, only a person can clear it.
        SoundCloud.challengeSolver = { url -> DataDomeChallenge.solve(this, url) }
        // The heart rolls back by itself; this is what says why it did.
        SoundCloudLikes.onRefused = { liked ->
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    this,
                    if (liked) "SoundCloud refused the like" else "SoundCloud refused removing the like",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        engine = ExoPlayerEngine(this)
        engine.start()
        // Repaints the home-screen widget from the engine's state; starts with no widget placed too,
        // because the collector only reacts to track/transport changes, not to the clock.
        WrenWidgetUpdater.start(this)
        // The library costs a walk over every page of it; build it while the user is elsewhere.
        LibraryWarmup.start()
    }
}
