package com.ohav.noted.ui.intro

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.ohav.noted.NotedApp
import com.ohav.noted.design.Motion
import kotlinx.coroutines.launch

/**
 * The app with its two front doors: the launch splash (cold starts from the launcher) and the
 * first-run intro (until it is seen; settings can bring it back). The app comes forward from just
 * behind as each one leaves.
 */
@Composable
fun IntroHost(app: NotedApp, onboarded: Boolean, splash: Boolean, content: @Composable () -> Unit) {
    val splashState = remember { SplashState(splash) }
    val scope = rememberCoroutineScope()
    // the intro's presence follows the setting; its look glides (0 = gone, 1 = up)
    var introOn by remember { mutableStateOf(!onboarded) }
    val intro = remember { Animatable(if (onboarded) 0f else 1f) }
    LaunchedEffect(onboarded) {
        if (!onboarded) {
            introOn = true
            intro.animateTo(1f, Motion.glide())
        } else {
            intro.animateTo(0f, Motion.glide(visibilityThreshold = .004f))
            introOn = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val k = .94f + .06f * splashState.reveal()
                scaleX = k; scaleY = k
            },
        ) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    val k = 1f - .05f * intro.value
                    scaleX = k; scaleY = k
                },
            ) { content() }
            if (introOn) Box(
                Modifier.fillMaxSize().graphicsLayer {
                    val v = intro.value.coerceIn(0f, 1f)
                    alpha = v
                    val k = 1.04f - .04f * v
                    scaleX = k; scaleY = k
                },
            ) { Onboarding(app) { scope.launch { app.settings.setOnboarded(true) } } }
        }
        SplashOverlay(splashState)
    }
}
