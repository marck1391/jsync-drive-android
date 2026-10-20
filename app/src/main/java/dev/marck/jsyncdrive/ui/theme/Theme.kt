/*
 * DESIGN: a clean cloud drive. Quiet neutral surfaces, one blue for what you can act on, rounded shapes, and the
 * content (your folders and the pictures in them) as the loudest thing on screen. Follows the system light/dark
 * setting. State is told by an icon and a word, never by colour alone.
 */
package dev.marck.jsyncdrive.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8AB0FF),
    onPrimary = Color(0xFF0A2A66),
    primaryContainer = Color(0xFF1F3B73),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFFB4C2DC),
    onSecondary = Color(0xFF1E2A40),
    secondaryContainer = Color(0xFF2B3750),
    onSecondaryContainer = Color(0xFFD9E3FA),
    tertiary = Color(0xFF7ED6A8),
    onTertiary = Color(0xFF00391F),
    background = Color(0xFF111317),
    onBackground = Color(0xFFE5E8EE),
    surface = Color(0xFF111317),
    onSurface = Color(0xFFE5E8EE),
    surfaceVariant = Color(0xFF2A2F38),
    onSurfaceVariant = Color(0xFFA9B1BE),
    surfaceTint = Color(0xFF8AB0FF),
    surfaceContainerLowest = Color(0xFF0D0F13),
    surfaceContainerLow = Color(0xFF171A1F),
    surfaceContainer = Color(0xFF1C2026),
    surfaceContainerHigh = Color(0xFF242932),
    surfaceContainerHighest = Color(0xFF2D333D),
    outline = Color(0xFF727B8A),
    outlineVariant = Color(0xFF2E343D),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE5E8EE),
    inverseOnSurface = Color(0xFF111317),
    inversePrimary = Color(0xFF2B5BC4),
    scrim = Color(0xFF000000),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF2B5BC4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE6FD),
    onPrimaryContainer = Color(0xFF0A2A66),
    secondary = Color(0xFF546178),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE3E9F4),
    onSecondaryContainer = Color(0xFF1E2A40),
    tertiary = Color(0xFF1E7A4F),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFAFBFD),
    onBackground = Color(0xFF1B1F27),
    surface = Color(0xFFFAFBFD),
    onSurface = Color(0xFF1B1F27),
    surfaceVariant = Color(0xFFE6EAF1),
    onSurfaceVariant = Color(0xFF596372),
    surfaceTint = Color(0xFF2B5BC4),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F6FA),
    surfaceContainer = Color(0xFFEEF1F6),
    surfaceContainerHigh = Color(0xFFE7EBF2),
    surfaceContainerHighest = Color(0xFFDFE4EC),
    outline = Color(0xFF7D8696),
    outlineVariant = Color(0xFFDCE1E9),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    inverseSurface = Color(0xFF2E333B),
    inverseOnSurface = Color(0xFFF1F3F8),
    inversePrimary = Color(0xFF8AB0FF),
    scrim = Color(0xFF000000),
)

/** The app's theme. Dynamic colour is deliberately not used: the blue is the identity. */
@Composable
fun JsyncTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme: ColorScheme = if (dark) DarkScheme else LightScheme
    MaterialTheme(colorScheme = scheme, typography = JsyncTypography, shapes = JsyncShapes, content = content)
}
