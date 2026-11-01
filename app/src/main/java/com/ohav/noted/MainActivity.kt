package com.ohav.noted

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ohav.noted.design.NotedTheme
import com.ohav.noted.ui.NotedRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as NotedApp
        setContent {
            val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            settings?.let { NotedTheme(it) { NotedRoot(app) } }
        }
    }

    override fun onResume() {
        super.onResume()
        (application as NotedApp).updater.onResume()
    }
}
