package com.wren.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import auth.AuthEvents
import auth.AuthStore
import auth.GoogleAuth
import auth.SoundCloudAuth
import auth.loadCredentials
import util.connectMessage
import auth.runGoogleLogin
import com.wren.app.util.openInBrowser
import kotlinx.coroutines.launch

/**
 * Sessions, mirroring the desktop ProfileDialog: local profile name plus Google and
 * SoundCloud connect/disconnect. Google runs the loopback flow in Chrome Custom Tabs;
 * SoundCloud hands off to [SoundCloudLoginScreen] (its PKCE callback cannot be caught by
 * a Custom Tab, only by a WebView we control).
 */
@Composable
fun AccountsDialog(
    onDismiss: () -> Unit,
    onStartSoundcloudLogin: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authVersion by AuthEvents.version.collectAsState()

    var profileName by remember { mutableStateOf("") }
    var isEditingName by remember { mutableStateOf(false) }
    var googleError by remember { mutableStateOf<String?>(null) }
    var awaitingBrowser by remember { mutableStateOf(false) }
    var scError by remember { mutableStateOf<String?>(null) }
    var scTokenInput by remember { mutableStateOf("") }
    var scConnecting by remember { mutableStateOf(false) }

    val googleConnected = remember(authVersion) { GoogleAuth.isAuthenticated }
    val scConnected = remember(authVersion) { SoundCloudAuth.isAuthenticated }

    LaunchedEffect(Unit) { profileName = AuthStore.profile().displayName }

    Dialog(onDismissRequest = onDismiss) {
        Surface(color = Surface, shape = RoundedCornerShape(0.dp)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text("profile;", color = PsSteel400, fontFamily = FontMono, fontSize = 10.sp)
                Spacer(Modifier.height(8.dp))
                if (isEditingName) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = profileName,
                            onValueChange = { profileName = it },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            colors = fieldColors(),
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            AuthStore.updateDisplayName(profileName)
                            isEditingName = false
                        }) { Text("ok;", fontFamily = FontMono, fontSize = 12.sp, color = TextPrimary) }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(profileName, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { isEditingName = true }) {
                            Text("edit;", fontFamily = FontMono, fontSize = 11.sp, color = TextSecondary)
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                Text("connected sessions;", color = PsSteel400, fontFamily = FontMono, fontSize = 10.sp)
                Spacer(Modifier.height(12.dp))

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
                                googleError = "No Google OAuth client bundled and no oauth.json on this device"
                                return@launch
                            }
                            awaitingBrowser = true
                            try {
                                val tokens = runGoogleLogin(creds) { url -> openInBrowser(context, url) }
                                GoogleAuth.connect(tokens)
                            } catch (e: Exception) {
                                googleError = e.connectMessage()
                            } finally {
                                awaitingBrowser = false
                            }
                        }
                    },
                    onDisconnect = { GoogleAuth.disconnect() },
                ) {
                    if (awaitingBrowser) {
                        Text(
                            "// waiting_for_browser_authorization;",
                            color = PsSteel400,
                            fontFamily = FontMono,
                            fontSize = 10.sp,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                ProviderRow(
                    name = "SoundCloud",
                    code = "SC ",
                    connected = scConnected,
                    accountLabel = SoundCloudAuth.username,
                    error = scError,
                    onConnect = { if (!scConnecting) { scError = null; onStartSoundcloudLogin() } },
                    onDisconnect = { SoundCloudAuth.disconnect() },
                ) {
                    Column {
                        Button(
                            onClick = { if (!scConnecting) { scError = null; onStartSoundcloudLogin() } },
                            enabled = !scConnecting,
                            colors = ButtonDefaults.buttonColors(backgroundColor = Accent),
                            shape = RoundedCornerShape(0.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("sign_in_to_soundcloud;", color = Background, fontFamily = FontMono, fontSize = 12.sp)
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
                            colors = fieldColors(),
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
                            colors = ButtonDefaults.buttonColors(backgroundColor = Accent),
                            shape = RoundedCornerShape(0.dp),
                        ) {
                            Text("connect;", color = Background, fontFamily = FontMono, fontSize = 12.sp)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("close;", color = PsSteel400, fontFamily = FontMono, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun fieldColors() = TextFieldDefaults.outlinedTextFieldColors(
    textColor = TextPrimary,
    cursorColor = TextPrimary,
    focusedBorderColor = TextPrimary,
    unfocusedBorderColor = PsPearl200,
    backgroundColor = Background,
    placeholderColor = PsSteel400,
)

@Composable
private fun ProviderRow(
    name: String,
    code: String,
    connected: Boolean,
    accountLabel: String?,
    error: String?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    customContent: @Composable (() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(PsInset, RoundedCornerShape(0.dp))
            .padding(12.dp),
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
                TextButton(onClick = onDisconnect) {
                    Text("disconnect;", color = PsSignalDanger, fontFamily = FontMono, fontSize = 11.sp)
                }
            } else {
                TextButton(onClick = onConnect) {
                    Text("connect;", color = TextPrimary, fontFamily = FontMono, fontSize = 11.sp)
                }
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
