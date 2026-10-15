package com.wren.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Divider
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wren.app.update.ApkInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import update.UpdatePreferences
import update.UpdateState

/**
 * The one place an update is offered, pinned above the tab content.
 *
 * Renders nothing unless the checker holds an [UpdateState.Available]. Idle, checking and
 * up-to-date all mean there is no action to take, and a failed *launch* check stays silent: a
 * cold start with no network is not worth nagging about, and the checker already logs it. A
 * failed *download* is the opposite — the user asked for it and is waiting — so it is shown with
 * a retry.
 *
 * The checker's `StateFlow` supplies availability only. Progress, the current download failure
 * and the dismissal are local: none of them is the checker's business, and all of them are gone
 * the moment the banner leaves composition.
 */
@Composable
fun UpdateBanner(
    state: UpdateState,
    installer: ApkInstaller,
    modifier: Modifier = Modifier,
) {
    val release = (state as? UpdateState.Available)?.release ?: return

    // Keyed on the raw tag, the exact string the checker compares against, so "Later" hides this
    // release without a refetch while a genuinely newer one still shows.
    var dismissedTag by remember { mutableStateOf<String?>(null) }
    if (release.tag == dismissedTag) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    // null means "no fraction reported yet", which is the only indeterminate signal this API
    // gives: download() reports 0f immediately and 1f only once the archive is verified, so 1f
    // means ready-to-install rather than every byte transferred.
    var fraction by remember { mutableStateOf<Float?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    fun install() {
        // A second tap while a download is in flight must not start another.
        if (downloading) return
        // Read at tap time, never cached, so returning from the system settings screen just works.
        if (!installer.canInstall()) {
            context.startActivity(installer.installPermissionIntent())
            return
        }
        downloading = true
        fraction = null
        failure = null
        scope.launch {
            try {
                val apk = installer.download(release) { fraction = it }
                installer.install(apk)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                failure = throwable.message ?: "The download failed"
            } finally {
                downloading = false
                fraction = null
            }
        }
    }

    Column(modifier.fillMaxWidth().background(Surface)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            SectionLabel("_update;")
            Spacer(Modifier.height(4.dp))
            Text(
                if (downloading) "downloading wren ${release.tag}"
                else "wren ${release.tag} is available",
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )

            if (downloading) {
                Spacer(Modifier.height(10.dp))
                val progress = fraction
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = Accent,
                        backgroundColor = Track,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = Accent,
                        backgroundColor = Track,
                    )
                }
            } else {
                val error = failure
                if (error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(error, color = PsSignalDanger, fontFamily = FontMono, fontSize = 10.sp)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BannerAction(if (error != null) "Retry" else "Install", primary = true) { install() }
                    BannerAction("Later") {
                        UpdatePreferences.dismiss(release.tag)
                        dismissedTag = release.tag
                    }
                }
            }
        }
        Divider(color = Hairline, thickness = 1.dp)
    }
}

/** A square text action in the PrintStream control style; the primary one is an accent block. */
@Composable
private fun BannerAction(label: String, primary: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        color = if (primary) OnAccent else TextPrimary,
        fontFamily = FontMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        letterSpacing = 1.4.sp,
        modifier = Modifier
            .background(if (primary) Accent else Track)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
