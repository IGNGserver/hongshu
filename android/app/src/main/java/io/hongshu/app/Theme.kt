@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package io.hongshu.app

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Google Messages Dark Color Tokens (AMOLED Deep Charcoal)
private val GoogleDarkColorScheme = darkColorScheme(
    primary = Color(0xFFA8C7FA),          // Google Soft Blue
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF004A77),
    onPrimaryContainer = Color(0xFFC2E7FF),
    secondary = Color(0xFF7FCFFF),
    onSecondary = Color(0xFF003355),
    secondaryContainer = Color(0xFF284777),
    onSecondaryContainer = Color(0xFFD3E4FF),
    tertiary = Color(0xFFA8C7FA),
    onTertiary = Color(0xFF062E6F),
    tertiaryContainer = Color(0xFF0842A0),
    onTertiaryContainer = Color(0xFFD3E3FD),
    background = Color(0xFF111318),        // Google Messages AMOLED background
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),           // Seamless surface
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF1E2024),
    onSurfaceVariant = Color(0xFFC4C6D0),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1E2025),  // Google Card background
    surfaceContainerHigh = Color(0xFF282A2F), // Dialog & Card item background
    surfaceContainerHighest = Color(0xFF33353A),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474E),
)

// Google Messages Light Color Tokens (Clean White & Soft Blue)
private val GoogleLightColorScheme = lightColorScheme(
    primary = Color(0xFF0B57D0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF041E49),
    secondary = Color(0xFF00639B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC2E7FF),
    onSecondaryContainer = Color(0xFF001D35),
    tertiary = Color(0xFF006399),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC4E7FF),
    onTertiaryContainer = Color(0xFF001E32),
    background = Color(0xFFFBF8FD),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F3FA),
    surfaceContainer = Color(0xFFEDEEF6),
    surfaceContainerHigh = Color(0xFFE7E8F0),
    surfaceContainerHighest = Color(0xFFE1E2EA),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
)

// Google Messages Avatar Color Palette (Vibrant circular avatars)
val GoogleAvatarPalette = listOf(
    Color(0xFFE08D3C), // Amber Orange
    Color(0xFF34A853), // Google Forest Green
    Color(0xFFE91E63), // Rose Pink
    Color(0xFF1A73E8), // Google Blue
    Color(0xFF9C27B0), // Purple
    Color(0xFF00ACC1), // Cyan Teal
    Color(0xFFF25C54), // Coral Red
    Color(0xFF5C6BC0), // Indigo
)

fun avatarColorFor(identifier: String): Color {
    if (identifier.isEmpty()) return GoogleAvatarPalette[0]
    val hash = Math.abs(identifier.hashCode())
    return GoogleAvatarPalette[hash % GoogleAvatarPalette.size]
}

@Composable
fun GoogleMessagesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> GoogleDarkColorScheme
        else -> GoogleLightColorScheme
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
