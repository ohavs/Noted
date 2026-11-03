package com.ohav.noted.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.ohav.noted.MainActivity
import com.ohav.noted.NotedApp
import com.ohav.noted.R
import com.ohav.noted.data.Block
import com.ohav.noted.data.BlockType
import com.ohav.noted.data.Blocks
import com.ohav.noted.data.CatTree
import com.ohav.noted.data.CategoryEntity
import com.ohav.noted.data.NoteEntity
import com.ohav.noted.data.Share
import com.ohav.noted.data.foreign
import com.ohav.noted.design.FontPair
import com.ohav.noted.design.NoteColor
import com.ohav.noted.design.ThemeMode
import com.ohav.noted.reminders.Notifier
import com.ohav.noted.settings.AppSettings
import com.ohav.noted.ui.home.fmtRel
import kotlinx.coroutines.flow.first

/**
 * The big home-screen widget: one category's stack, as cards in the note colours.
 * Tap the title → next category; tick a row in place; tap a card → that note in the editor;
 * + → a new note in this category. Light/dark and accent follow the app's settings.
 */
class NotedWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as NotedApp
        val notes0 = app.notes.notes.first()
        val cats0 = app.notes.categories.first()
        val set0 = app.settings.settings.first()
        provideContent {
            val notes by app.notes.notes.collectAsState(notes0)
            val cats by app.notes.categories.collectAsState(cats0)
            val settings by app.settings.settings.collectAsState(set0)
            val chosen = currentState<Preferences>()[CAT]
            Main(notes, cats, settings, chosen)
        }
    }

    companion object {
        val CAT = stringPreferencesKey("cat")
        suspend fun refresh(context: Context) = runCatching { NotedWidget().updateAll(context) }
    }
}

class NotedWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NotedWidget()
}

/** The palette the widget draws with: fixed by the app setting, or day/night when it follows the system. */
private class WColors(s: AppSettings) {
    private val mode = s.theme
    private val accent = s.accent
    private fun pick(light: Color, dark: Color): ColorProvider = when (mode) {
        ThemeMode.Light -> ColorProvider(light)
        ThemeMode.Dark -> ColorProvider(dark)
        ThemeMode.System -> androidx.glance.color.ColorProvider(day = light, night = dark)
    }
    val bg = pick(Color(0xFFF5F1EA), Color(0xFF12110F))
    val ink = pick(Color(0xFF1B1915), Color(0xFFF3EEE6))
    val ink2 = pick(Color(0xFF6B655C), Color(0xFFA59E93))
    val ink3 = pick(Color(0xFFA69F94), Color(0xFF6A645B))
    val surface = pick(Color(0xFFFFFDF9), Color(0xFF1F1D1A))
    val noteInk = pick(Color(0xFF1D1A15), Color(0xFFF4EFE7))
    val noteInk2 = pick(Color(0x9E1D1A15), Color(0x99F4EFE7))
    val boxOff = pick(Color(0x731D1A15), Color(0x73F4EFE7))
    val accentC = pick(accent.light, accent.dark)
    val accentInk = pick(Color.White, accent.darkInk)
    fun note(c: NoteColor) = pick(c.light, c.dark)
}

private const val MAX_ROWS = 5

@Composable
private fun Main(notes: List<NoteEntity>, cats: List<CategoryEntity>, settings: AppSettings, chosen: String?) {
    val context = LocalContext.current
    val c = remember(settings.theme, settings.accent) { WColors(settings) }
    val tree = remember(cats) { CatTree(cats) }
    val tops = tree.tops
    val cat = tops.firstOrNull { it.id == chosen } ?: tops.firstOrNull { !it.foreign } ?: tops.firstOrNull()
    val family = cat?.let { tree.family(it.id) }.orEmpty()
    val shown = notes.filter { it.categoryId in family }

    Column(
        GlanceModifier.fillMaxSize().background(c.bg).cornerRadius(28.dp).padding(start = 12.dp, end = 12.dp, top = 10.dp),
    ) {
        Row(GlanceModifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                GlanceModifier.defaultWeight().clickable(actionRunCallback<NextCategory>()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val title = remember(cat?.name, settings.fontPair) { titleBitmap(context, cat?.name ?: "noted", settings.fontPair) }
                Image(ImageProvider(title), contentDescription = cat?.name, colorFilter = ColorFilter.tint(c.ink))
                Spacer(GlanceModifier.width(8.dp))
                Text("${shown.size}", style = TextStyle(color = c.ink3, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                if (tops.size > 1) {
                    Spacer(GlanceModifier.width(2.dp))
                    Image(ImageProvider(R.drawable.wg_caret_down), "הקטגוריה הבאה", GlanceModifier.size(16.dp), colorFilter = ColorFilter.tint(c.ink3))
                }
            }
            Box(
                GlanceModifier.size(44.dp).cornerRadius(22.dp).background(c.accentC)
                    .clickable(actionStartActivity(newNoteIntent(context, cat?.id))),
                contentAlignment = Alignment.Center,
            ) {
                Image(ImageProvider(R.drawable.wg_plus), "פתק חדש", GlanceModifier.size(22.dp), colorFilter = ColorFilter.tint(c.accentInk))
            }
        }

        if (shown.isEmpty()) {
            Empty(c, context, cat?.id)
        } else {
            LazyColumn(GlanceModifier.fillMaxSize()) {
                items(shown, itemId = { it.id.hashCode().toLong() }) { n ->
                    Column(GlanceModifier.fillMaxWidth().padding(bottom = 8.dp)) { Card(n, c, context) }
                }
            }
        }
    }
}

@Composable
private fun Empty(c: WColors, context: Context, cat: String?) {
    Column(
        GlanceModifier.fillMaxSize().padding(bottom = 16.dp).clickable(actionStartActivity(newNoteIntent(context, cat))),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(GlanceModifier.size(52.dp).cornerRadius(18.dp).background(c.surface), contentAlignment = Alignment.Center) {
            Image(ImageProvider(R.drawable.wg_note_pencil), null, GlanceModifier.size(26.dp), colorFilter = ColorFilter.tint(c.ink2))
        }
        Spacer(GlanceModifier.height(10.dp))
        Text("הערימה ריקה", style = TextStyle(color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold))
        Spacer(GlanceModifier.height(2.dp))
        Text("נגיעה כאן — והפתק הראשון נפתח", style = TextStyle(color = c.ink2, fontSize = 13.sp))
    }
}

@Composable
private fun Card(n: NoteEntity, c: WColors, context: Context) {
    val blocks = remember(n.blocks) { Blocks.parse(n.blocks) }
    val canTick = remember(n.share) { Share.parse(n.share).role != Share.ROLE_VIEWER }
    val lines = blocks.filter { it.type != BlockType.Divider && (it.type !in TEXT || clean(it.text).isNotBlank()) }
    val rows = lines.take(MAX_ROWS)
    val rest = lines.size - rows.size
    Column(
        GlanceModifier.fillMaxWidth().cornerRadius(22.dp).background(c.note(NoteColor.of(n.color)))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .clickable(actionStartActivity(openNoteIntent(context, n.id))),
    ) {
        if (n.title.isNotBlank() || n.pinned) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    n.title.ifBlank { "ללא כותרת" },
                    GlanceModifier.defaultWeight(),
                    style = TextStyle(color = c.noteInk, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                    maxLines = 2,
                )
                if (n.pinned) Image(ImageProvider(R.drawable.wg_push_pin_fill), "נעוץ", GlanceModifier.size(14.dp), colorFilter = ColorFilter.tint(c.noteInk2))
            }
        }
        n.reminderAt?.let { at ->
            Row(GlanceModifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(ImageProvider(R.drawable.wg_bell), null, GlanceModifier.size(12.dp), colorFilter = ColorFilter.tint(c.noteInk2))
                Spacer(GlanceModifier.width(4.dp))
                Text(fmtRel(at), style = TextStyle(color = c.noteInk2, fontSize = 12.sp, fontWeight = FontWeight.Medium))
            }
        }
        rows.forEachIndexed { i, b ->
            Spacer(GlanceModifier.height(if (i == 0 && n.title.isNotBlank()) 6.dp else 3.dp))
            Line(n, b, c, canTick)
        }
        if (rest > 0) {
            Spacer(GlanceModifier.height(4.dp))
            Text("ועוד $rest", style = TextStyle(color = c.noteInk2, fontSize = 12.sp, fontWeight = FontWeight.Medium))
        }
    }
}

private val TEXT = setOf(BlockType.P, BlockType.H1, BlockType.H2, BlockType.H3, BlockType.Quote, BlockType.Bullet, BlockType.Num, BlockType.Check)

private fun clean(s: String) = s.replace("​", "").trim()

@Composable
private fun Line(n: NoteEntity, b: Block, c: WColors, canTick: Boolean) {
    val text = clean(b.text)
    val indent = (b.indent.coerceIn(0, 3) * 12).dp
    when (b.type) {
        BlockType.Check -> Row(
            GlanceModifier.fillMaxWidth().padding(start = indent).let {
                if (canTick) it.clickable(actionRunCallback<ToggleCheck>(actionParametersOf(NOTE to n.id, BLOCK to b.id))) else it
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(GlanceModifier.size(20.dp), contentAlignment = Alignment.Center) {
                if (b.done) {
                    Image(ImageProvider(R.drawable.wg_box_fill), null, GlanceModifier.size(20.dp), colorFilter = ColorFilter.tint(c.noteInk))
                    Image(ImageProvider(R.drawable.wg_tick), null, GlanceModifier.size(20.dp), colorFilter = ColorFilter.tint(c.note(NoteColor.of(n.color))))
                } else {
                    Image(ImageProvider(R.drawable.wg_box), if (b.done) "בוצע" else "לסמן", GlanceModifier.size(20.dp), colorFilter = ColorFilter.tint(c.boxOff))
                }
            }
            Spacer(GlanceModifier.width(9.dp))
            Text(
                text,
                GlanceModifier.defaultWeight().padding(vertical = 2.dp),
                style = TextStyle(
                    color = if (b.done) c.noteInk2 else c.noteInk, fontSize = 14.sp,
                    textDecoration = if (b.done) TextDecoration.LineThrough else TextDecoration.None,
                ),
                maxLines = 2,
            )
        }
        BlockType.Images, BlockType.Voice, BlockType.Draw, BlockType.Link -> Text(
            when (b.type) { BlockType.Images -> "תמונה"; BlockType.Voice -> "הקלטה"; BlockType.Draw -> "ציור"; else -> "קישור" },
            GlanceModifier.padding(start = indent),
            style = TextStyle(color = c.noteInk2, fontSize = 13.sp, fontWeight = FontWeight.Medium),
        )
        else -> {
            val h = b.type == BlockType.H1 || b.type == BlockType.H2 || b.type == BlockType.H3
            val lead = when (b.type) { BlockType.Bullet -> "•  "; BlockType.Num -> "–  "; else -> "" }
            Text(
                lead + text,
                GlanceModifier.fillMaxWidth().padding(start = indent),
                style = TextStyle(
                    color = if (b.type == BlockType.Quote) c.noteInk2 else c.noteInk,
                    fontSize = if (h) 14.5.sp else 13.5.sp,
                    fontWeight = if (h) FontWeight.Bold else FontWeight.Normal,
                ),
                maxLines = if (h) 1 else 3,
            )
        }
    }
}

private val NOTE = ActionParameters.Key<String>("note")
private val BLOCK = ActionParameters.Key<String>("block")

private fun openNoteIntent(context: Context, id: String) = Intent(context, MainActivity::class.java)
    .setData(Uri.parse("noted-widget://note/$id"))
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    .putExtra(Notifier.EXTRA_NOTE, id)

private fun newNoteIntent(context: Context, cat: String?) = Intent(context, MainActivity::class.java)
    .setData(Uri.parse("noted-widget://new/${cat.orEmpty()}"))
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    .putExtra(MainActivity.EXTRA_NEW_IN, cat.orEmpty())

/** Ticks one checklist row from the home screen. */
class ToggleCheck : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val note = parameters[NOTE] ?: return
        val block = parameters[BLOCK] ?: return
        (context.applicationContext as NotedApp).notes.toggleCheck(note, block)
        NotedWidget().update(context, glanceId)
    }
}

/** Title tap: the next top-level category (wraps around). */
class NextCategory : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val tops = CatTree((context.applicationContext as NotedApp).notes.categoriesNow()).tops
        if (tops.size < 2) return
        updateAppWidgetState(context, glanceId) { p ->
            val cur = tops.indexOfFirst { it.id == p[NotedWidget.CAT] }.let { if (it < 0) tops.indexOfFirst { t -> !t.foreign }.coerceAtLeast(0) else it }
            p[NotedWidget.CAT] = tops[(cur + 1) % tops.size].id
        }
        NotedWidget().update(context, glanceId)
    }
}

/**
 * The category name in the app's display font. RemoteViews can't take a custom typeface, so the
 * title is drawn into a white-on-clear bitmap and tinted with the ink colour (day/night safe).
 */
private fun titleBitmap(context: Context, text: String, pair: FontPair): Bitmap {
    val dm = context.resources.displayMetrics
    val (res, wght) = when (pair) {
        FontPair.Soft -> R.font.fredoka to 600
        FontPair.Editorial -> R.font.frank_ruhl_libre to 800
        FontPair.Condensed -> R.font.karantina_bold to 0
    }
    val tf = runCatching { ResourcesCompat.getFont(context, res) }.getOrNull() ?: Typeface.DEFAULT_BOLD
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = tf
        color = Color.White.toArgb()
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 26f * pair.scale, dm)
        if (wght > 0) fontVariationSettings = "'wght' $wght"
        letterSpacing = pair.trackingEm
    }
    val maxW = 210f * dm.density
    val s = TextUtils.ellipsize(text, paint, maxW, TextUtils.TruncateAt.END).toString().ifEmpty { text.take(1) }
    val fm = paint.fontMetrics
    val w = paint.measureText(s).coerceAtLeast(1f)
    val h = fm.descent - fm.ascent
    val bmp = Bitmap.createBitmap(kotlin.math.ceil(w + 2).toInt(), kotlin.math.ceil(h).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    bmp.density = dm.densityDpi
    Canvas(bmp).drawText(s, 1f, -fm.ascent, paint)
    return bmp
}
