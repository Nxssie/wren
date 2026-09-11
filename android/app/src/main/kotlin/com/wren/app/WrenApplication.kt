package com.wren.app

import android.app.Application
import api.HttpStreamResolver
import api.Streams
import auth.OAuthConfig
import com.wren.app.player.ExoPlayerEngine
import com.wren.app.ui.globalDark
import com.wren.app.util.ThemePreference
import util.AppDirs
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
        OAuthConfig.clientId = BuildConfig.WREN_GOOGLE_CLIENT_ID
        OAuthConfig.clientSecret = BuildConfig.WREN_GOOGLE_CLIENT_SECRET
        // The theme is a user preference, not screen state: without this it flipped back to the
        // default on every cold start and on any activity recreate.
        ThemePreference.load()?.let { globalDark = it }
        Streams.resolver = HttpStreamResolver()
        engine = ExoPlayerEngine(this)
        engine.start()
    }
}
