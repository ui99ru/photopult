package ru.ui99.photopult.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColors = darkColorScheme(
    primary = WarmAmber,
    onPrimary = DarkBackground,
    secondary = WarmClay,
    background = DarkBackground,
    onBackground = OnDark,
    surface = DarkSurface,
    onSurface = OnDark,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = OnDarkMuted,
)

private val LightColors = lightColorScheme(
    primary = WarmAmberDark,
    onPrimary = LightSurface,
    secondary = WarmClay,
    background = LightBackground,
    onBackground = OnLight,
    surface = LightSurface,
    onSurface = OnLight,
)

/**
 * App theme. Dark by default per the brief; still honours a device set to light mode
 * so the app never looks broken.
 */
@Composable
fun PhotopultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
