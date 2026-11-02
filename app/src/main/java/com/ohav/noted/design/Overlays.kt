package com.ohav.noted.design

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/**
 * App-wide overlay slots, drawn above the nav: a free layer (the lifted card + its menu),
 * one bottom sheet and one dialog. Screens reach them through [LocalOverlays].
 */
@Stable
class Overlays {
    val sheet = SheetState()
    val dialog = DialogState()

    /** Free layer owned by whichever screen set it. */
    var layer by mutableStateOf<(@Composable () -> Unit)?>(null)

    /** 0..1 — how much the content behind the layer is blurred (API 31+). */
    var blur by mutableFloatStateOf(0f)
}

val LocalOverlays = staticCompositionLocalOf<Overlays> { error("Overlays not provided") }

// ------------------------------------------------------------------ sheet

@Stable
class SheetState {
    internal var title by mutableStateOf("")
    internal var content by mutableStateOf<(@Composable ColumnScope.() -> Unit)?>(null)
    internal var open by mutableStateOf(false)
    internal var gen by mutableIntStateOf(0)

    fun show(title: String, content: @Composable ColumnScope.() -> Unit) {
        this.title = title
        this.content = content
        open = true
        gen++
    }

    fun close() {
        open = false
    }
}

/** Bottom sheet on the glide spring; drag the grabber or title down (or fling) to dismiss. */
@Composable
fun SheetHost(state: SheetState) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    val scope = rememberCoroutineScope()
    val p = remember { Animatable(0f) }
    var h by remember { mutableFloatStateOf(1f) }
    val maxH = (LocalConfiguration.current.screenHeightDp * .92f).dp

    LaunchedEffect(state.open, state.gen) {
        if (state.open) haptics.light()
        p.animateTo(if (state.open) 1f else 0f, Motion.glide())
        if (!state.open) state.content = null
    }
    BackHandler(enabled = state.open) { state.close() }
    val content = state.content ?: return

    val drag = rememberDraggableState { dy ->
        scope.launch {
            var v = p.value - dy / h
            if (v > 1f) v = 1f + (v - 1f) * .15f
            p.snapTo(v)
        }
    }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = p.value.coerceIn(0f, 1f) }
                .background(c.scrim)
                .pointerInput(Unit) { detectTapGestures { state.close() } },
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = maxH)
                .onSizeChanged { h = it.height.toFloat().coerceAtLeast(1f) }
                .graphicsLayer { translationY = (1f - p.value) * (h + 30.dp.toPx()) }
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(c.surface)
                // a field in the sheet keeps the sheet above the keyboard
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(bottom = 16.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().draggable(
                    drag, Orientation.Vertical,
                    onDragStopped = { vel ->
                        if (vel > 1400f || p.value < .6f) state.close()
                        else p.animateTo(1f, Motion.glide(), initialVelocity = -vel / h)
                    },
                ),
            ) {
                Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(42.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(c.ink.copy(alpha = .18f)))
                }
                Text(state.title, style = t.title, color = c.ink, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp))
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) { content() }
        }
    }
}

/** A list row inside a sheet — icon, label, trailing hint, hairline under it. */
@Composable
fun SheetRow(icon: ImageVector, label: String, end: String? = null, danger: Boolean = false, last: Boolean = false, onClick: () -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val ink = if (danger) c.danger else c.ink
    Column(Modifier.fillMaxWidth().press(scale = .97f, onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, null, Modifier.size(22.dp), tint = if (danger) c.danger else c.ink2)
            Text(label, style = t.body.copy(fontWeight = FontWeight.SemiBold), color = ink, modifier = Modifier.weight(1f))
            if (end != null) Text(end, style = t.label.copy(fontWeight = FontWeight.SemiBold), color = c.ink3)
        }
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
    }
}

// ------------------------------------------------------------------ dialog

@Stable
class DialogState {
    class Request(
        val icon: ImageVector, val danger: Boolean, val title: String, val text: String?,
        val note: Pair<String, Color>?, val confirm: String, val cancel: String,
        val result: CompletableDeferred<Boolean>,
    )

    internal var req by mutableStateOf<Request?>(null)
    internal var visible by mutableStateOf(false)

    suspend fun confirm(
        title: String, text: String? = null, icon: ImageVector = Ph.Info, danger: Boolean = false,
        note: Pair<String, Color>? = null, confirm: String = "אישור", cancel: String = "ביטול",
    ): Boolean {
        req?.result?.complete(false)
        val d = CompletableDeferred<Boolean>()
        req = Request(icon, danger, title, text, note, confirm, cancel, d)
        visible = true
        return d.await()
    }

    internal fun finish(v: Boolean) {
        req?.result?.complete(v)
        visible = false
    }
}

/** Bottom-anchored card: icon tile, display title, text, optional note chip, stacked actions. */
@Composable
fun DialogHost(state: DialogState) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    val p = remember { Animatable(0f) }
    val fade = remember { Animatable(0f) }
    LaunchedEffect(state.visible, state.req) {
        val r = state.req ?: return@LaunchedEffect
        if (state.visible) { if (r.danger) haptics.medium() else haptics.light() }
        launch { fade.animateTo(if (state.visible) 1f else 0f, Motion.glide()) }
        p.animateTo(if (state.visible) 1f else 0f, Motion.touch())
        if (!state.visible) state.req = null
    }
    BackHandler(enabled = state.visible) { state.finish(false) }
    val r = state.req ?: return
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }.background(c.scrim)
                .pointerInput(Unit) { detectTapGestures { state.finish(false) } },
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(14.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = fade.value
                    translationY = (1f - p.value) * 30.dp.toPx()
                    val s = .94f + .06f * p.value
                    scaleX = s; scaleY = s
                }
                .softShadow(Shadows.float(c.isDark), 26.dp, c.surface)
                .clip(Shapes.lg)
                .background(c.surface)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(start = 22.dp, end = 22.dp, top = 26.dp, bottom = 16.dp),
        ) {
            Box(
                Modifier.size(74.dp).clip(Squircle(24.dp)).background(if (r.danger) c.dangerSoft else c.bg),
                contentAlignment = Alignment.Center,
            ) { Icon(r.icon, null, Modifier.size(30.dp), tint = if (r.danger) c.danger else c.ink) }
            Spacer(Modifier.height(16.dp))
            Text(r.title, style = t.title.copy(fontSize = t.title.fontSize * (27f / 30f)), color = c.ink)
            if (r.text != null) {
                Spacer(Modifier.height(6.dp))
                Text(r.text, style = t.body, color = c.ink2)
            }
            Spacer(Modifier.height(20.dp))
            r.note?.let { (title, color) ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 20.dp).clip(Shapes.sm).background(color).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Ph.Notepad, null, Modifier.size(20.dp), tint = c.noteInk)
                    Text(title, style = t.bodyStrong, color = c.noteInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn(r.confirm, Modifier.fillMaxWidth(), kind = if (r.danger) BtnKind.Danger else BtnKind.Dark) { state.finish(true) }
                Btn(r.cancel, Modifier.fillMaxWidth(), kind = BtnKind.Quiet) { state.finish(false) }
            }
        }
    }
}
