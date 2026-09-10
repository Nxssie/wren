package com.wren.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import api.SoundCloudLikes
import auth.AuthEvents
import player.PlayerEngine
import provider.Platform
import provider.Providers

private enum class WrenTab(val code: String, val icon: ImageVector) {
    SEARCH("SCH", Icons.Default.Search),
    DISCOVER("DSC", Icons.Default.Explore),
    LIBRARY("LIB", Icons.Default.LibraryMusic),
    NOW_PLAYING("NOW", Icons.Default.PlayCircleFilled);

    /** Browse tabs are scoped to the active platform; Now Playing is the shared queue. */
    val browsesPlatform: Boolean get() = this != NOW_PLAYING
}

@Composable
fun WrenApp(engine: PlayerEngine) {
    var tab by remember { mutableStateOf(WrenTab.SEARCH) }
    // Tabs visited so far, so the system back gesture retraces steps instead of quitting.
    val tabHistory = remember { mutableStateListOf<WrenTab>() }
    fun navigate(target: WrenTab) {
        if (target == tab) return
        tabHistory.remove(target)
        tabHistory.add(tab)
        tab = target
    }
    var platform by remember { mutableStateOf(Platform.YOUTUBE) }
    var showAccounts by remember { mutableStateOf(false) }
    var showSoundcloudLogin by remember { mutableStateOf(false) }
    // Set when an artist is tapped in the Library; SearchScreen picks it up and searches.
    var artistSearchName by remember { mutableStateOf<String?>(null) }
    val authVersion by AuthEvents.version.collectAsState()
    val provider = remember(platform) { Providers.of(platform) }

    // Likes follow the SoundCloud session: load on start, reload/clear on sign in/out.
    LaunchedEffect(authVersion) { SoundCloudLikes.refresh() }

    // Outermost back handler: screens register their own (collapse sheet, leave playlist,
    // close login) and win while enabled; this one only runs once those are exhausted.
    BackHandler(enabled = tabHistory.isNotEmpty()) { tab = tabHistory.removeAt(tabHistory.lastIndex) }

    WrenTheme {
        Scaffold(
            backgroundColor = Background,
            topBar = {
                AppHeader(
                    platform = platform,
                    showPlatform = tab.browsesPlatform,
                    onPlatformChange = { platform = it; artistSearchName = null },
                    onOpenAccounts = { showAccounts = true },
                )
            },
            bottomBar = {
                Column {
                    PlayerBar(engine) { navigate(WrenTab.NOW_PLAYING) }
                    BottomNav(
                        selected = tab,
                        discoverLabel = provider.discoverLabel,
                        onSelect = ::navigate,
                    )
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                // Re-key on auth changes so screens re-run with the new session.
                when (tab) {
                    WrenTab.SEARCH -> key(platform, authVersion) {
                        SearchScreen(
                            provider = provider,
                            engine = engine,
                            seedQuery = artistSearchName,
                            onSeedConsumed = { artistSearchName = null },
                        )
                    }
                    WrenTab.DISCOVER -> key(platform, authVersion) { DiscoverScreen(provider, engine) }
                    WrenTab.LIBRARY -> key(platform, authVersion) {
                        LibraryScreen(
                            provider = provider,
                            engine = engine,
                            onArtistSearch = { artistSearchName = it; navigate(WrenTab.SEARCH) },
                        )
                    }
                    WrenTab.NOW_PLAYING -> NowPlayingScreen(engine)
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
            BackHandler { showSoundcloudLogin = false; showAccounts = true }
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

/**
 * Brand row plus the platform switcher. The switcher lives here, not inside Search, so
 * switching YouTube <-> SoundCloud is one gesture away from every browse tab and the
 * current scope is always visible.
 */
@Composable
private fun AppHeader(
    platform: Platform,
    showPlatform: Boolean,
    onPlatformChange: (Platform) -> Unit,
    onOpenAccounts: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Chrome)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "WREN",
                color = TextPrimary,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.weight(1f))
            ThemeToggle()
            IconButton(onClick = onOpenAccounts) {
                Icon(Icons.Default.AccountCircle, contentDescription = "Accounts", tint = TextPrimary)
            }
        }
        if (showPlatform) {
            SectionLabel("_platform;", Modifier.padding(start = 16.dp, bottom = 6.dp))
            PlatformSwitcher(
                current = platform,
                onChange = onPlatformChange,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(12.dp))
        } else {
            Spacer(Modifier.height(6.dp))
        }
        Divider(color = HairlineSoft, thickness = 1.dp)
    }
}

/** Same `_theme; dark;` control as the desktop sidebar. */
@Composable
private fun ThemeToggle() {
    Row(
        Modifier
            .clickable { globalDark = !globalDark }
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SectionLabel("_theme;")
        Text(
            if (globalDark) "dark;" else "light;",
            color = TextSecondary,
            fontFamily = FontMono,
            fontSize = 9.sp,
            letterSpacing = 1.4.sp,
        )
    }
}

/**
 * PrintStream take on a bottom bar: hairline on top, mono code over the label, and a
 * cyan accent rect marking the active tab — the same cue the desktop NavItem uses.
 */
@Composable
private fun BottomNav(selected: WrenTab, discoverLabel: String, onSelect: (WrenTab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Chrome)) {
        Divider(color = HairlineSoft, thickness = 1.dp)
        Row(Modifier.fillMaxWidth()) {
            WrenTab.entries.forEach { candidate ->
                val active = candidate == selected
                val label = when (candidate) {
                    WrenTab.SEARCH -> "search"
                    WrenTab.DISCOVER -> discoverLabel
                    WrenTab.LIBRARY -> "library"
                    WrenTab.NOW_PLAYING -> "now playing"
                }
                Column(
                    Modifier
                        .weight(1f)
                        .drawBehind {
                            if (active) {
                                drawRect(color = PsIrisCyan, topLeft = Offset.Zero, size = Size(size.width, 3.dp.toPx()))
                                drawRect(color = HairlineSoft, topLeft = Offset.Zero, size = size)
                            }
                        }
                        .clickable { onSelect(candidate) }
                        .padding(top = 10.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        candidate.icon,
                        contentDescription = label,
                        tint = if (active) TextPrimary else TextMuted,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "${candidate.code}·$label",
                        color = if (active) TextPrimary else TextMuted,
                        fontFamily = FontMono,
                        fontSize = 9.sp,
                        letterSpacing = 0.5.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
