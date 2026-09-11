package ui

import auth.AuthEvents
import auth.GoogleAuth
import auth.SoundCloudAuth
import auth.loadCredentials
import auth.runGoogleLogin
import util.connectMessage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import util.openInBrowser

@Composable
fun ProfileDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var profileName by remember { mutableStateOf("") }
    var isEditingName by remember { mutableStateOf(false) }
    val authVersion by AuthEvents.version.collectAsState()
    val googleConnected = remember(authVersion) { GoogleAuth.isAuthenticated }
    val scConnected = remember(authVersion) { SoundCloudAuth.isAuthenticated }
    var googleError by remember { mutableStateOf<String?>(null) }
    var googleAuthUrl by remember { mutableStateOf<String?>(null) }  // set while waiting for the browser
    var googleBrowserOpened by remember { mutableStateOf(true) }
    val clipboard = LocalClipboardManager.current
    var scError by remember { mutableStateOf<String?>(null) }
    var scTokenInput by remember { mutableStateOf("") }
    var scConnecting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        profileName = auth.AuthStore.profile().displayName
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(440.dp)
                .background(Surface, RoundedCornerShape(0.dp))
                .padding(32.dp)
        ) {
            // Header
            Text("profile;", color = PsSteel400, fontFamily = FontMono, fontSize = 10.sp, letterSpacing = 1.7.sp)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isEditingName) {
                    OutlinedTextField(
                        value = profileName,
                        onValueChange = { profileName = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            textColor = TextPrimary, cursorColor = TextPrimary,
                            focusedBorderColor = TextPrimary, unfocusedBorderColor = PsPearl200,
                            backgroundColor = Surface
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        auth.AuthStore.updateDisplayName(profileName)
                        isEditingName = false
                    }) { Text("ok;", fontFamily = FontMono, fontSize = 12.sp) }
                } else {
                    Text(profileName, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { isEditingName = true }) { Text("edit;", fontFamily = FontMono, fontSize = 11.sp) }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text("connected sessions;", color = PsSteel400, fontFamily = FontMono, fontSize = 10.sp, letterSpacing = 1.7.sp)
            Spacer(Modifier.height(12.dp))

            // Google row
            ProviderRow(
                name = "Google",
                code = "GOO",
                connected = googleConnected,
                accountLabel = GoogleAuth.accountName,
                error = googleError,
                onConnect = {
                    scope.launch {
                        googleError = null
                        val creds = loadCredentials()
                        if (creds == null) {
                            googleError = "No Google OAuth client: this build has none bundled and ~/.config/wren/oauth.json is missing"
                            return@launch
                        }
                        try {
                            val tokens = runGoogleLogin(creds) { url ->
                                googleAuthUrl = url
                                googleBrowserOpened = openInBrowser(url)
                            }
                            GoogleAuth.connect(tokens)
                        } catch (e: Exception) {
                            googleError = e.connectMessage()
                        } finally {
                            googleAuthUrl = null
                        }
                    }
                },
                onDisconnect = { GoogleAuth.disconnect() },
                customContent = googleAuthUrl?.let { url ->
                    @Composable {
                        Column {
                            Text(
                                if (googleBrowserOpened) "// waiting_for_browser_authorization;"
                                else "// could_not_open_browser — copy the link and open it manually;",
                                color = if (googleBrowserOpened) PsSteel400 else PsSignalDanger,
                                fontFamily = FontMono, fontSize = 10.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            Row {
                                TextButton(onClick = { clipboard.setText(AnnotatedString(url)) }) {
                                    Text("copy_link;", color = TextPrimary, fontFamily = FontMono, fontSize = 11.sp)
                                }
                                TextButton(onClick = { googleBrowserOpened = openInBrowser(url) }) {
                                    Text("open_again;", color = TextPrimary, fontFamily = FontMono, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            )

            Spacer(Modifier.height(16.dp))

            // SoundCloud row
            val startWebLogin: () -> Unit = {
                if (!scConnecting) {
                    scConnecting = true
                    scError = null
                    scope.launch {
                        try {
                            // The ordinary sign-in page, not the authorization endpoint: the app
                            // reads the session the page keeps for itself and validates it as usual.
                            val session = SoundCloudLoginWindow.open().await()
                            SoundCloudAuth.connect(session.accessToken, session.refreshToken, session.clientId)
                            scTokenInput = ""
                        } catch (e: Exception) {
                            scError = e.connectMessage()
                        } finally {
                            scConnecting = false
                        }
                    }
                }
            }
            ProviderRow(
                name = "SoundCloud",
                code = "SC ",
                connected = scConnected,
                accountLabel = SoundCloudAuth.username,
                error = scError,
                onConnect = startWebLogin,
                onDisconnect = { SoundCloudAuth.disconnect() },
                customContent = if (!scConnected) {
                    @Composable {
                        Column {
                            Button(
                                onClick = startWebLogin,
                                enabled = !scConnecting,
                                colors = ButtonDefaults.buttonColors(backgroundColor = PsInk900),
                                shape = RoundedCornerShape(0.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (scConnecting && scTokenInput.isBlank()) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = PsWhite, strokeWidth = 1.5.dp)
                                } else {
                                    Text("sign_in_to_soundcloud;", color = PsWhite, fontFamily = FontMono, fontSize = 12.sp)
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Text("// or_paste_token_manually;", color = PsSteel400, fontFamily = FontMono, fontSize = 10.sp)
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = scTokenInput,
                                onValueChange = { scTokenInput = it },
                                placeholder = { Text("_paste_oauth_token;", color = PsSteel400, fontFamily = FontMono, fontSize = 12.sp) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = TextFieldDefaults.outlinedTextFieldColors(
                                    textColor = TextPrimary, cursorColor = TextPrimary,
                                    focusedBorderColor = TextPrimary, unfocusedBorderColor = PsPearl200,
                                    backgroundColor = Surface, placeholderColor = PsSteel400
                                )
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    if (scTokenInput.isBlank() || scConnecting) return@Button
                                    scConnecting = true
                                    scError = null
                                    scope.launch {
                                        runCatching { SoundCloudAuth.connect(scTokenInput.trim()) }
                                            .onSuccess { scTokenInput = "" }
                                            .onFailure { scError = it.connectMessage() }
                                        scConnecting = false
                                    }
                                },
                                enabled = scTokenInput.isNotBlank() && !scConnecting,
                                colors = ButtonDefaults.buttonColors(backgroundColor = PsInk900),
                                shape = RoundedCornerShape(0.dp)
                            ) {
                                if (scConnecting && scTokenInput.isNotBlank()) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = PsWhite, strokeWidth = 1.5.dp)
                                } else {
                                    Text("connect;", color = PsWhite, fontFamily = FontMono, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                } else null
            )

            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("close;", color = PsSteel400, fontFamily = FontMono, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ProviderRow(
    name: String,
    code: String,
    connected: Boolean,
    accountLabel: String?,
    error: String?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    customContent: @Composable (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PsInset, RoundedCornerShape(0.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(code, color = PsSteel400, fontFamily = FontMono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                if (connected && accountLabel != null) {
                    Text(accountLabel, color = PsSteel400, fontSize = 11.sp, fontFamily = FontMono)
                }
            }
            if (connected) {
                Icon(Icons.Default.Check, contentDescription = null, tint = PsSignalOk, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDisconnect) { Text("disconnect;", color = PsSignalDanger, fontFamily = FontMono, fontSize = 11.sp) }
            } else {
                TextButton(onClick = onConnect) { Text("connect;", color = TextPrimary, fontFamily = FontMono, fontSize = 11.sp) }
            }
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = PsSignalDanger, fontSize = 11.sp)
        }
        if (!connected && customContent != null) {
            Spacer(Modifier.height(10.dp))
            customContent()
        }
    }
}
