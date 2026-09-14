package com.wren.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import api.SoundCloudLikes
import api.YouTubeLikes
import auth.AuthEvents
import util.ThemePreference
import player.PlayerEngine
import provider.Platform
import provider.Providers

/**
 * The bottom bar, in the order it is drawn. Now Playing is deliberately not one of them: it opens
 * from the player bar or from the item that is already playing, and lives above the tabs.
 */
private enum class WrenTab(val code: String, val icon: ImageVector) {
    HOME("HOM", Icons.Default.Home),
    SEARCH("SCH", Icons.Default.Search),
    EXPLORE("EXP", Icons.Default.Explore),
    LIBRARY("LIB", Icons.Default.LibraryMusic)
}

/**
 * @param openNowPlaying requested by a tap on the media widget; consumed here and cleared, so
 *   a later tap raises it again (see MainActivity).
 */
@Composable
fun WrenApp(engine: PlayerEngine, openNowPlaying: MutableState<Boolean>) {
    // Saved, not just remembered: a system-initiated recreate (dark mode, locale, font scale)
    // or a restore after process death used to drop the user back on the first tab.
    var tab by rememberSaveable { mutableStateOf(WrenTab.HOME) }
    // Tabs visited so far, so the system back gesture retraces steps instead of quitting.
    val tabHistory = rememberSaveable(saver = TabHistorySaver) { mutableStateListOf<WrenTab>() }
    // The player is a screen above the tabs rather than a tab of its own; [playerHasReturn]
    // separates a tap from inside the app, where back closes it, from a widget tap on a cold
    // start, where there is nothing behind the player and back should leave the app.
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    var playerHasReturn by rememberSaveable { mutableStateOf(false) }
    fun navigate(target: WrenTab) {
        showPlayer = false
        if (target == tab) return
        tabHistory.remove(target)
        tabHistory.add(tab)
        tab = target
    }
    fun openPlayer(fromInside: Boolean) {
        playerHasReturn = fromInside
        showPlayer = true
    }
    var platform by rememberSaveable { mutableStateOf(Platform.YOUTUBE) }
    var showAccounts by remember { mutableStateOf(false) }
    var showSoundcloudLogin by remember { mutableStateOf(false) }
    // Set when an artist is tapped in the Library; SearchScreen picks it up and searches.
    var artistSearchName by remember { mutableStateOf<String?>(null) }
    val authVersion by AuthEvents.version.collectAsState()
    val provider = remember(platform) { Providers.of(platform) }

    // Likes follow the SoundCloud session: load on start, reload/clear on sign in/out.
    LaunchedEffect(authVersion) {
        SoundCloudLikes.refresh()
        YouTubeLikes.reset()
    }

    // A tap on the widget means "show me what is playing". Only a request made from inside the
    // app gets somewhere to go back to, so the cold start that raised it leaves on back. The
    // overlays sit above the tabs, so they have to close first or the request would land behind
    // whichever one was open.
    LaunchedEffect(openNowPlaying.value) {
        if (!openNowPlaying.value) return@LaunchedEffect
        showAccounts = false
        showSoundcloudLogin = false
        openPlayer(fromInside = tabHistory.isNotEmpty())
        openNowPlaying.value = false
    }

    // Outermost back handler: screens register their own (collapse sheet, leave playlist,
    // close login) and win while enabled; this one only runs once those are exhausted. The
    // player's own handler is declared with the player, so it outranks the tab underneath.
    BackHandler(enabled = tabHistory.isNotEmpty()) { tab = tabHistory.removeAt(tabHistory.lastIndex) }

    WrenTheme {
        Scaffold(
            backgroundColor = Background,
            topBar = {
                AppHeader(
                    platform = platform,
                    onPlatformChange = { platform = it; artistSearchName = null },
                    onOpenAccounts = { showAccounts = true },
                    // The player is not a browse surface: the switcher would change nothing on it.
                    showPlatform = !showPlayer,
                )
            },
            bottomBar = {
                Column {
                    // Hidden while the player itself is open: the collapsed sheet at the bottom of
                    // that screen already carries the same track and expands on a tap, so a second
                    // copy would only repeat it in less detail.
                    if (!showPlayer) {
                        PlayerBar(engine) { openPlayer(fromInside = true) }
                    }
                    BottomNav(selected = tab, onSelect = ::navigate)
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                // Re-key on auth changes so screens re-run with the new session.
                when (tab) {
                    WrenTab.HOME -> key(platform, authVersion) { HomeScreen(provider, engine) }
                    WrenTab.SEARCH -> key(platform, authVersion) {
                        SearchScreen(
                            provider = provider,
                            engine = engine,
                            seedQuery = artistSearchName,
                            onSeedConsumed = { artistSearchName = null },
                        )
                    }
                    WrenTab.EXPLORE -> key(platform, authVersion) { ExploreScreen(provider, engine) }
                    WrenTab.LIBRARY -> key(platform, authVersion) {
                        LibraryScreen(
                            provider = provider,
                            engine = engine,
                            onArtistSearch = { artistSearchName = it; navigate(WrenTab.SEARCH) },
                        )
                    }
                }

                // The player draws over the tab instead of replacing it, so closing it returns to
                // whatever the tab had open — a playlist, a search, a scroll position. It owns the
                // tab's part of the layout either way, painting its own opaque background.
                if (showPlayer) {
                    // Declared here, after the tab, so it answers back before any handler the tab
                    // registered: what is on screen is the player, so that is what back closes.
                    BackHandler(enabled = playerHasReturn) { showPlayer = false }
                    // Handles taps itself so none reach the tab behind it. The player's own layers
                    // leave a band uncovered mid-drag, between the compact header and the sheet,
                    // and without this a tap there would land on whatever the tab has at that spot.
                    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }) {
                        NowPlayingScreen(engine)
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

        // No back handler here: SoundCloudLoginScreen's own (composed last, so it wins) walks
        // the WebView back first and only then cancels — to the same place this would go.
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

/**
 * Brand row plus the platform switcher. The switcher lives here, not inside Search, so
 * switching YouTube <-> SoundCloud is one gesture away from every browse tab and the
 * current scope is always visible.
 */
@Composable
private fun AppHeader(
    platform: Platform,
    onPlatformChange: (Platform) -> Unit,
    onOpenAccounts: () -> Unit,
    showPlatform: Boolean,
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
        // Every browse tab works on the active platform, so the switcher is always one gesture
        // away there; the player hides it, since it has no platform to switch.
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

/**
 * The back stack is a handful of enum entries; the saver is what carries it across a
 * recreate, where `remember` would have started the user over on Search.
 */
private val TabHistorySaver = listSaver<SnapshotStateList<WrenTab>, WrenTab>(
    save = { it.toList() },
    restore = { it.toMutableStateList() },
)

/** Same `_theme; dark;` control as the desktop sidebar. */
@Composable
private fun ThemeToggle() {
    Row(
        Modifier
            .clickable {
                globalDark = !globalDark
                ThemePreference.save(globalDark)
            }
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
private fun BottomNav(selected: WrenTab, onSelect: (WrenTab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Chrome)) {
        Divider(color = HairlineSoft, thickness = 1.dp)
        Row(Modifier.fillMaxWidth()) {
            WrenTab.entries.forEach { candidate ->
                val active = candidate == selected
                val label = when (candidate) {
                    WrenTab.HOME -> "home"
                    WrenTab.SEARCH -> "search"
                    WrenTab.EXPLORE -> "explore"
                    WrenTab.LIBRARY -> "library"
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
