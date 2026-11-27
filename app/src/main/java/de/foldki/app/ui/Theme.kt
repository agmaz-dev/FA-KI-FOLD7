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
    val Bg = Color(0xFF0D111A)
    val Surface = Color(0xFF181F2C)
    val Surface2 = Color(0xFF232D3D)
    val Surface3 = Color(0xFF303D50)
    val Line = Color(0xFF303A4A)
    val Text = Color(0xFFFFFFFF)
    val Muted = Color(0xFF929EB1)
    val Dim = Color(0xFF697586)
    val Accent = Color(0xFF528AFF)
    val AccentSoft = Color(0x2E528AFF)
    val OnAccent = Color(0xFFFFFFFF)
    val Ok = Color(0xFF32BF8A)
    val OkSoft = Color(0x2930D158)
    val Warn = Color(0xFFFF9F0A)
    val Danger = Color(0xFFFF453A)
    val Purple = Color(0xFFBF5AF2)
    val Side = Color(0xFF181F2C)
    val Scrim = Color(0x99000000)

    // Icon tiles (ab 4.9): one calm grey for every symbol – blue only marks what is active or can be tapped.
    // Ten colours side by side made the menus look restless (feedback of the user).
    private val TileBg = Color(0xFF303D50)
    private val TileFg = Color(0xFFE5E5EA)
    val T1 = TileBg to TileFg
    val T2 = TileBg to TileFg
    val T3 = TileBg to TileFg
    val T4 = TileBg to TileFg
    val T5 = TileBg to TileFg
    val T6 = TileBg to TileFg
    val T7 = TileBg to TileFg
    val T8 = TileBg to TileFg
    val T9 = TileBg to TileFg
    val T10 = TileBg to TileFg
    val T11 = TileBg to TileFg
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
    // Ab 4.9: no extra letter spacing – Material's 0.5 sp made every text look wide and coarse.
    fun TextStyle.inter() = copy(fontFamily = Inter, letterSpacing = 0.sp)
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
