package org.miu.cookbook

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.miu.cookbook.di.LocalAppContainer
import org.miu.cookbook.ui.CookbookRoot
import org.miu.cookbook.ui.theme.CookbookTheme
import org.miu.cookbook.ui.theme.ThemeMode
import org.miu.cookbook.ui.theme.ThemePreferences

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as CookbookApplication).container
        setContent {
            // 外观由侧边栏底部决定，所以状态得放在主题外面
            var themeMode by remember { mutableStateOf(ThemePreferences.load(this)) }
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            CookbookTheme(darkTheme = darkTheme) {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    CookbookRoot(
                        themeMode = themeMode,
                        onThemeModeChange = { mode ->
                            themeMode = mode
                            ThemePreferences.save(this, mode)
                        },
                    )
                }
            }
        }
    }
}
