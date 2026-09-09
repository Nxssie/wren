package ui

import auth.AuthEvents
import auth.GoogleAuth
import auth.SoundCloudAuth
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import player.FFmpegPlayer
import provider.Platform
import provider.Providers
import java.net.URL

// PrintStream design tokens
val PsPaper        = Color(0xFFF4F4F2)
val PsWhite        = Color(0xFFFFFFFF)
val PsPearl100     = Color(0xFFEDEDEF)
val PsPearl200     = Color(0xFFDDDDE1)
val PsPearl300     = Color(0xFFC3C3C8)
val PsSteel400     = Color(0xFF8A8A90)
val PsSteel500     = Color(0xFF5A5A62)
val PsGraphite600  = Color(0xFF2F2F36)
val PsGraphite700  = Color(0xFF1A1A1F)
val PsInk800       = Color(0xFF0D0D11)
val PsInk900       = Color(0xFF050507)
val PsIrisCyan     = Color(0xFFB6E8F2)
val PsSignalOk     = Color(0xFF7FA58A)
val PsSignalDanger = Color(0xFFD2644D)
val PsMidGraphite  = Color(0xFF3A3A42)

// Global dark-mode toggle — mutableStateOf so composables react to it
var globalDark by mutableStateOf(false)

// Semantic aliases — computed properties so they react to globalDark
val Background    get() = if (globalDark) PsInk900        else PsPaper
val Surface       get() = if (globalDark) PsGraphite600   else PsWhite
val Accent        get() = if (globalDark) PsWhite         else PsInk900
val TextPrimary   get() = if (globalDark) PsWhite         else PsInk900
val TextSecondary get() = if (globalDark) PsPearl200      else PsSteel500
val PsInset       get() = if (globalDark) PsMidGraphite   else PsPearl100
val Hairline      get() = if (globalDark) PsWhite.copy(alpha = 0.12f) else Color(0x1F000000)
val HairlineSoft  get() = if (globalDark) PsWhite.copy(alpha = 0.06f) else Color(0x0D000000)
val FontMono            = FontFamily.Monospace

/** Density AWT actually applied (Xft.dpi / OS settings); 1f when it applied nothing. */
private fun awtDensity(): Float = runCatching {
    java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
        .defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX.toFloat()
}.getOrNull()?.takeIf { it > 0f } ?: 1f

@Composable
fun AppWindow(uiScale: Float, onCloseRequest: () -> Unit) {
    val player = remember { FFmpegPlayer() }
    var showProfileDialog by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) }
    // Browsing is scoped to one platform at a time; the queue/player stay shared.
    var platform by remember { mutableStateOf(Platform.YOUTUBE) }
    val provider = remember(platform) { Providers.of(platform) }
    var artistBrowseId by remember { mutableStateOf<String?>(null) }
    var artistName by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        player.start()
        onDispose { player.stop() }
    }

    val appIcon = remember {
        BitmapPainter(
            Thread.currentThread().contextClassLoader!!
                .getResourceAsStream("wren.png")!!
                .use(::loadImageBitmap)
        )
    }

    // Scale the window itself to native resolution: AWT may ignore the uiScale property
    // on X11, so divide out whatever density AWT actually applied and multiply by the
    // compositor scale. Content density is set explicitly (see below).
    val awtDensity = remember { awtDensity() }
    val sizeScale = if (awtDensity > 0f) uiScale / awtDensity else uiScale

    Window(
        onCloseRequest = onCloseRequest,
        title = "Wren",
        icon = appIcon,
        state = WindowState(width = (960 * sizeScale).dp, height = (700 * sizeScale).dp)
    ) {
        CompositionLocalProvider(LocalDensity provides Density(uiScale)) {
            MaterialTheme(
            colors = if (globalDark) darkColors(
                background = PsInk900, surface = PsGraphite600,
                primary = PsWhite, onPrimary = PsInk900,
                onBackground = PsWhite, onSurface = PsWhite
            ) else lightColors(
                background = PsPaper, surface = PsWhite,
                primary = PsInk900, onPrimary = PsWhite,
                onBackground = PsInk900, onSurface = PsInk900
            )
        ) {
            Column(Modifier.fillMaxSize().background(Background)) {
                Row(Modifier.weight(1f)) {
                    Sidebar(
                        platform = platform,
                        onPlatformChange = { platform = it; artistBrowseId = null },
                        selectedTab = selectedTab,
                        onTabChange = { selectedTab = it; artistBrowseId = null },
                        onOpenProfile = { showProfileDialog = true }
                    )
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        val browseId = artistBrowseId
                        when {
                            browseId != null -> ArtistScreen(
                                browseId = browseId,
                                player = player,
                                onBack = { artistBrowseId = null },
                                onArtistClick = { id, name -> artistBrowseId = id; artistName = name }
                            )
                            // key(platform): each platform keeps its own screen state
                            selectedTab == 0 -> key(platform) {
                                SearchScreen(
                                    provider = provider,
                                    player = player,
                                    onArtistClick = { id, name -> artistBrowseId = id; artistName = name }
                                )
                            }
                            selectedTab == 1 -> key(platform) { DiscoverScreen(provider, player) }
                            selectedTab == 2 -> key(platform) { LibraryScreen(provider, player) }
                            selectedTab == 3 -> NowPlayingScreen(player)
                        }
                    }
                }
                PlayerBar(player)
            }

            if (showProfileDialog) {
                ProfileDialog(onDismiss = { showProfileDialog = false })
            }
            }
        }
    }
}

@Composable
private fun Sidebar(
    platform: Platform,
    onPlatformChange: (Platform) -> Unit,
    selectedTab: Int,
    onTabChange: (Int) -> Unit,
    onOpenProfile: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(220.dp)
            .fillMaxHeight()
            .background(PsInk900)
            .drawBehind {
                // Right-edge 1px hairline
                drawLine(
                    color = Color.Black,
                    start = Offset(size.width, 0f),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1f
                )
            },
        horizontalAlignment = Alignment.Start
    ) {
        // Brand header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    // Bottom hairline at 8% white
                    drawLine(
                        color = Color.White.copy(alpha = 0.08f),
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = 1f
                    )
                }
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            Text(
                "WREN",
                color = PsWhite,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                letterSpacing = 2.sp
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            "_platform;",
            color = PsPearl300.copy(alpha = 0.6f),
            fontFamily = FontMono,
            fontSize = 9.sp,
            letterSpacing = 1.7.sp,
            modifier = Modifier.padding(start = 20.dp, bottom = 6.dp)
        )
        PlatformSwitcher(platform, onPlatformChange)

        Spacer(Modifier.height(16.dp))

        // Section label
        Text(
            "_navigation;",
            color = PsPearl300.copy(alpha = 0.6f),
            fontFamily = FontMono,
            fontSize = 9.sp,
            letterSpacing = 1.7.sp,
            modifier = Modifier.padding(start = 20.dp, bottom = 6.dp)
        )

        NavItem(
            code = "SCH",
            label = "search",
            selected = selectedTab == 0,
            onClick = { onTabChange(0) }
        )
        NavItem(
            code = if (platform == Platform.YOUTUBE) "RAD" else "DSC",
            label = Providers.of(platform).discoverLabel,
            selected = selectedTab == 1,
            onClick = { onTabChange(1) }
        )
        NavItem(
            code = "LIB",
            label = "library",
            selected = selectedTab == 2,
            onClick = { onTabChange(2) }
        )
        NavItem(
            code = "NOW",
            label = "now playing",
            selected = selectedTab == 3,
            onClick = { onTabChange(3) }
        )

        Spacer(Modifier.weight(1f))

        // Theme toggle
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { globalDark = !globalDark }
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "_theme;",
                fontFamily = FontMono, fontSize = 9.sp,
                letterSpacing = 1.4.sp,
                color = PsPearl300.copy(alpha = 0.55f)
            )
            Text(
                if (globalDark) "dark;" else "light;",
                fontFamily = FontMono, fontSize = 9.sp,
                letterSpacing = 1.4.sp,
                color = PsPearl300.copy(alpha = 0.55f)
            )
        }

        // Footer text
        Text(
            "///rev.B;\n_handle_with_care;\n0472-2026",
            color = PsPearl300.copy(alpha = 0.3f),
            fontFamily = FontMono,
            fontSize = 9.sp,
            lineHeight = 14.sp,
            modifier = Modifier.padding(start = 20.dp, bottom = 16.dp)
        )

        Divider(color = Color.White.copy(alpha = 0.08f), thickness = 1.dp)

        UserSection(onOpenProfile = onOpenProfile)

        Spacer(Modifier.height(8.dp))
    }
}

/** Segmented control scoping Search / Discover / Library to one platform. */
@Composable
private fun PlatformSwitcher(current: Platform, onChange: (Platform) -> Unit) {
    val authVersion by AuthEvents.version.collectAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .background(PsGraphite700)
            .padding(2.dp)
    ) {
        Platform.entries.forEach { p ->
            val selected = p == current
            val connected = remember(authVersion, p) { Providers.of(p).isAuthenticated }
            Row(
                Modifier
                    .weight(1f)
                    .background(if (selected) PsWhite else Color.Transparent)
                    .clickable { onChange(p) }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    p.code,
                    color = if (selected) PsInk900 else PsPearl300.copy(alpha = 0.7f),
                    fontFamily = FontMono,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    letterSpacing = 1.4.sp
                )
                Spacer(Modifier.width(6.dp))
                // Session dot: green when that platform has a connected account
                Box(
                    Modifier.size(5.dp).background(
                        if (connected) PsSignalOk else (if (selected) PsPearl300 else PsPearl300.copy(alpha = 0.25f))
                    )
                )
            }
        }
    }
}

@Composable
private fun NavItem(
    code: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                if (selected) {
                    // Active left 2dp accent rect
                    drawRect(
                        color = PsIrisCyan,
                        topLeft = Offset(0f, 0f),
                        size = Size(4.dp.toPx(), size.height)
                    )
                    // Subtle background
                    drawRect(
                        color = Color.White.copy(alpha = 0.06f),
                        topLeft = Offset(0f, 0f),
                        size = size
                    )
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            code,
            color = if (selected) PsWhite else PsPearl300.copy(alpha = 0.5f),
            fontFamily = FontMono,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            letterSpacing = 0.5.sp,
            modifier = Modifier.width(32.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            color = if (selected) PsWhite else PsPearl300.copy(alpha = 0.5f),
            fontFamily = FontMono,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun UserSection(onOpenProfile: () -> Unit) {
    val authVersion by AuthEvents.version.collectAsState()
    val avatarUrl = remember(authVersion) { GoogleAuth.avatarUrl ?: SoundCloudAuth.avatarUrl }
    val googleConnected = remember(authVersion) { GoogleAuth.isAuthenticated }
    val scConnected = remember(authVersion) { SoundCloudAuth.isAuthenticated }
    val profileName = remember(authVersion) { auth.AuthStore.profile().displayName }
    var avatarBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(avatarUrl) {
        avatarBitmap = null
        if (!avatarUrl.isNullOrEmpty()) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = URL(avatarUrl).toURI().toURL().readBytes()
                    avatarBitmap = org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
                }
            }
        }
    }

    // Always visible, whatever is connected: the profile dialog is where sessions are
    // connected/disconnected independently, so it must stay reachable at all times.
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenProfile)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(28.dp).background(PsGraphite600), contentAlignment = Alignment.Center) {
            if (avatarBitmap != null) {
                Image(bitmap = avatarBitmap!!, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(Icons.Default.AccountCircle, contentDescription = null, tint = PsPearl300, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(profileName, color = PsWhite, fontFamily = FontMono, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ProviderBadge("GOO", googleConnected)
                ProviderBadge("SC", scConnected)
                if (!googleConnected && !scConnected) {
                    Text("_sign_in;", color = PsPearl300.copy(alpha = 0.5f), fontFamily = FontMono, fontSize = 9.sp)
                }
            }
        }
        Icon(
            Icons.Default.Settings,
            contentDescription = "Profile and sessions",
            tint = PsPearl300.copy(alpha = 0.5f), modifier = Modifier.size(14.dp)
        )
    }
}

@Composable
private fun ProviderBadge(code: String, connected: Boolean) {
    Text(
        code,
        color = if (connected) PsSignalOk else PsPearl300.copy(alpha = 0.3f),
        fontFamily = FontMono,
        fontSize = 9.sp
    )
}
