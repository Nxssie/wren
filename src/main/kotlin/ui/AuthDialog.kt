package ui

import auth.AuthManager
import auth.buildAuthUrl
import auth.exchangeCode
import auth.loadCredentials
import auth.waitForAuthCode
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI

@Composable
fun AuthDialog(onDismiss: () -> Unit, onSuccess: () -> Unit) {
    val hasCredentials = remember { loadCredentials() != null }

    if (!hasCredentials) {
        CredentialsSetupDialog(onDismiss)
        return
    }

    var authUrl by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching {
            val creds = loadCredentials()!!
            val url = buildAuthUrl(creds.clientId)
            authUrl = url
            openInBrowser(url)
            val code = waitForAuthCode()
            val tokens = exchangeCode(code, creds)
            AuthManager.saveTokens(tokens)
            onSuccess()
        }.onFailure {
            error = it.message ?: it.toString()
        }
    }

    Dialog(onDismissRequest = { onDismiss() }) {
        Column(
            modifier = Modifier
                .width(400.dp)
                .background(Surface, RoundedCornerShape(16.dp))
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Sign in", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(24.dp))
            when {
                error != null -> {
                    Text(error!!, color = Color(0xFFFF6B6B), fontSize = 13.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = { onDismiss() },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Surface),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Close", color = TextSecondary, fontSize = 14.sp)
                    }
                }
                authUrl == null -> {
                    CircularProgressIndicator(color = Accent, modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.height(16.dp))
                    Text("Preparing authorization link...", color = TextSecondary, fontSize = 13.sp)
                }
                else -> {
                    Text(
                        "The browser has been opened. Authorize the app to continue.",
                        color = TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    TextButton(
                        onClick = { openInBrowser(authUrl!!) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open link again", color = Accent, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Accent, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Waiting for authorization...", color = TextSecondary, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(20.dp))
                    TextButton(onClick = { onDismiss() }) {
                        Text("Cancel", color = TextSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun CredentialsSetupDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .background(Surface, RoundedCornerShape(16.dp))
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Setup required", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            Text(
                "To sign in you need to create OAuth credentials in Google Cloud and save them to:",
                color = TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "~/.config/wren/oauth.json",
                color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "1. Open Google Cloud Console\n" +
                "2. Create a project and enable \"YouTube Data API v3\"\n" +
                "3. Create credentials → OAuth 2.0 → Desktop app\n" +
                "4. Save the file with this format:",
                color = TextSecondary, fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "{\n  \"client_id\": \"…\",\n  \"client_secret\": \"…\"\n}",
                color = TextPrimary, fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F0F0F), RoundedCornerShape(6.dp))
                    .padding(12.dp)
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { openInBrowser("https://console.cloud.google.com/apis/credentials") }) {
                    Text("Open Cloud Console", color = Accent, fontSize = 13.sp)
                }
                TextButton(onClick = onDismiss) {
                    Text("Close", color = TextSecondary, fontSize = 13.sp)
                }
            }
        }
    }
}

private fun openInBrowser(url: String) {
    runCatching { Desktop.getDesktop().browse(URI(url)) }
}
