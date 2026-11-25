package dev.handywibowo.diskquilt.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF6FD3A8),
    onPrimary = Color(0xFF00382A),
    secondary = Color(0xFF8FD0E8),
    background = Color(0xFF111417),
    surface = Color(0xFF181C20),
    surfaceVariant = Color(0xFF232A30),
    onSurface = Color(0xFFE6EAEE),
    onSurfaceVariant = Color(0xFFAAB4BD),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1F7A57),
    onPrimary = Color.White,
    secondary = Color(0xFF25647A),
    background = Color(0xFFF7F9FA),
    surface = Color.White,
    surfaceVariant = Color(0xFFE8EDF0),
    onSurface = Color(0xFF131A1F),
    onSurfaceVariant = Color(0xFF4A5761),
)

@Composable
fun DiskQuiltTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme() || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        typography = Typography(),
        content = content,
    )
}
