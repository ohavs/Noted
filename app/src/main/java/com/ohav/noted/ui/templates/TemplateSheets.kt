package com.ohav.noted.ui.templates

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ohav.noted.NotedApp
import com.ohav.noted.data.Block
import com.ohav.noted.data.BlockType
import com.ohav.noted.data.Template
import com.ohav.noted.data.Templates
import com.ohav.noted.design.Btn
import com.ohav.noted.design.BtnKind
import com.ohav.noted.design.Motion
import com.ohav.noted.design.NoteColor
import com.ohav.noted.design.Noted
import com.ohav.noted.design.Overlays
import com.ohav.noted.design.Ph
import com.ohav.noted.design.SheetRow
import com.ohav.noted.design.Shapes
import com.ohav.noted.design.ToastState
import com.ohav.noted.design.VSpace
import com.ohav.noted.design.press
import com.ohav.noted.design.pressLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val NAME_MAX = 28

/**
 * Long press on + : a new note from a template. Mine first (long press = rename / delete),
 * then the ones that ship with the app.
 */
fun openTemplatePicker(app: NotedApp, overlays: Overlays, toasts: ToastState, onPick: (Template) -> Unit) {
    overlays.sheet.show("פתק מתבנית") {
        val mine by app.templates.mine.collectAsStateWithLifecycle(initialValue = emptyList())
        val builtins = remember { Templates.builtins() }
        fun pick(t: Template) {
            overlays.sheet.close()
            app.scope.launch(kotlinx.coroutines.Dispatchers.Main) { delay(140); onPick(t) }
        }
        if (mine.isNotEmpty()) {
            Label("שלי")
            Grid(mine, onLong = { t -> swap(app, overlays) { manageSheet(app, overlays, toasts, t) } }, onClick = ::pick)
            VSpace(14.dp)
        }
        Label("מוכנות")
        Grid(builtins, onLong = null, onClick = ::pick)
        if (mine.isEmpty()) {
            VSpace(14.dp)
            Hint("כל פתק יכול להפוך לתבנית: בפתק ⋮ ← ״שמירה כתבנית״.")
        }
        VSpace(6.dp)
    }
}

/** Settings → ארגון → תבניות: my templates; a tap opens what can be done with one. */
fun openTemplateList(app: NotedApp, overlays: Overlays, toasts: ToastState) {
    overlays.sheet.show("התבניות שלי") {
        val mine by app.templates.mine.collectAsStateWithLifecycle(initialValue = null)
        val list = mine
        when {
            list == null -> Unit
            list.isEmpty() -> {
                Column(
                    Modifier.fillMaxWidth().clip(Shapes.lg).background(Noted.colors.bg).padding(horizontal = 20.dp, vertical = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(64.dp).clip(Shapes.md).background(Noted.colors.accentSoft), contentAlignment = Alignment.Center) {
                        Icon(Ph.Cards, null, Modifier.size(30.dp), tint = Noted.colors.accent)
                    }
                    VSpace(14.dp)
                    Text("עוד אין תבניות משלך", style = Noted.type.emptyTitle, color = Noted.colors.ink)
                    VSpace(6.dp)
                    Text(
                        "בפתק: ⋮ ← ״שמירה כתבנית״. לחיצה ארוכה על + פותחת פתק מתבנית.",
                        style = Noted.type.body, color = Noted.colors.ink2,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                VSpace(8.dp)
            }
            else -> {
                Grid(list, onLong = null) { t -> swap(app, overlays) { manageSheet(app, overlays, toasts, t) } }
                VSpace(14.dp)
                Hint("לחיצה ארוכה על + בבית פותחת פתק מתבנית.")
                VSpace(6.dp)
            }
        }
    }
}

/**
 * ⋮ → שמירה כתבנית: the note's shape (title, rows without media, every box clear, colour,
 * category) under a name of my choosing.
 */
fun openSaveTemplate(
    app: NotedApp, overlays: Overlays, toasts: ToastState,
    title: String, blocks: List<Block>, color: NoteColor, categoryId: String,
) {
    val rows = Templates.shape(blocks)
    val clean = title.replace("​", "").trim()
    if (clean.isEmpty() && rows.all { it.text.isBlank() && it.type != BlockType.Divider }) {
        toasts.show("אין עדיין מה לשמור — כתבו משהו קודם", Ph.Info)
        return
    }
    overlays.sheet.show("שמירה כתבנית") {
        val haptics = Noted.haptics
        var name by remember { mutableStateOf(clean.take(NAME_MAX)) }
        val ok = name.isNotBlank()
        fun save() {
            if (!ok) { haptics.reject(); return }
            haptics.confirm()
            overlays.sheet.close()
            app.scope.launch {
                val t = app.templates.saveFrom(name, clean, blocks, color, categoryId)
                toasts.show("נשמרה תבנית ״${t.name}״", Ph.Cards, action = "ביטול") {
                    app.scope.launch { app.templates.setDeleted(t.id, true) }
                }
            }
        }
        val preview = Template("", name.ifBlank { "תבנית" }, clean, rows, color, categoryId, Ph.Cards, false)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            TemplateCard(preview, Modifier.width(150.dp))
            Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Point(Ph.Check, "השורות והכותרות נשמרות")
                Point(Ph.CheckSquare, "תיבות הסימון חוזרות ריקות")
                Point(Ph.Image, "בלי תמונות, הקלטות וציורים")
            }
        }
        VSpace(18.dp)
        NameField(name, placeholder = "שם התבנית", onChange = { name = it }, onDone = ::save)
        VSpace(18.dp)
        val a by animateFloatAsState(if (ok) 1f else .4f, Motion.glide(), label = "tplOk")
        Btn("שמירה כתבנית", Modifier.fillMaxWidth().graphicsLayer { alpha = a }, icon = Ph.Cards, kind = BtnKind.Dark) { save() }
        VSpace(8.dp)
    }
}

// ------------------------------------------------------------------ one of mine

private fun manageSheet(app: NotedApp, overlays: Overlays, toasts: ToastState, t: Template) {
    overlays.sheet.show(t.name) {
        TemplateCard(t, Modifier.fillMaxWidth(), rows = 7)
        VSpace(10.dp)
        SheetRow(Ph.PencilSimple, "שינוי שם") { swap(app, overlays) { renameSheet(app, overlays, toasts, t) } }
        SheetRow(Ph.Trash, "מחיקת התבנית", danger = true, last = true) {
            overlays.sheet.close()
            app.scope.launch {
                app.templates.setDeleted(t.id, true)
                toasts.show("התבנית ״${t.name}״ נמחקה", Ph.Trash, action = "ביטול") {
                    app.scope.launch { app.templates.setDeleted(t.id, false) }
                }
            }
        }
    }
}

private fun renameSheet(app: NotedApp, overlays: Overlays, toasts: ToastState, t: Template) {
    overlays.sheet.show("שם התבנית") {
        val haptics = Noted.haptics
        var name by remember { mutableStateOf(t.name) }
        val ok = name.isNotBlank()
        fun save() {
            if (!ok) { haptics.reject(); return }
            haptics.confirm()
            overlays.sheet.close()
            if (name.trim() == t.name) return
            app.scope.launch {
                app.templates.rename(t.id, name)
                toasts.show("השם עודכן", Ph.CheckCircleFill, millis = 1600)
            }
        }
        NameField(name, placeholder = "שם התבנית", onChange = { name = it }, onDone = ::save)
        VSpace(18.dp)
        val a by animateFloatAsState(if (ok) 1f else .4f, Motion.glide(), label = "tplRename")
        Btn("שמירה", Modifier.fillMaxWidth().graphicsLayer { alpha = a }, icon = Ph.Check, kind = BtnKind.Dark) { save() }
        VSpace(8.dp)
    }
}

/** One sheet makes way for the next: it slides down, then the next one rises. */
private fun swap(app: NotedApp, overlays: Overlays, next: () -> Unit) {
    overlays.sheet.close()
    app.scope.launch(kotlinx.coroutines.Dispatchers.Main) { delay(180); next() }
}

// ------------------------------------------------------------------ pieces

@Composable
private fun Label(text: String) =
    Text(text, style = Noted.type.section, color = Noted.colors.ink2, modifier = Modifier.padding(bottom = 10.dp))

@Composable
private fun Hint(text: String) {
    val c = Noted.colors
    Row(
        Modifier.fillMaxWidth().clip(Shapes.md).background(c.bg).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Ph.Lightbulb, null, Modifier.size(18.dp), tint = c.ink3)
        Text(text, style = Noted.type.caption, color = c.ink2, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Point(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    val c = Noted.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(17.dp), tint = c.ink3)
        Text(text, style = Noted.type.caption, color = c.ink2)
    }
}

@Composable
private fun Grid(items: List<Template>, onLong: ((Template) -> Unit)?, onClick: (Template) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { t ->
                    val m = Modifier.weight(1f).let { base ->
                        if (onLong != null) base.pressLong(scale = .95f, onLongClick = { onLong(t) }) { onClick(t) }
                        else base.press(scale = .95f) { onClick(t) }
                    }
                    TemplateCard(t, m)
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * A template as a small sheet of its paper: its icon and name, then its rows drawn in
 * miniature — headings in bold, boxes, numbers and dots, empty lines as soft bars.
 */
@Composable
fun TemplateCard(t: Template, modifier: Modifier = Modifier, rows: Int = 5) {
    val c = Noted.colors
    val ty = Noted.type
    val paper = c.note(t.color)
    Column(modifier.clip(Shapes.lg).background(paper).padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(c.noteInk.copy(alpha = .08f)), contentAlignment = Alignment.Center) {
                Icon(t.icon, null, Modifier.size(17.dp), tint = c.noteInk)
            }
            Text(t.name, style = ty.barTitle, color = c.noteInk, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        VSpace(10.dp)
        Box(Modifier.fillMaxWidth().height((rows * 17).dp).clipToBounds()) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                var num = 0
                t.blocks.take(rows + 1).forEachIndexed { i, b ->
                    num = if (b.type == BlockType.Num) num + 1 else 0
                    MiniRow(b, num, i)
                }
            }
            // the paper takes over toward the bottom: more rows below, without a hard cut
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(22.dp)
                    .background(Brush.verticalGradient(listOf(paper.copy(alpha = 0f), paper))),
            )
        }
    }
}

@Composable
private fun MiniRow(b: Block, num: Int, i: Int) {
    val c = Noted.colors
    val ty = Noted.type
    val ink = c.noteInk
    val text = b.text.replace("​", "").trim()
    @Composable
    fun Line(start: androidx.compose.ui.unit.Dp = 0.dp) {
        if (text.isNotEmpty()) {
            Text(text, style = ty.tiny.copy(fontWeight = FontWeight.Medium), color = c.noteInk2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            // empty rows still show their place: a bar of varying length
            val w = listOf(.62f, .44f, .54f, .36f)[i % 4]
            Box(Modifier.padding(start = start).fillMaxWidth(w).height(5.dp).clip(RoundedCornerShape(3.dp)).background(ink.copy(alpha = .13f)))
        }
    }
    Row(Modifier.fillMaxWidth().height(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        when (b.type) {
            BlockType.H1, BlockType.H2, BlockType.H3 ->
                Text(text.ifEmpty { "—" }, style = ty.tiny.copy(fontWeight = FontWeight.ExtraBold), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BlockType.Check -> {
                Box(
                    Modifier.size(9.dp).drawBehind {
                        drawRoundRect(
                            ink.copy(alpha = .55f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5.dp.toPx()),
                            style = Stroke(1.4.dp.toPx()),
                        )
                    },
                )
                Line()
            }
            BlockType.Num -> {
                Text("$num.", style = ty.tiny, color = ink.copy(alpha = .6f))
                Line()
            }
            BlockType.Bullet -> {
                Box(Modifier.size(4.dp).clip(CircleShape).background(ink.copy(alpha = .55f)))
                Line()
            }
            BlockType.Divider -> Box(Modifier.fillMaxWidth().height(1.dp).background(ink.copy(alpha = .18f)))
            BlockType.Quote -> {
                Box(Modifier.width(2.dp).height(11.dp).background(ink.copy(alpha = .4f)))
                Line()
            }
            else -> Line()
        }
    }
}

@Composable
private fun NameField(value: String, placeholder: String, onChange: (String) -> Unit, onDone: () -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(280); runCatching { focus.requestFocus() } }
    Box(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(Shapes.md)
            .background(c.bg)
            .drawBehind {
                val o = Shapes.md.createOutline(size, layoutDirection, this)
                drawOutline(o, c.line, style = Stroke(3.dp.toPx()))
            }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(placeholder, style = t.field.copy(fontWeight = FontWeight.SemiBold), color = c.ink3)
        BasicTextField(
            value = value, onValueChange = { if (it.length <= NAME_MAX) onChange(it) }, singleLine = true,
            textStyle = t.field.copy(color = c.ink, fontWeight = FontWeight.SemiBold),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
}
