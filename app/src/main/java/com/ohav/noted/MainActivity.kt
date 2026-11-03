package com.ohav.noted

import android.os.Looper
import com.google.android.gms.maps.MapsInitializer
import com.ohav.noted.places.PlacesApi
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
import com.ohav.noted.ui.intro.IntroHost

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
        // the splash plays on a fresh launch only — not when a notification / the widget / a link
        // brought the app up for one thing, and not when the activity is just recreated
        val splash = savedInstanceState == null && openNote.value == null && invite.value == null
        setContent {
            val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            settings?.let {
                NotedTheme(it) { IntroHost(app, it.onboarded, splash) { NotedRoot(app, openNote, invite) } }
            }
        }
        warmMaps()
    }

    /**
     * Load the Maps SDK once the first screen is idle, so the places tab doesn't pay for it
     * (hundreds of ms on the main thread) the first time it opens.
     */
    private fun warmMaps() {
        if (!PlacesApi.enabled) return
        Looper.myQueue().addIdleHandler {
            runCatching { MapsInitializer.initialize(applicationContext, MapsInitializer.Renderer.LATEST) {} }
            false
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
        intent?.getStringExtra(EXTRA_NEW_IN)?.let { openNote.value = NEW_PREFIX + (intent.getStringExtra(EXTRA_NEW_MODE) ?: "text") + ":" + it }
        if (intent?.action == Intent.ACTION_VIEW) com.ohav.noted.sync.Sharing.tokenOf(intent.dataString)?.let { invite.value = it }
        // from the watch ("open on the phone"): noted://note/<id>
        if (intent?.action == Intent.ACTION_VIEW && intent.data?.scheme == "noted" && intent.data?.host == "note") {
            intent.data?.lastPathSegment?.let { openNote.value = it }
        }
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
        /** How the new note opens: text / list / voice / camera / remind (see ui.editor.Start). */
        const val EXTRA_NEW_MODE = "new_mode"
        /** [openNote] value meaning "create one: <mode>:<category>" rather than an id. */
        const val NEW_PREFIX = "new:"
    }
}
