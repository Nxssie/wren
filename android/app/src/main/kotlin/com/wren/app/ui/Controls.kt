package com.wren.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.material.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import auth.AuthEvents
import provider.Platform
import provider.Providers

/** `_platform;`-style caption used above controls, mirroring the desktop sidebar. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = TextMuted,
        fontFamily = FontMono,
        fontSize = 9.sp,
        letterSpacing = 1.7.sp,
        modifier = modifier,
    )
}

/**
 * Square segmented control: a recessed track with one raised segment. Generic so the
 * platform switcher and the library sub-tabs share one look.
 */
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    segment: @Composable (option: T, active: Boolean) -> Unit,
) {
    Row(modifier.background(Track).padding(2.dp)) {
        options.forEach { option ->
            val active = option == selected
            Row(
                Modifier
                    .weight(1f)
                    .background(if (active) Accent else Color.Transparent)
                    .clickable { onSelect(option) }
                    .padding(vertical = 7.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                segment(option, active)
            }
        }
    }
}

/** Mono text for a [Segmented] option; colour flips against the raised segment. */
@Composable
fun SegmentLabel(text: String, active: Boolean) {
    Text(
        text,
        color = if (active) OnAccent else TextMuted,
        fontFamily = FontMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        letterSpacing = 1.4.sp,
    )
}

/**
 * Scopes Search / Discover / Library to one platform. The dot next to each code is
 * green when that platform has a connected session, so the user can see at a glance
 * which side has a library to browse before switching.
 */
@Composable
fun PlatformSwitcher(current: Platform, onChange: (Platform) -> Unit, modifier: Modifier = Modifier) {
    val authVersion by AuthEvents.version.collectAsState()
    Segmented(
        options = Platform.entries,
        selected = current,
        onSelect = onChange,
        modifier = modifier.fillMaxWidth(),
    ) { platform, active ->
        val connected = remember(authVersion, platform) { Providers.of(platform).isAuthenticated }
        SegmentLabel(platform.code, active)
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier.size(5.dp).background(
                when {
                    connected -> PsSignalOk
                    active -> OnAccent.copy(alpha = 0.4f)
                    else -> TextMuted.copy(alpha = 0.4f)
                },
            ),
        )
    }
}
