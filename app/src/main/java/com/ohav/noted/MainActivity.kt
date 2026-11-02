package com.ohav.noted

import android.os.Build
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
        preferHighRefreshRate()
        val app = application as NotedApp
        setContent {
            val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            settings?.let { NotedTheme(it) { NotedRoot(app) } }
        }
    }

    /**
     * Ask for the fastest refresh rate the panel has at its current resolution. Without it some
     * phones keep the app at 60 Hz and every transition looks half as smooth.
     */
    private fun preferHighRefreshRate() = runCatching {
        @Suppress("DEPRECATION")
        val d = (if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay) ?: return@runCatching
        val cur = d.mode
        val best = d.supportedModes
            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return@runCatching
        if (window.attributes.preferredDisplayModeId != best.modeId) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
        }
    }

    override fun onResume() {
        super.onResume()
        preferHighRefreshRate()
        (application as NotedApp).updater.onResume()
    }
}
