package de.foldki.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import de.foldki.app.R

/** Design tokens of the Fold KI design (from 4.0: dark, Apple-like – black, grey groups, blue accent, green switches). */
object FK {
    val Bg = Color(0xFF000000)
    val Surface = Color(0xFF1C1C1E)
    val Surface2 = Color(0xFF2C2C2E)
    val Surface3 = Color(0xFF3A3A3C)
    val Line = Color(0x8C545458)
    val Text = Color(0xFFFFFFFF)
    val Muted = Color(0x99EBEBF5)
    val Dim = Color(0x4DEBEBF5)
    val Accent = Color(0xFF0A84FF)
    val AccentSoft = Color(0x2E0A84FF)
    val OnAccent = Color(0xFFFFFFFF)
    val Ok = Color(0xFF30D158)
    val OkSoft = Color(0x2930D158)
    val Warn = Color(0xFFFF9F0A)
    val Danger = Color(0xFFFF453A)
    val Purple = Color(0xFFBF5AF2)
    val Side = Color(0xFF1C1C1E)
    val Scrim = Color(0x99000000)

    // Icon tiles (background, symbol): solid colour with a white symbol, like iOS settings.
    val T1 = Color(0xFFFF9F0A) to Color.White
    val T2 = Color(0xFF0A84FF) to Color.White
    val T3 = Color(0xFF30D158) to Color.White
    val T4 = Color(0xFFBF5AF2) to Color.White
    val T5 = Color(0xFFFF375F) to Color.White
    val T6 = Color(0xFF636366) to Color.White
    val T7 = Color(0xFF5E5CE6) to Color.White
    val T8 = Color(0xFF30B0C7) to Color.White
    val T9 = Color(0xFF24292F) to Color.White
    val T10 = Color(0xFF8E8E93) to Color.White
    val T11 = Color(0xFF3A3A3C) to Color.White
}

val Accent = FK.Accent

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private val Scheme = darkColorScheme(
    primary = FK.Accent, onPrimary = FK.OnAccent,
    secondary = FK.Accent, onSecondary = FK.OnAccent,
    background = FK.Bg, onBackground = FK.Text,
    surface = FK.Bg, onSurface = FK.Text,
    surfaceVariant = FK.Surface, onSurfaceVariant = FK.Muted,
    surfaceContainer = FK.Side, surfaceContainerHigh = FK.Surface, surfaceContainerHighest = FK.Surface2,
    surfaceContainerLow = FK.Surface, surfaceContainerLowest = FK.Bg,
    outline = FK.Line, outlineVariant = FK.Line,
    error = Color(0xFFF2B8B5),
)

private fun typography(): Typography {
    val base = Typography()
    fun TextStyle.inter() = copy(fontFamily = Inter)
    return Typography(
        displayLarge = base.displayLarge.inter(), displayMedium = base.displayMedium.inter(), displaySmall = base.displaySmall.inter(),
        headlineLarge = base.headlineLarge.inter(), headlineMedium = base.headlineMedium.inter(), headlineSmall = base.headlineSmall.inter(),
        titleLarge = base.titleLarge.inter(), titleMedium = base.titleMedium.inter(), titleSmall = base.titleSmall.inter(),
        bodyLarge = base.bodyLarge.inter().copy(fontSize = 15.sp), bodyMedium = base.bodyMedium.inter(), bodySmall = base.bodySmall.inter(),
        labelLarge = base.labelLarge.inter(), labelMedium = base.labelMedium.inter(), labelSmall = base.labelSmall.inter(),
    )
}

/** Always the dark Fold KI design (the approved look), independent of the system setting. */
@Composable
fun FoldTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = typography(), content = content)
}
