package com.ohav.noted.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ohav.noted.NotedApp
import com.ohav.noted.data.BlockType
import com.ohav.noted.data.Blocks
import com.ohav.noted.data.NoteEntity
import com.ohav.noted.design.IconButton
import com.ohav.noted.design.Motion
import com.ohav.noted.design.NoteColor
import com.ohav.noted.design.Noted
import com.ohav.noted.design.Ph
import com.ohav.noted.design.Shapes
import com.ohav.noted.design.ToastState
import com.ohav.noted.design.press
import com.ohav.noted.update.Updater
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(app: NotedApp, toasts: ToastState, onSettings: () -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val scope = rememberCoroutineScope()
    val notes by app.notes.notes.collectAsStateWithLifecycle(initialValue = emptyList())
    val cats by app.notes.categories.collectAsStateWithLifecycle(initialValue = emptyList())
    val update by app.updater.state.collectAsState()
    var cat by rememberSaveable { mutableStateOf("all") }
    val shown = remember(notes, cat) { if (cat == "all") notes else notes.filter { it.categoryId == cat } }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "head") {
                Column(Modifier.statusBarsPadding().padding(top = 8.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        IconButton(Ph.Gear, "הגדרות", onClick = onSettings)
                    }
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        val all = listOf("all" to "כל הפתקים") + cats.map { it.id to it.name }
                        items(all, key = { it.first }) { (id, name) ->
                            val on = id == cat
                            val col by animateColorAsState(if (on) c.ink else c.ink3, Motion.glide(), label = "cat")
                            val sc by animateFloatAsState(if (on) 1f else .62f, Motion.glide(), label = "catScale")
                            Text(
                                name, style = t.hero, color = col, maxLines = 1,
                                modifier = Modifier
                                    .graphicsLayer { scaleX = sc; scaleY = sc; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 1f) }
                                    .press(scale = .96f) { cat = id },
                            )
                        }
                    }
                    Text(
                        "${shown.size} פתקים", style = t.caption, color = c.ink2,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    )
                }
            }
            (update as? Updater.State.Available)?.let { a ->
                item(key = "update") {
                    UpdateBanner(a.versionName) { onSettings() }
                }
            }
            items(shown, key = { it.id }) { n ->
                NoteCard(
                    n,
                    modifier = Modifier.animateItem(fadeInSpec = Motion.glide(), placementSpec = Motion.glide(), fadeOutSpec = Motion.glide()),
                    onClick = { toasts.show("העורך נבנה בשלב 4", Ph.NotePencil) },
                    onLongClick = {
                        scope.launch { app.notes.trash(n.id) }
                        toasts.show("הפתק הועבר לסל", Ph.CheckCircleFill, action = "ביטול") { scope.launch { app.notes.restore(n.id) } }
                    },
                )
            }
        }

        // new note — squircle FAB at the bottom end (left in RTL), as in the prototype
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(20.dp)
                .size(64.dp)
                .clip(Shapes.lg)
                .background(c.accent)
                .press(scale = .9f) {
                    scope.launch {
                        val color = NoteColor.entries.random()
                        app.notes.create(if (cat == "all") "home" else cat, color, title = "פתק חדש")
                    }
                },
            contentAlignment = Alignment.Center,
        ) { Icon(Ph.Plus, "פתק חדש", Modifier.size(28.dp), tint = c.accentInk) }
    }
}

@Composable
private fun UpdateBanner(version: String, onClick: () -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(Shapes.lg).background(c.inverse).press(scale = .97f, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Ph.Sparkle, null, Modifier.size(22.dp), tint = c.accent)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("גרסה חדשה מוכנה", style = t.label, color = c.inverseInk)
            Text("noted $version", style = t.caption, color = c.inverseInk2)
        }
        Icon(Ph.CaretEnd, null, Modifier.size(18.dp), tint = c.inverseInk2)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(n: NoteEntity, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    val blocks = remember(n.blocks) { Blocks.parse(n.blocks) }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) .975f else 1f, Motion.touch(), label = "card")
    Column(
        modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .graphicsLayer { scaleX = s; scaleY = s }
            .clip(Shapes.card)
            .background(c.note(NoteColor.of(n.color)))
            .combinedClickable(
                interactionSource = source, indication = null,
                onClick = { haptics.light(); onClick() },
                onLongClick = { haptics.medium(); onLongClick() },
            )
            .padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 22.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                n.title.ifBlank { "ללא כותרת" }, style = t.noteTitle, color = c.noteInk,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (n.pinned) Icon(Ph.PushPinFill, "נעוץ", Modifier.padding(top = 4.dp).size(18.dp), tint = c.noteInk2)
        }
        val checks = blocks.filter { it.type == BlockType.Check }
        if (checks.isNotEmpty()) {
            Spacer(Modifier.size(10.dp))
            checks.take(4).forEach { b ->
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(20.dp).clip(Shapes.sm).background(if (b.done) c.noteInk else c.noteInk.copy(alpha = .1f)),
                        contentAlignment = Alignment.Center,
                    ) { if (b.done) Icon(Ph.Check, null, Modifier.size(13.dp), tint = c.note(NoteColor.of(n.color))) }
                    Spacer(Modifier.width(10.dp))
                    Text(b.text, style = t.note, color = if (b.done) c.noteInk2 else c.noteInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            val preview = blocks.firstOrNull { it.text.isNotBlank() }?.text
            if (preview != null) {
                Spacer(Modifier.size(6.dp))
                Text(preview, style = t.note, color = c.noteInk2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
