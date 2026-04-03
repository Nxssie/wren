package ui

import auth.AuthManager
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.darkColors
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import player.MpvPlayer
import java.net.URL

val Background = Color(0xFF0F0F0F)
val Surface = Color(0xFF1C1C1C)
val Accent = Color(0xFFFF4444)
val TextPrimary = Color.White
val TextSecondary = Color(0xFFAAAAAA)

private val SidebarCollapsed = 64.dp
private val SidebarExpanded = 220.dp

@Composable
fun AppWindow(onCloseRequest: () -> Unit) {
    val player = remember { MpvPlayer() }
    var authenticated by remember { mutableStateOf(AuthManager.isAuthenticated) }
    var showAuthDialog by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) }
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

    Window(
        onCloseRequest = onCloseRequest,
        title = "Wren",
        icon = appIcon,
        state = WindowState(width = 960.dp, height = 700.dp)
    ) {
        MaterialTheme(
            colors = darkColors(
                background = Background,
                surface = Surface,
                primary = Accent
            )
        ) {
            Column(Modifier.fillMaxSize().background(Background)) {
                Row(Modifier.weight(1f)) {
                    Sidebar(
                        authenticated = authenticated,
                        selectedTab = selectedTab,
                        onTabChange = { selectedTab = it; artistBrowseId = null },
                        onLoginRequest = { showAuthDialog = true },
                        onLogout = { AuthManager.logout(); authenticated = false }
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
                            selectedTab == 0 -> SearchScreen(
                                player = player,
                                onArtistClick = { id, name -> artistBrowseId = id; artistName = name }
                            )
                            selectedTab == 1 -> LibraryScreen(player)
                        }
                    }
                }
                PlayerBar(player)
            }

            if (showAuthDialog) {
                AuthDialog(
                    onDismiss = { showAuthDialog = false },
                    onSuccess = { authenticated = true; showAuthDialog = false }
                )
            }
        }
    }
}

@Composable
private fun Sidebar(
    authenticated: Boolean,
    selectedTab: Int,
    onTabChange: (Int) -> Unit,
    onLoginRequest: () -> Unit,
    onLogout: () -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    val width by animateDpAsState(
        targetValue = if (expanded) SidebarExpanded else SidebarCollapsed,
        animationSpec = tween(200)
    )

    Column(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .background(Surface),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Toggle button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clickable { expanded = !expanded },
            contentAlignment = if (expanded) Alignment.CenterEnd else Alignment.Center
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.ChevronLeft else Icons.Default.ChevronRight,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = TextSecondary,
                modifier = Modifier.padding(end = if (expanded) 12.dp else 0.dp).size(22.dp)
            )
        }

        Divider(color = Color(0xFF2A2A2A), thickness = 1.dp)
        Spacer(Modifier.height(8.dp))

        // Nav items
        NavItem(
            icon = Icons.Default.Search,
            label = "Search",
            selected = selectedTab == 0,
            expanded = expanded,
            onClick = { onTabChange(0) }
        )
        NavItem(
            icon = Icons.Default.LibraryMusic,
            label = "Library",
            selected = selectedTab == 1,
            expanded = expanded,
            onClick = { onTabChange(1) }
        )

        Spacer(Modifier.weight(1f))
        Divider(color = Color(0xFF2A2A2A), thickness = 1.dp)

        // User section
        if (authenticated) {
            UserSection(expanded = expanded, onLogout = onLogout)
        } else {
            LoginButton(expanded = expanded, onClick = onLoginRequest)
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun NavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit
) {
    val bg = if (selected) Accent.copy(alpha = 0.12f) else Color.Transparent
    val tint = if (selected) Accent else TextSecondary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = if (expanded) 12.dp else 0.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        if (expanded) {
            Spacer(Modifier.width(12.dp))
            Text(
                label,
                color = if (selected) TextPrimary else TextSecondary,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun UserSection(expanded: Boolean, onLogout: () -> Unit) {
    var showLogout by remember { mutableStateOf(false) }
    val avatarUrl = remember { AuthManager.avatarUrl }
    val accountName = remember { AuthManager.accountName }
    var avatarBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(avatarUrl) {
        if (!avatarUrl.isNullOrEmpty()) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = URL(avatarUrl).toURI().toURL().readBytes()
                    avatarBitmap = org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
                }
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        if (expanded && showLogout) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onLogout(); showLogout = false }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Sign out", tint = TextSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("Sign out", color = TextSecondary, fontSize = 13.sp)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { showLogout = !showLogout }
                .padding(horizontal = if (expanded) 12.dp else 0.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center
        ) {
            // Avatar
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(Color(0xFF333333)),
                contentAlignment = Alignment.Center
            ) {
                if (avatarBitmap != null) {
                    Image(
                        bitmap = avatarBitmap!!,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(Icons.Default.AccountCircle, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(32.dp))
                }
            }

            if (expanded) {
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        accountName ?: "Account",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    if (showLogout) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun LoginButton(expanded: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = if (expanded) 12.dp else 0.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center
    ) {
        Icon(Icons.Default.AccountCircle, contentDescription = "Sign in", tint = TextSecondary, modifier = Modifier.size(22.dp))
        if (expanded) {
            Spacer(Modifier.width(12.dp))
            Text("Sign in", color = TextSecondary, fontSize = 13.sp)
        }
    }
}
