package com.wren.app.ui

import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

// PrintStream design tokens — shared with the desktop app so both targets look the same.
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

/** Mutable so the theme toggle recomposes everything reading the semantic aliases. */
var globalDark by mutableStateOf(true)

val Background    get() = if (globalDark) PsInk900        else PsPaper
val Surface       get() = if (globalDark) PsGraphite600   else PsWhite
val Accent        get() = if (globalDark) PsWhite         else PsInk900
val TextPrimary   get() = if (globalDark) PsWhite         else PsInk900
val TextSecondary get() = if (globalDark) PsPearl200      else PsSteel500
val PsInset       get() = if (globalDark) PsMidGraphite   else PsPearl100
val Hairline      get() = if (globalDark) PsWhite.copy(alpha = 0.12f) else Color(0x1F000000)
val HairlineSoft  get() = if (globalDark) PsWhite.copy(alpha = 0.06f) else Color(0x0D000000)
val FontMono      = FontFamily.Monospace

@Composable
fun WrenTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = if (globalDark) darkColors(
            background = PsInk900, surface = PsGraphite600,
            primary = PsWhite, onPrimary = PsInk900,
            onBackground = PsWhite, onSurface = PsWhite,
        ) else lightColors(
            background = PsPaper, surface = PsWhite,
            primary = PsInk900, onPrimary = PsWhite,
            onBackground = PsInk900, onSurface = PsInk900,
        ),
    ) {
        content()
    }
}
