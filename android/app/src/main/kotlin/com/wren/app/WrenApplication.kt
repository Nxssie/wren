package com.wren.app

import android.app.Application
import api.HttpStreamResolver
import api.Streams
import auth.OAuthConfig
import com.wren.app.player.ExoPlayerEngine
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
        Streams.resolver = HttpStreamResolver()
        engine = ExoPlayerEngine(this)
        engine.start()
    }
}
