package org.miu.cookbook.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = TomatoLight,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = TomatoContainerLight,
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF410002),
    secondary = TerracottaLight,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    secondaryContainer = TerracottaContainerLight,
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF2C1600),
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
)

private val DarkColors = darkColorScheme(
    primary = TomatoDark,
    onPrimary = androidx.compose.ui.graphics.Color(0xFF690005),
    primaryContainer = TomatoContainerDark,
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFFFDAD6),
    secondary = TerracottaDark,
    onSecondary = androidx.compose.ui.graphics.Color(0xFF472A00),
    secondaryContainer = TerracottaContainerDark,
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFFFDDB6),
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
)

/**
 * @param dynamicColor Android 12+ 用系统取色（壁纸主题），否则用上面的暖色方案
 */
@Composable
fun CookbookTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = CookbookTypography,
        content = content,
    )
}
