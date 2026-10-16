package org.miu.cookbook

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import org.miu.cookbook.di.LocalAppContainer
import org.miu.cookbook.ui.CookbookRoot
import org.miu.cookbook.ui.theme.CookbookTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as CookbookApplication).container
        setContent {
            CookbookTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    CookbookRoot()
                }
            }
        }
    }
}
