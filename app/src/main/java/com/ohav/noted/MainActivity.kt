package com.ohav.noted

import android.content.Intent
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ohav.noted.design.NotedTheme
import com.ohav.noted.ui.NotedRoot

class MainActivity : ComponentActivity() {
    /** A note to open, from a reminder notification; consumed by the UI. */
    private val openNote = mutableStateOf<String?>(null)

    /** An invite link's token, opened from outside; consumed by the UI. */
    private val invite = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        preferHighRefreshRate()
        val app = application as NotedApp
        if (savedInstanceState == null) takeOpen(intent)
        setContent {
            val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            settings?.let { NotedTheme(it) { NotedRoot(app, openNote, invite) } }
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeOpen(intent)
    }

    private fun takeOpen(intent: Intent?) {
        intent?.getStringExtra(com.ohav.noted.reminders.Notifier.EXTRA_NOTE)?.let { openNote.value = it }
        intent?.getStringExtra(EXTRA_NEW_IN)?.let { openNote.value = NEW_PREFIX + it }
        if (intent?.action == Intent.ACTION_VIEW) com.ohav.noted.sync.Sharing.tokenOf(intent.dataString)?.let { invite.value = it }
    }

    override fun onResume() {
        super.onResume()
        preferHighRefreshRate()
        val app = application as NotedApp
        app.updater.onResume()
        com.ohav.noted.sync.Push.foreground = true
        // "allow all the time" is granted outside the app; coming back is when fences can be registered
        app.scope.launch { app.places.sync() }
    }

    override fun onPause() {
        super.onPause()
        com.ohav.noted.sync.Push.foreground = false
    }

    companion object {
        /** From the widget's +: a new note in this category ("" = the first one). */
        const val EXTRA_NEW_IN = "new_in"
        /** [openNote] value meaning "create one in <category>" rather than an id. */
        const val NEW_PREFIX = "new:"
    }
}
