package com.ohav.noted.wear

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.ohav.noted.wear.ui.Actions
import com.ohav.noted.wear.ui.C
import com.ohav.noted.wear.ui.Flash
import com.ohav.noted.wear.ui.FlashHost
import com.ohav.noted.wear.ui.HomeScreen
import com.ohav.noted.wear.ui.NoteScreen
import com.ohav.noted.wear.ui.Ph
import com.ohav.noted.wear.ui.RecordScreen
import com.ohav.noted.wear.ui.RemindersScreen
import com.ohav.noted.wear.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    /** What the tile asked for: "dictate", "record", or "note:<id>". */
    private val launch = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) take(intent)
        setContent { App() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    override fun onResume() {
        super.onResume()
        store.refresh()
    }

    private fun take(i: Intent?) {
        i?.getStringExtra("do")?.let { launch.value = it }
        i?.getStringExtra("open")?.let { launch.value = "note:$it" }
    }

    @androidx.compose.runtime.Composable
    private fun App() {
        val nav = rememberSwipeDismissableNavController()
        val flash = remember { Flash() }
        val s by store.state.collectAsState()
        val dictInto = remember { mutableStateOf<String?>(null) }
        val recInto = remember { mutableStateOf<String?>(null) }

        val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val text = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                store.dictated(text, dictInto.value)
                flash.show(if (dictInto.value == null) "נשמר כפתק חדש" else "נוסף לפתק")
            }
        }
        val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) nav.navigate("rec") else flash.show("צריך גישה למיקרופון", Ph.Microphone)
        }

        val actions = remember {
            Actions(
                dictate = { into ->
                    dictInto.value = into
                    val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL")
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, if (into == null) "מה לרשום?" else "מה להוסיף?")
                    try { speech.launch(i) } catch (_: ActivityNotFoundException) { flash.show("אין בשעון זיהוי דיבור", Ph.WarningCircle) }
                },
                record = { into ->
                    recInto.value = into
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) nav.navigate("rec")
                    else mic.launch(Manifest.permission.RECORD_AUDIO)
                },
                openNote = { id -> nav.navigate("note/$id") },
                openOnPhone = { id ->
                    val i = Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_BROWSABLE).setData(Uri.parse("noted://note/$id"))
                    runCatching {
                        val f = RemoteActivityHelper(this).startRemoteActivity(i)
                        f.addListener({
                            if (runCatching { f.get() }.isSuccess) flash.show("נפתח בטלפון", Ph.DeviceMobile)
                            else flash.show("הטלפון לא זמין", Ph.WarningCircle)
                        }, ContextCompat.getMainExecutor(this))
                    }.onFailure { flash.show("הטלפון לא זמין", Ph.WarningCircle) }
                },
                reminders = { nav.navigate("rem") },
                settings = { nav.navigate("settings") },
                flash = flash,
            )
        }

        // the tile: dictate / record / a note, once per tap
        val asked by launch
        LaunchedEffect(asked) {
            val a = asked ?: return@LaunchedEffect
            launch.value = null
            when {
                a == "dictate" -> actions.dictate(null)
                a == "record" -> actions.record(null)
                a.startsWith("note:") -> { nav.popBackStack("home", inclusive = false); nav.navigate("note/" + a.removePrefix("note:")) }
            }
        }

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Box(Modifier.fillMaxSize().background(C.bg)) {
                SwipeDismissableNavHost(navController = nav, startDestination = "home") {
                    composable("home") { HomeScreen(actions) }
                    composable("note/{id}") { e -> NoteScreen(e.arguments?.getString("id").orEmpty(), actions) }
                    composable("rem") { RemindersScreen(actions) }
                    composable("settings") { SettingsScreen(actions) }
                    composable("rec") { RecordScreen(recInto.value, actions) { nav.popBackStack() } }
                }
                FlashHost(flash, Color(Palette.accent(s?.accent)), Color(Palette.onAccent(s?.accent)))
            }
        }
    }
}
