package works.gauss.grokbotalarm

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** True black, white type, one blue accent. Content color is forced to white. */
object AlarmPalette {
    val Black = Color(0xFF000000)
    val White = Color(0xFFFFFFFF)
    val Mute = Color(0xFF9A9A9A)
    val Hairline = Color(0xFF2A2A2A)
    val Accent = Color(0xFF1D9BF0)
}

private fun outfit(weight: Int) = Font(
    R.font.outfit,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val AlarmFont = FontFamily(
    outfit(400),
    outfit(500),
    outfit(600),
    outfit(700),
    outfit(800),
    outfit(900),
)

private fun style(weight: FontWeight, size: Int) = TextStyle(
    fontFamily = AlarmFont,
    fontWeight = weight,
    fontSize = size.sp,
    color = AlarmPalette.White,
    lineHeight = (size + 6).sp,
)

private val AlarmTypography = Typography(
    displayLarge = style(FontWeight.Black, 40),
    headlineLarge = style(FontWeight.Bold, 34),
    headlineMedium = style(FontWeight.Bold, 28),
    titleLarge = style(FontWeight.Bold, 22),
    titleMedium = style(FontWeight.SemiBold, 18),
    bodyLarge = style(FontWeight.Normal, 18),
    bodyMedium = style(FontWeight.Normal, 16),
    labelLarge = style(FontWeight.Bold, 18),
)

@Composable
fun AlarmTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = AlarmPalette.White,
        onPrimary = AlarmPalette.Black,
        background = AlarmPalette.Black,
        onBackground = AlarmPalette.White,
        surface = AlarmPalette.Black,
        onSurface = AlarmPalette.White,
        error = AlarmPalette.White,
        outline = AlarmPalette.Hairline,
    )
    MaterialTheme(colorScheme = scheme, typography = AlarmTypography) {
        // MaterialTheme does not set LocalContentColor. The default is black,
        // which made the question invisible on the near-black alarm screen.
        CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides AlarmPalette.White,
        ) {
            androidx.compose.material3.ProvideTextStyle(
                value = TextStyle(color = AlarmPalette.White, fontFamily = AlarmFont),
                content = content,
            )
        }
    }
}
