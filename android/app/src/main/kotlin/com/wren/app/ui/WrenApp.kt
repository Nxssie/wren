package com.wren.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.BottomNavigation
import androidx.compose.material.BottomNavigationItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import auth.AuthEvents
import auth.GoogleAuth
import auth.SoundCloudAuth
import player.PlayerEngine
import provider.Platform
import provider.Providers

private enum class WrenTab(val label: String, val icon: ImageVector) {
    SEARCH("search", Icons.Default.Search),
    DISCOVER("discover", Icons.Default.Explore),
    NOW_PLAYING("now playing", Icons.Default.PlayCircleFilled),
}

@Composable
fun WrenApp(engine: PlayerEngine) {
    var tab by remember { mutableStateOf(WrenTab.SEARCH) }
    var platform by remember { mutableStateOf(Platform.YOUTUBE) }
    var showAccounts by remember { mutableStateOf(false) }
    var showSoundcloudLogin by remember { mutableStateOf(false) }
    val authVersion by AuthEvents.version.collectAsState()
    val provider = remember(platform) { Providers.of(platform) }

    WrenTheme {
        Scaffold(
            backgroundColor = Background,
            bottomBar = {
                Column {
                    PlayerBar(engine) { tab = WrenTab.NOW_PLAYING }
                    BottomNavigation(backgroundColor = Surface, contentColor = TextPrimary) {
                        WrenTab.entries.forEach { candidate ->
                            BottomNavigationItem(
                                selected = tab == candidate,
                                onClick = { tab = candidate },
                                icon = { Icon(candidate.icon, contentDescription = candidate.label) },
                                label = { Text(candidate.label, fontFamily = FontMono, fontSize = 10.sp) },
                                selectedContentColor = PsIrisCyan,
                                unselectedContentColor = TextSecondary,
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                AppHeader(onOpenAccounts = { showAccounts = true })
                Box(Modifier.weight(1f)) {
                    // Re-key on auth changes so screens re-run with the new session.
                    when (tab) {
                        WrenTab.SEARCH -> key(platform, authVersion) {
                            SearchScreen(
                                provider = provider,
                                platform = platform,
                                onPlatformChange = { platform = it },
                                engine = engine,
                            )
                        }
                        WrenTab.DISCOVER -> key(platform, authVersion) { DiscoverScreen(provider, engine) }
                        WrenTab.NOW_PLAYING -> NowPlayingScreen(engine)
                    }
                }
            }
        }

        if (showAccounts) {
            AccountsDialog(
                onDismiss = { showAccounts = false },
                onStartSoundcloudLogin = {
                    showAccounts = false
                    showSoundcloudLogin = true
                },
            )
        }

        if (showSoundcloudLogin) {
            Box(Modifier.fillMaxSize().background(Background)) {
                SoundCloudLoginScreen(
                    onDone = {
                        showSoundcloudLogin = false
                        showAccounts = true
                    },
                )
            }
        }
    }
}

@Composable
private fun AppHeader(onOpenAccounts: () -> Unit) {
    val authVersion by AuthEvents.version.collectAsState()
    val googleConnected = remember(authVersion) { GoogleAuth.isAuthenticated }
    val scConnected = remember(authVersion) { SoundCloudAuth.isAuthenticated }

    Row(
        Modifier.fillMaxWidth().background(Background).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("wren;", color = TextSecondary, fontFamily = FontMono, fontSize = 12.sp)
        Spacer(Modifier.width(10.dp))
        if (googleConnected) SessionBadge("GOO")
        if (scConnected) SessionBadge("SC ")
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { globalDark = !globalDark }) {
            Icon(
                if (globalDark) Icons.Default.LightMode else Icons.Default.DarkMode,
                contentDescription = "Toggle theme",
                tint = TextSecondary,
            )
        }
        IconButton(onClick = onOpenAccounts) {
            Icon(Icons.Default.AccountCircle, contentDescription = "Accounts", tint = TextPrimary)
        }
    }
}

@Composable
private fun SessionBadge(label: String) {
    Text(
        label,
        color = PsInk900,
        fontFamily = FontMono,
        fontSize = 9.sp,
        modifier = Modifier
            .padding(end = 4.dp)
            .background(PsSignalOk, RoundedCornerShape(2.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}
