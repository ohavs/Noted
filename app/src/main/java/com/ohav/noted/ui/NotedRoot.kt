package com.ohav.noted.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ohav.noted.NotedApp
import com.ohav.noted.design.Motion
import com.ohav.noted.design.Noted
import com.ohav.noted.design.ToastHost
import com.ohav.noted.design.ToastState
import com.ohav.noted.ui.home.HomeScreen
import com.ohav.noted.ui.settings.SettingsScreen

@Composable
fun NotedRoot(app: NotedApp) {
    var screen by rememberSaveable { mutableStateOf("home") }
    val toasts = remember { ToastState() }
    BackHandler(enabled = screen != "home") { screen = "home" }

    Box(Modifier.fillMaxSize().background(Noted.colors.bg)) {
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                // RTL: going deeper enters from the left (end side), back returns from the right.
                val forward = targetState != "home"
                (slideInHorizontally(Motion.glide()) { if (forward) -it / 3 else it / 3 } + fadeIn(Motion.glide()))
                    .togetherWith(slideOutHorizontally(Motion.glide()) { if (forward) it / 4 else -it / 4 } + fadeOut(Motion.glide()))
            },
            label = "screen",
        ) { s ->
            when (s) {
                "settings" -> SettingsScreen(app, toasts, onBack = { screen = "home" })
                else -> HomeScreen(app, toasts, onSettings = { screen = "settings" })
            }
        }
        ToastHost(toasts, Modifier.align(Alignment.BottomCenter))
    }
}
