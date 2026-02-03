package cn.com.lg.epubreader.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Basic Colors
val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

// Eye Care Colors
val EyeCareBackground = Color(0xFFF5F0E6) // Warm paper-like
val EyeCareSurface = Color(0xFFEBE5D9)
val EyeCareText = Color(0xFF3C3C3C)

// Night Colors
val NightBackground = Color(0xFF121212)
val NightSurface = Color(0xFF1E1E1E)
val NightText = Color(0xFFE0E0E0)

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80,
    background = NightBackground,
    surface = NightSurface,
    onBackground = NightText,
    onSurface = NightText
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

val EyeCareColorScheme = lightColorScheme(
    primary = Color(0xFF795548), // Brownish
    secondary = Color(0xFF5D4037),
    background = EyeCareBackground,
    surface = EyeCareSurface,
    onBackground = EyeCareText,
    onSurface = EyeCareText
)

enum class ThemeMode {
    SYSTEM, LIGHT, DARK, EYE_CARE
}

@Composable
fun EpubReaderTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = when (themeMode) {
        ThemeMode.DARK -> DarkColorScheme
        ThemeMode.LIGHT -> LightColorScheme
        ThemeMode.EYE_CARE -> EyeCareColorScheme
        ThemeMode.SYSTEM -> if (darkTheme) DarkColorScheme else LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = themeMode == ThemeMode.LIGHT || themeMode == ThemeMode.EYE_CARE
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography, // Default typography for now
        content = content
    )
}
