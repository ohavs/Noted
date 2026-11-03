package com.ohav.noted.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.unit.Dp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.glance.appwidget.AndroidRemoteViews
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
 * Tap the title → a dropdown of the categories; tick a row in place; tap a card → that note in the editor;
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
            val state = currentState<Preferences>()
            Main(notes, cats, settings, state[CAT], state[OPEN] == true)
        }
    }

    companion object {
        val CAT = stringPreferencesKey("cat")
        val OPEN = booleanPreferencesKey("open")
        suspend fun refresh(context: Context) = runCatching { NotedWidget().updateAll(context) }
    }
}

class NotedWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NotedWidget()
}

/** One colour, day and night (equal when the app forces light or dark). */
private class Tone(val day: Color, val night: Color) {
    val p: ColorProvider = if (day == night) ColorProvider(day) else androidx.glance.color.ColorProvider(day = day, night = night)
    fun now(context: Context): Color =
        if ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) night else day
}

/** The palette the widget draws with: fixed by the app setting, or day/night when it follows the system. */
private class WColors(s: AppSettings) {
    private val mode = s.theme
    private val accent = s.accent
    private fun pick(light: Color, dark: Color): Tone = when (mode) {
        ThemeMode.Light -> Tone(light, light)
        ThemeMode.Dark -> Tone(dark, dark)
        ThemeMode.System -> Tone(light, dark)
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

/*
 * RTL, whatever the device language. RemoteViews lay out by the launcher's locale and Glance's Text
 * pins textDirection="locale", so on an English phone everything flipped. Instead: every row sits in
 * an RTL frame ([Rtl]), all text goes through [RText] (right-aligned, direction from the content),
 * and paddings go through [padRtl] (Glance resolves start/end by the app's locale, not the frame's).
 */

@Composable
private fun Rtl(modifier: GlanceModifier = GlanceModifier, content: @Composable () -> Unit) {
    val context = LocalContext.current
    AndroidRemoteViews(RemoteViews(context.packageName, R.layout.widget_rtl), R.id.wrtl, modifier) { content() }
}

@Composable
private fun GlanceModifier.padRtl(start: Dp = 0.dp, end: Dp = 0.dp, top: Dp = 0.dp, bottom: Dp = 0.dp): GlanceModifier {
    val ctxRtl = LocalContext.current.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    return if (ctxRtl) padding(start = start, end = end, top = top, bottom = bottom)
    else padding(start = end, end = start, top = top, bottom = bottom)
}

private enum class W { Normal, Medium, Bold }

@Composable
private fun RText(
    text: String,
    color: Tone,
    size: Float,
    modifier: GlanceModifier = GlanceModifier,
    weight: W = W.Normal,
    maxLines: Int = Int.MAX_VALUE,
    strike: Boolean = false,
    center: Boolean = false,
) {
    val context = LocalContext.current
    val id = R.id.wtext
    val rv = RemoteViews(context.packageName, if (center) R.layout.widget_text_center else R.layout.widget_text).apply {
        val s = SpannableString(text)
        if (text.isNotEmpty()) {
            when (weight) {
                W.Bold -> s.setSpan(StyleSpan(Typeface.BOLD), 0, s.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                W.Medium -> s.setSpan(TypefaceSpan("sans-serif-medium"), 0, s.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                W.Normal -> Unit
            }
            if (strike) s.setSpan(StrikethroughSpan(), 0, s.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }
        setTextViewText(id, s)
        setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, size)
        setInt(id, "setMaxLines", maxLines)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && color.day != color.night) {
            setColorInt(id, "setTextColor", color.day.toArgb(), color.night.toArgb())
        } else {
            setTextColor(id, color.now(context).toArgb())
        }
    }
    AndroidRemoteViews(rv, modifier)
}

@Composable
private fun Main(notes: List<NoteEntity>, cats: List<CategoryEntity>, settings: AppSettings, chosen: String?, open: Boolean) {
    val context = LocalContext.current
    val c = remember(settings.theme, settings.accent) { WColors(settings) }
    val tree = remember(cats) { CatTree(cats) }
    val tops = tree.tops
    val cat = tops.firstOrNull { it.id == chosen } ?: tops.firstOrNull { !it.foreign } ?: tops.firstOrNull()
    val family = cat?.let { tree.family(it.id) }.orEmpty()
    val shown = notes.filter { it.categoryId in family }
    val canPick = tops.size > 1
    val menu = open && canPick

    Column(
        GlanceModifier.fillMaxSize().background(c.bg.p).cornerRadius(28.dp).padding(start = 12.dp, end = 12.dp, top = 10.dp),
    ) {
        Rtl(GlanceModifier.fillMaxWidth().padRtl(start = 4.dp, bottom = 8.dp)) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    GlanceModifier.defaultWeight().let { if (canPick) it.clickable(actionRunCallback<ToggleMenu>()) else it },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val title = remember(cat?.name, settings.fontPair) { titleBitmap(context, cat?.name ?: "noted", settings.fontPair) }
                    Image(ImageProvider(title), contentDescription = cat?.name, colorFilter = ColorFilter.tint(c.ink.p))
                    Spacer(GlanceModifier.width(8.dp))
                    Text("${shown.size}", style = TextStyle(color = c.ink3.p, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                    if (canPick) {
                        Spacer(GlanceModifier.width(2.dp))
                        Image(
                            ImageProvider(if (menu) R.drawable.wg_caret_up else R.drawable.wg_caret_down),
                            if (menu) "סגירת רשימת הקטגוריות" else "בחירת קטגוריה",
                            GlanceModifier.size(16.dp),
                            colorFilter = ColorFilter.tint(if (menu) c.ink.p else c.ink3.p),
                        )
                    }
                }
                Box(
                    GlanceModifier.size(44.dp).cornerRadius(22.dp).background(c.accentC.p)
                        .clickable(actionStartActivity(newNoteIntent(context, cat?.id))),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(ImageProvider(R.drawable.wg_plus), "פתק חדש", GlanceModifier.size(22.dp), colorFilter = ColorFilter.tint(c.accentInk.p))
                }
            }
        }

        when {
            menu -> Menu(tops, notes, tree, cat?.id, c)
            shown.isEmpty() -> Empty(c, context, cat?.id)
            else -> LazyColumn(GlanceModifier.fillMaxSize()) {
                items(shown, itemId = { it.id.hashCode().toLong() }) { n ->
                    Rtl(GlanceModifier.fillMaxWidth().padding(bottom = 8.dp)) { Card(n, c, context) }
                }
            }
        }
    }
}

/** The category dropdown: drops open under the title, in place of the stack. */
@Composable
private fun Menu(tops: List<CategoryEntity>, notes: List<NoteEntity>, tree: CatTree, current: String?, c: WColors) {
    val counts = remember(notes, tops) { tops.associate { t -> tree.family(t.id).let { f -> t.id to notes.count { it.categoryId in f } } } }
    Column(GlanceModifier.fillMaxSize().padding(bottom = 12.dp)) {
        LazyColumn(GlanceModifier.fillMaxSize().cornerRadius(22.dp).background(c.surface.p).padding(vertical = 6.dp)) {
            items(tops, itemId = { it.id.hashCode().toLong() }) { t ->
                val sel = t.id == current
                Rtl(GlanceModifier.fillMaxWidth()) {
                    Row(
                        GlanceModifier.fillMaxWidth().height(46.dp).padding(horizontal = 16.dp)
                            .clickable(actionRunCallback<SelectCategory>(actionParametersOf(CAT_P to t.id))),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RText(t.name, if (sel) c.ink else c.ink2, 15.5f, GlanceModifier.defaultWeight(), weight = if (sel) W.Bold else W.Medium, maxLines = 1)
                        Spacer(GlanceModifier.width(10.dp))
                        Text("${counts[t.id] ?: 0}", style = TextStyle(color = c.ink3.p, fontSize = 13.sp, fontWeight = FontWeight.Medium))
                        Spacer(GlanceModifier.width(10.dp))
                        Box(GlanceModifier.size(18.dp), contentAlignment = Alignment.Center) {
                            if (sel) Image(ImageProvider(R.drawable.wg_check), "נבחרה", GlanceModifier.size(18.dp), colorFilter = ColorFilter.tint(c.accentC.p))
                        }
                    }
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
        Box(GlanceModifier.size(52.dp).cornerRadius(18.dp).background(c.surface.p), contentAlignment = Alignment.Center) {
            Image(ImageProvider(R.drawable.wg_note_pencil), null, GlanceModifier.size(26.dp), colorFilter = ColorFilter.tint(c.ink2.p))
        }
        Spacer(GlanceModifier.height(10.dp))
        RText("הערימה ריקה", c.ink, 16f, GlanceModifier.fillMaxWidth(), weight = W.Bold, center = true)
        Spacer(GlanceModifier.height(2.dp))
        RText("נגיעה כאן — והפתק הראשון נפתח", c.ink2, 13f, GlanceModifier.fillMaxWidth(), center = true)
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
        GlanceModifier.fillMaxWidth().cornerRadius(22.dp).background(c.note(NoteColor.of(n.color)).p)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .clickable(actionStartActivity(openNoteIntent(context, n.id))),
    ) {
        if (n.title.isNotBlank() || n.pinned) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RText(n.title.ifBlank { "ללא כותרת" }, c.noteInk, 15f, GlanceModifier.defaultWeight(), weight = W.Bold, maxLines = 2)
                if (n.pinned) Image(ImageProvider(R.drawable.wg_push_pin_fill), "נעוץ", GlanceModifier.size(14.dp), colorFilter = ColorFilter.tint(c.noteInk2.p))
            }
        }
        n.reminderAt?.let { at ->
            Row(GlanceModifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(ImageProvider(R.drawable.wg_bell), null, GlanceModifier.size(12.dp), colorFilter = ColorFilter.tint(c.noteInk2.p))
                Spacer(GlanceModifier.width(4.dp))
                RText(fmtRel(at), c.noteInk2, 12f, GlanceModifier.defaultWeight(), weight = W.Medium, maxLines = 1)
            }
        }
        rows.forEachIndexed { i, b ->
            Spacer(GlanceModifier.height(if (i == 0 && n.title.isNotBlank()) 6.dp else 3.dp))
            Line(n, b, c, canTick)
        }
        if (rest > 0) {
            Spacer(GlanceModifier.height(4.dp))
            RText("ועוד $rest", c.noteInk2, 12f, GlanceModifier.fillMaxWidth(), weight = W.Medium, maxLines = 1)
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
            GlanceModifier.fillMaxWidth().padRtl(start = indent).let {
                if (canTick) it.clickable(actionRunCallback<ToggleCheck>(actionParametersOf(NOTE to n.id, BLOCK to b.id))) else it
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(GlanceModifier.size(20.dp), contentAlignment = Alignment.Center) {
                if (b.done) {
                    Image(ImageProvider(R.drawable.wg_box_fill), null, GlanceModifier.size(20.dp), colorFilter = ColorFilter.tint(c.noteInk.p))
                    Image(ImageProvider(R.drawable.wg_tick), null, GlanceModifier.size(20.dp), colorFilter = ColorFilter.tint(c.note(NoteColor.of(n.color)).p))
                } else {
                    Image(ImageProvider(R.drawable.wg_box), "לסמן", GlanceModifier.size(20.dp), colorFilter = ColorFilter.tint(c.boxOff.p))
                }
            }
            Spacer(GlanceModifier.width(9.dp))
            RText(
                text, if (b.done) c.noteInk2 else c.noteInk, 14f,
                GlanceModifier.defaultWeight().padding(vertical = 2.dp),
                maxLines = 2, strike = b.done,
            )
        }
        BlockType.Images, BlockType.Voice, BlockType.Draw, BlockType.Link -> RText(
            when (b.type) { BlockType.Images -> "תמונה"; BlockType.Voice -> "הקלטה"; BlockType.Draw -> "ציור"; else -> "קישור" },
            c.noteInk2, 13f, GlanceModifier.fillMaxWidth().padRtl(start = indent), weight = W.Medium, maxLines = 1,
        )
        else -> {
            val h = b.type == BlockType.H1 || b.type == BlockType.H2 || b.type == BlockType.H3
            val lead = when (b.type) { BlockType.Bullet -> "•  "; BlockType.Num -> "–  "; else -> "" }
            RText(
                lead + text,
                if (b.type == BlockType.Quote) c.noteInk2 else c.noteInk,
                if (h) 14.5f else 13.5f,
                GlanceModifier.fillMaxWidth().padRtl(start = indent),
                weight = if (h) W.Bold else W.Normal,
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

private val CAT_P = ActionParameters.Key<String>("cat")

/** Title tap: drops the category list open (or closes it). */
class ToggleMenu : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        updateAppWidgetState(context, glanceId) { p -> p[NotedWidget.OPEN] = p[NotedWidget.OPEN] != true }
        NotedWidget().update(context, glanceId)
    }
}

/** A row in the list: show that category and fold the list away. */
class SelectCategory : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[CAT_P] ?: return
        updateAppWidgetState(context, glanceId) { p ->
            p[NotedWidget.CAT] = id
            p[NotedWidget.OPEN] = false
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
