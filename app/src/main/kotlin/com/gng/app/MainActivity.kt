package com.gng.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gng.app.data.ThemeMode
import com.gng.app.ui.root.RootScreen
import com.gng.app.ui.theme.GngTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val theme by container.settings.theme.collectAsStateWithLifecycle(ThemeMode.System)
            val dynamic by container.settings.dynamicColor.collectAsStateWithLifecycle(true)
            GngTheme(theme, dynamic) { RootScreen() }
        }
    }
}
