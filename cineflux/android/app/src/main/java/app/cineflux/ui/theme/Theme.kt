package app.cineflux.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Paleta Apple TV + Netflix: lienzo negro absoluto, tipografía blanca y un
// único acento rojo para la acción principal.
val Ink = Color(0xFF000000)
val Panel = Color(0xFF0B0B0D)
val PanelHigh = Color(0xFF1A1A1F)
val Stroke = Color(0xFF2C2C31)
val Text = Color(0xFFFFFFFF)
val Muted = Color(0xFFA1A1AA)
val Tomato = Color(0xFFFA320A)
val Golden = Color(0xFFF5C518)
val Rose = Color(0xFFE50914)

private val Colors = darkColorScheme(
    primary = Rose,
    onPrimary = Color.White,
    secondary = Golden,
    onSecondary = Color(0xFF191006),
    background = Ink,
    onBackground = Text,
    surface = Panel,
    onSurface = Text,
    surfaceVariant = PanelHigh,
    onSurfaceVariant = Muted,
    outline = Stroke,
    outlineVariant = Stroke,
    error = Color(0xFFFF7A7A),
    onError = Color(0xFF1A0707)
)

private val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, fontSize = 42.sp, letterSpacing = (-1.2).sp, color = Text),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = (-0.8).sp, color = Text),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 21.sp, letterSpacing = (-0.3).sp, color = Text),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Text),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Text),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 22.sp, color = Text),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 19.sp, color = Muted),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Text),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, color = Muted)
)

@Composable
fun CinefluxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = AppTypography, content = content)
}
