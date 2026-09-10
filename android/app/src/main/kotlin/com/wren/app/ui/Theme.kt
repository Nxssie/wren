package com.wren.app.ui

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Shapes
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

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
/** Chrome (header, nav) sits one step below the page background, like the desktop sidebar. */
val Chrome        get() = if (globalDark) PsInk900        else PsWhite
/** Recessed control track (segmented switchers, insets). */
val Track         get() = if (globalDark) PsGraphite700   else PsPearl100
val Accent        get() = if (globalDark) PsWhite         else PsInk900
val OnAccent      get() = if (globalDark) PsInk900        else PsWhite
val TextPrimary   get() = if (globalDark) PsWhite         else PsInk900
val TextSecondary get() = if (globalDark) PsPearl200      else PsSteel500
val TextMuted     get() = if (globalDark) PsPearl300.copy(alpha = 0.6f) else PsSteel400
val PsInset       get() = if (globalDark) PsMidGraphite   else PsPearl100
val Hairline      get() = if (globalDark) PsWhite.copy(alpha = 0.12f) else Color(0x1F000000)
val HairlineSoft  get() = if (globalDark) PsWhite.copy(alpha = 0.06f) else Color(0x0D000000)
val FontMono      = FontFamily.Monospace

/** PrintStream is square: no rounded corners anywhere, Material components included. */
private val SquareShapes = Shapes(
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp),
)

@Composable
fun WrenTheme(content: @Composable () -> Unit) {
    SystemBars()
    MaterialTheme(
        colors = if (globalDark) darkColors(
            background = PsInk900, surface = PsGraphite600,
            primary = PsWhite, onPrimary = PsInk900,
            secondary = PsIrisCyan, onSecondary = PsInk900,
            onBackground = PsWhite, onSurface = PsWhite,
            error = PsSignalDanger,
        ) else lightColors(
            background = PsPaper, surface = PsWhite,
            primary = PsInk900, onPrimary = PsWhite,
            secondary = PsIrisCyan, onSecondary = PsInk900,
            onBackground = PsInk900, onSurface = PsInk900,
            error = PsSignalDanger,
        ),
        shapes = SquareShapes,
    ) {
        content()
    }
}

/** Status/navigation bars follow the in-app theme instead of the XML default. */
@Composable
private fun SystemBars() {
    val view = LocalView.current
    if (view.isInEditMode) return
    val dark = globalDark
    val color = Chrome.toArgb()
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        @Suppress("DEPRECATION")
        window.statusBarColor = color
        @Suppress("DEPRECATION")
        window.navigationBarColor = color
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}
