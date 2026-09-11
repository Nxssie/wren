package com.wren.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.wren.app.ui.WrenApp

/**
 * Deliberately owns no teardown: the engine lives in [WrenApplication] and the playback
 * service is a foreground service, so finishing this activity (back, or the task going away)
 * must leave playback and its notification running. Releasing here is what used to silence
 * the app the moment the user left it.
 */
class MainActivity : ComponentActivity() {

    /** Raised by a tap on the media widget (see WrenPlaybackService), consumed by [WrenApp]. */
    private val openNowPlaying = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val engine = (application as WrenApplication).engine
        // A relaunch replays the launch intent; only a real launch moves the tab, so the
        // screen the user was on is not yanked away from under them.
        if (savedInstanceState == null) openNowPlaying.value = intent.opensNowPlaying()
        setContent {
            RequestNotificationPermission()
            WrenApp(engine, openNowPlaying)
        }
    }

    /** Already on screen (launchMode="singleTop"): a widget tap arrives here instead. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.opensNowPlaying()) openNowPlaying.value = true
    }

    companion object {
        /** Marks the media widget's content intent; anything else lands on the home tab. */
        const val ACTION_NOW_PLAYING = "com.wren.app.action.NOW_PLAYING"
    }
}

private fun Intent?.opensNowPlaying(): Boolean = this?.action == MainActivity.ACTION_NOW_PLAYING

/** Android 13+ needs an explicit grant for the playback notification to be visible. */
@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
