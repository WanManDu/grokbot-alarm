package works.gauss.grokbotalarm

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Amber = Color(0xFFFFB020)
private val Ink = Color(0xFF1A1200)
private val Background = Color(0xFF0E0E12)
private val Surface = Color(0xFF1C1C24)
private val OnBg = Color(0xFFF4F1EA)

@Composable
fun AlarmTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Amber,
            onPrimary = Ink,
            background = Background,
            onBackground = OnBg,
            surface = Surface,
            onSurface = OnBg,
            error = Color(0xFFFF5A4A),
        ),
        content = content,
    )
}
