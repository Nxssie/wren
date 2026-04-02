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
            Text("Iniciar sesión", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
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
                        Text("Cerrar", color = TextSecondary, fontSize = 14.sp)
                    }
                }
                authUrl == null -> {
                    CircularProgressIndicator(color = Accent, modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.height(16.dp))
                    Text("Preparando enlace de autorización...", color = TextSecondary, fontSize = 13.sp)
                }
                else -> {
                    Text(
                        "Se ha abierto el navegador. Autoriza la aplicación para continuar.",
                        color = TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    TextButton(
                        onClick = { openInBrowser(authUrl!!) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Abrir enlace de nuevo", color = Accent, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Accent, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Esperando autorización...", color = TextSecondary, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(20.dp))
                    TextButton(onClick = { onDismiss() }) {
                        Text("Cancelar", color = TextSecondary, fontSize = 12.sp)
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
            Text("Configuración requerida", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            Text(
                "Para iniciar sesión necesitas crear credenciales OAuth en Google Cloud y guardarlas en:",
                color = TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "~/.config/wren/oauth.json",
                color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "1. Abre Google Cloud Console\n" +
                "2. Crea un proyecto y habilita \"YouTube Data API v3\"\n" +
                "3. Crea credenciales → OAuth 2.0 → Aplicación de escritorio\n" +
                "4. Guarda el archivo con este formato:",
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
                    Text("Abrir Cloud Console", color = Accent, fontSize = 13.sp)
                }
                TextButton(onClick = onDismiss) {
                    Text("Cerrar", color = TextSecondary, fontSize = 13.sp)
                }
            }
        }
    }
}

private fun openInBrowser(url: String) {
    runCatching { Desktop.getDesktop().browse(URI(url)) }
}
