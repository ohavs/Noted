package com.ohav.noted.ui.lock

import android.content.Context
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLayoutDirection
import com.ohav.noted.NotedApp
import com.ohav.noted.data.Privacy
import com.ohav.noted.design.Motion
import com.ohav.noted.design.Noted
import com.ohav.noted.design.Overlays
import com.ohav.noted.design.Ph
import com.ohav.noted.design.Squircle
import com.ohav.noted.design.press
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ what cards see

/** Privacy as the cards read it: per-note flags and the locked note open right now. */
data class PrivacyView(val flags: Map<String, Int> = emptyMap(), val unlocked: String? = null) {
    fun of(id: String) = flags[id] ?: 0
    fun blurTitle(id: String) = of(id) and Privacy.BLUR_TITLE != 0
    fun blurBody(id: String) = of(id) and Privacy.BLUR_BODY != 0
    /** Locked and not open for this visit: show nothing of it. */
    fun locked(id: String) = of(id) and Privacy.LOCK != 0 && unlocked != id
}

val LocalPrivacy = androidx.compose.runtime.compositionLocalOf { PrivacyView() }

val LocalLock = staticCompositionLocalOf<LockState> { error("LockState not provided") }

/**
 * Frosted glass over content: a real blur on API 31+, below that the paper colour laid over it.
 * [amount] is read in the draw phase only (0 = clear, 1 = fully veiled).
 */
fun Modifier.veil(paper: Color, radius: Dp = 10.dp, amount: () -> Float): Modifier =
    if (Build.VERSION.SDK_INT >= 31) graphicsLayer {
        val a = amount()
        renderEffect = if (a > .01f) BlurEffect(a * radius.toPx(), a * radius.toPx(), TileMode.Decal) else null
    } else drawWithContent {
        drawContent()
        val a = amount()
        if (a > .01f) drawRect(paper.copy(alpha = .93f * a.coerceIn(0f, 1f)))
    }

// ------------------------------------------------------------------ the lock

/** Asks for the app code (or a fingerprint). Shown by [LockHost], above sheets and dialogs. */
@Stable
class LockState {
    internal class Req(val subtitle: String?, val change: Boolean, val forget: Boolean, val onOk: () -> Unit)

    internal var req by mutableStateOf<Req?>(null)
    internal var gen by mutableIntStateOf(0)

    /** Unlock (sets the code up first when there is none). */
    fun ask(subtitle: String?, onOk: () -> Unit) {
        req = Req(subtitle, change = false, forget = false, onOk = onOk); gen++
    }

    /** Change the code: the current one, then a new one twice. */
    fun change(onOk: () -> Unit) {
        req = Req(null, change = true, forget = false, onOk = onOk); gen++
    }

    internal fun close() { req = null }
}

private enum class Step { Verify, New, Confirm }

/**
 * Full-screen lock: the app behind it frosts over, a lock glyph, four dots and our own keypad
 * (digits stay left-to-right like every phone keypad). Fingerprint starts by itself when it's on;
 * a match sweeps the glyph from the bottom up and the lock opens.
 */
@Composable
fun LockHost(app: NotedApp, state: LockState, overlays: Overlays) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val priv = app.privacy

    val p = remember { Animatable(0f) }
    var shown by remember { mutableStateOf<LockState.Req?>(null) }
    var step by remember { mutableStateOf(Step.Verify) }
    var code by remember { mutableStateOf("") }
    var first by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val shake = remember { Animatable(0f) }
    val sweep = remember { Animatable(0f) }   // fingerprint match, bottom → top
    val opened = remember { Animatable(0f) }  // the shackle lifts
    var busy by remember { mutableStateOf(false) }
    var cancel by remember { mutableStateOf<CancellationSignal?>(null) }
    var ticker by remember { mutableStateOf<Job?>(null) }
    val bioOn = remember(shown) { priv.hasCode && priv.biometric && priv.biometricAvailable() }

    fun stopBio() { cancel?.cancel(); cancel = null }

    fun dismiss() {
        stopBio()
        state.close()
    }

    fun success() {
        val r = shown ?: return
        busy = true
        stopBio()
        haptics.confirm()
        scope.launch {
            opened.animateTo(1f, Motion.touch())
            state.close()
            r.onOk()
        }
    }

    fun startBio() {
        if (!bioOn || step != Step.Verify || Build.VERSION.SDK_INT < 28) return
        stopBio()
        cancel = biometric(ctx, "פתיחת פתק נעול", credential = false, onOk = {
            cancel = null
            scope.launch {
                haptics.light()
                sweep.animateTo(1f, Motion.glide())
                success()
            }
        })
    }

    fun waitMessage() {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                val left = priv.waitLeft()
                if (left <= 0) { error = null; break }
                error = "יותר מדי ניסיונות. אפשר שוב בעוד ${(left + 999) / 1000} שנ׳"
                delay(250)
            }
        }
    }

    fun reject(msg: String) {
        haptics.reject()
        error = msg
        code = ""
        scope.launch { shake.snapTo(0f); shake.animateTo(0f, Motion.touch(), initialVelocity = 620f) }
    }

    fun entered() {
        val r = shown ?: return
        when (step) {
            Step.Verify -> {
                if (priv.waitLeft() > 0) { reject(""); waitMessage(); return }
                if (priv.check(code)) {
                    if (r.change || r.forget) { haptics.light(); step = Step.New; code = ""; error = null }
                    else success()
                } else if (priv.waitLeft() > 0) { reject(""); waitMessage() }
                else reject("קוד שגוי")
            }
            Step.New -> { first = code; code = ""; error = null; step = Step.Confirm; haptics.light() }
            Step.Confirm -> if (code == first) {
                priv.setCode(code)
                success()
            } else {
                step = Step.New; first = ""
                reject("הקודים לא תאמו — מתחילים שוב")
            }
        }
    }

    fun type(d: Char) {
        if (busy || code.length >= Privacy.CODE_LEN) return
        if (step == Step.Verify && priv.waitLeft() > 0) { haptics.reject(); waitMessage(); return }
        haptics.tick()
        if (error != null && step != Step.Confirm) error = null
        code += d
        if (code.length == Privacy.CODE_LEN) scope.launch { delay(90); entered() }
    }

    LaunchedEffect(state.req, state.gen) {
        val r = state.req
        if (r != null) {
            shown = r
            step = if (!priv.hasCode) Step.New else Step.Verify
            code = ""; first = ""; error = null; busy = false
            sweep.snapTo(0f); opened.snapTo(0f); shake.snapTo(0f)
            if (step == Step.Verify && priv.waitLeft() > 0) waitMessage()
            haptics.light()
            launch { p.animateTo(1f, Motion.glide()) }
            delay(220)
            startBio()
        } else {
            stopBio()
            ticker?.cancel()
            p.animateTo(0f, Motion.glide())
            shown = null
        }
    }
    // the app behind frosts over while the lock is up
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { p.value }.collect { overlays.blur = it.coerceIn(0f, 1f) }
    }
    DisposableEffect(Unit) { onDispose { stopBio() } }
    BackHandler(enabled = state.req != null) { dismiss() }

    val r = shown ?: return
    val title = when (step) {
        Step.Verify -> if (r.change) "הקוד הנוכחי" else "נעול"
        Step.New -> if (r.change || r.forget || priv.hasCode) "קוד חדש" else "בחירת קוד"
        Step.Confirm -> "שוב, לאישור"
    }
    val sub = error ?: when (step) {
        Step.Verify -> r.subtitle ?: if (bioOn) "קוד או טביעת אצבע" else "הקוד של הפתקים הנעולים"
        Step.New -> if (r.change) "${Privacy.CODE_LEN} ספרות" else "קוד אחד לכל הפתקים הנעולים · ${Privacy.CODE_LEN} ספרות"
        Step.Confirm -> "אותן ${Privacy.CODE_LEN} ספרות"
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = p.value.coerceIn(0f, 1f) }
            .background(c.bg.copy(alpha = if (c.isDark) .78f else .72f))
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .graphicsLayer { translationY = (1f - p.value) * 46.dp.toPx() }
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Glyph(sweep = { sweep.value }, opened = { opened.value })
            Spacer(Modifier.height(22.dp))
            Text(title, style = t.title, color = c.ink, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(
                sub, style = t.sub, color = if (error != null) c.danger else c.ink2,
                textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.height(42.dp),
            )
            Spacer(Modifier.height(10.dp))
            Dots(code.length, full = { opened.value }, Modifier.graphicsLayer { translationX = shake.value.dp.toPx() })
            Spacer(Modifier.weight(1f))
            Keypad(
                bio = bioOn && step == Step.Verify,
                onDigit = ::type,
                onBack = { if (code.isNotEmpty() && !busy) { haptics.tick(); code = code.dropLast(1) } },
                onBio = ::startBio,
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                FootLink("ביטול") { dismiss() }
                Spacer(Modifier.weight(1f))
                if (step == Step.Verify && priv.hasCode && Build.VERSION.SDK_INT >= 29) FootLink("שכחתי את הקוד") {
                    stopBio()
                    biometric(ctx, "איפוס קוד הפתקים", credential = true, onOk = {
                        haptics.light()
                        shown = LockState.Req(r.subtitle, r.change, forget = true, onOk = r.onOk)
                        step = Step.New; code = ""; first = ""; error = null
                    })
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** The lock. A fingerprint match fills a fingerprint from the bottom up, then the shackle lifts. */
@Composable
private fun Glyph(sweep: () -> Float, opened: () -> Float) {
    val c = Noted.colors
    Box(
        Modifier
            .size(96.dp)
            .graphicsLayer { val k = 1f + .08f * opened(); scaleX = k; scaleY = k }
            .clip(Squircle(32.dp))
            .background(c.accentSoft),
        contentAlignment = Alignment.Center,
    ) {
        // closed lock → fades into the open one as the shackle lifts
        Icon(
            Ph.LockFill, null,
            Modifier.size(40.dp).graphicsLayer { alpha = (1f - opened()) * (1f - sweep()) },
            tint = c.accent,
        )
        Icon(
            Ph.LockSimpleOpen, null,
            Modifier.size(40.dp).graphicsLayer { alpha = opened(); translationY = (1f - opened()) * 4.dp.toPx() },
            tint = c.accent,
        )
        // the matched fingerprint: drawn in, bottom to top
        Icon(
            Ph.Fingerprint, null,
            Modifier.size(46.dp)
                .graphicsLayer { alpha = (if (sweep() > 0f) 1f else 0f) * (1f - opened()) }
                .drawWithContent {
                    val s = sweep()
                    clipRect(top = size.height * (1f - s)) { this@drawWithContent.drawContent() }
                },
            tint = c.accent,
        )
    }
}

@Composable
private fun Dots(n: Int, full: () -> Float, modifier: Modifier) {
    val c = Noted.colors
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            repeat(Privacy.CODE_LEN) { i ->
                val on = i < n
                val k = remember { Animatable(0f) }
                LaunchedEffect(on) { k.animateTo(if (on) 1f else 0f, Motion.touch()) }
                Box(
                    Modifier
                        .size(15.dp)
                        .graphicsLayer { val s = .78f + .22f * k.value; scaleX = s; scaleY = s }
                        .drawBehind {
                            val r = size.minDimension / 2
                            val f = maxOf(k.value, full()).coerceIn(0f, 1f)
                            drawCircle(c.ink3, r - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
                            if (f > 0f) drawCircle(c.accent, r * f)
                        },
                )
            }
        }
    }
}

@Composable
private fun Keypad(bio: Boolean, onDigit: (Char) -> Unit, onBack: () -> Unit, onBio: () -> Unit) {
    val rows = listOf("123", "456", "789")
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    row.forEach { d -> Key(onClick = { onDigit(d) }) { Digit(d) } }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                if (bio) Key(accent = true, onClick = onBio) {
                    Icon(Ph.Fingerprint, "טביעת אצבע", Modifier.size(30.dp), tint = Noted.colors.accent)
                } else Spacer(Modifier.size(KEY))
                Key(onClick = { onDigit('0') }) { Digit('0') }
                Key(quiet = true, onClick = onBack) {
                    Icon(Ph.Backspace, "מחיקה", Modifier.size(26.dp), tint = Noted.colors.ink2)
                }
            }
        }
    }
}

private val KEY = 74.dp

@Composable
private fun Key(accent: Boolean = false, quiet: Boolean = false, onClick: () -> Unit, content: @Composable () -> Unit) {
    val c = Noted.colors
    Box(
        Modifier
            .size(KEY)
            .press(scale = .88f, haptic = false, onClick = onClick)
            .clip(Squircle(28.dp))
            .background(
                when {
                    accent -> c.accentSoft
                    quiet -> Color.Transparent
                    else -> c.surface
                },
            ),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun Digit(d: Char) {
    Text(d.toString(), style = Noted.type.title.copy(fontSize = 30.sp, lineHeight = 30.sp), color = Noted.colors.ink)
}

@Composable
private fun FootLink(text: String, onClick: () -> Unit) {
    Box(Modifier.clip(Squircle(14.dp)).press(scale = .94f, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(text, style = Noted.type.label, color = Noted.colors.ink2)
    }
}

/**
 * The system fingerprint / face check (or the phone's own screen lock, [credential]). The platform
 * prompt, not androidx.biometric: it needs no FragmentActivity. API 28+ (29+ for [credential]).
 */
internal fun biometric(ctx: Context, title: String, credential: Boolean, onOk: () -> Unit): CancellationSignal? {
    if (Build.VERSION.SDK_INT < 28 || credential && Build.VERSION.SDK_INT < 29) return null
    val exec = ctx.mainExecutor
    val b = android.hardware.biometrics.BiometricPrompt.Builder(ctx).setTitle(title)
    if (credential) {
        if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        else {
            @Suppress("DEPRECATION")
            b.setDeviceCredentialAllowed(true)
        }
    } else {
        b.setNegativeButton("קוד", exec) { _, _ -> }
        if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK)
        if (Build.VERSION.SDK_INT >= 29) b.setConfirmationRequired(false)
    }
    val signal = CancellationSignal()
    return runCatching {
        b.build().authenticate(signal, exec, object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?) = onOk()
        })
        signal
    }.getOrNull()
}
