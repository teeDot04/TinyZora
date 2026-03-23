package com.telo.tinyzora.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val PremiumDarkColorScheme = darkColorScheme(
    primary = PastelBlue,
    secondary = PastelGreen,
    tertiary = PastelYellow,
    background = BgDarkStart,
    surface = SurfaceCard,
    surfaceVariant = SurfaceDarker,
    onPrimary = TextPrimary,
    onSecondary = BgDarkStart,
    onTertiary = BgDarkStart,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextPrimary,
    error = LossRed,
    errorContainer = LossRed.copy(alpha = 0.2f),
    onError = TextPrimary,
    onErrorContainer = LossRed
)

private val PremiumLightColorScheme = lightColorScheme(
    primary = Color(0xFF6B94E5), // Deeper Pastel Blue for contrast
    secondary = Color(0xFF75A672), // Deeper Pastel Green
    tertiary = Color(0xFFD6A045), // Deeper Pastel Yellow
    background = Color(0xFFF1F5F9), // Very light slate
    surface = Color(0xFFE2E8F0), 
    surfaceVariant = Color(0xFFCBD5E1),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF0F172A), // Dark Navy Text
    onSurface = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFF334155),
    error = Color(0xFFD32F2F),
    errorContainer = Color(0xFFFFCDD2),
    onError = Color.White,
    onErrorContainer = Color(0xFFB71C1C)
)

@Composable
fun TinyZoraTheme(
    dynamicColor: Boolean = true, // Enables Hybrid Edge Gallery + Dynamic Wallpaper 
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> PremiumDarkColorScheme
        else -> PremiumLightColorScheme
    }
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
