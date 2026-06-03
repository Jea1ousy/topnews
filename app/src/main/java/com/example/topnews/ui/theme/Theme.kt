package com.example.topnews.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = BrandPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3B126F),
    onPrimaryContainer = Color(0xFFE9DFFF),
    secondary = BrandYellow,
    onSecondary = BrandNavy,
    tertiary = BrandYellow,
    onTertiary = BrandNavy,
    background = Color(0xFF101218),
    onBackground = Color(0xFFE4E7EC),
    surface = Color(0xFF181B22),
    onSurface = Color(0xFFE4E7EC),
    surfaceVariant = Color(0xFF252A34),
    onSurfaceVariant = Color(0xFFB8C0CC),
    outlineVariant = Color(0xFF343A46)
)

private val LightColorScheme = lightColorScheme(
    primary = BrandPurple,
    onPrimary = Color.White,
    primaryContainer = BrandPurpleContainer,
    onPrimaryContainer = BrandNavy,
    secondary = BrandNavy,
    onSecondary = Color.White,
    secondaryContainer = BrandYellowContainer,
    onSecondaryContainer = BrandNavy,
    tertiary = BrandYellow,
    onTertiary = BrandNavy,
    background = AppBackground,
    onBackground = TextPrimary,
    surface = AppSurface,
    onSurface = TextPrimary,
    surfaceVariant = ImagePlaceholder,
    onSurfaceVariant = TextSecondary,
    outlineVariant = DividerColor
)

@Composable
fun TopnewsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
