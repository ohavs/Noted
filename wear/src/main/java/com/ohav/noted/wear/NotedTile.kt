package com.ohav.noted.wear

import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import com.ohav.noted.wear.ui.whenText

/**
 * The tile, as the wearer chose in settings ([WView.tile]): pinned notes (the newest when nothing is
 * pinned), the newest, one category, one note (its open rows), or the coming reminders — as paper
 * strips with their checklist progress, and the two quick ways in — dictate a note / record one.
 * Under the first note with open checklist rows, the next ones can be ticked right here (the
 * tile reloads, the row goes, the next comes up). Everything else opens the app; the tile only
 * reads the stored state, so it draws at once.
 */
class NotedTile : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { c ->
            // a row ticked on the tile: "t:<note>|<line>" (checked here at once, sent to the phone)
            requestParams.currentState.lastClickableId.takeIf { it.startsWith("t:") }?.removePrefix("t:")?.split('|', limit = 2)
                ?.takeIf { it.size == 2 }?.let { (n, l) -> store.toggle(n, l, true) }
            c.set(tile(requestParams.deviceConfiguration.screenWidthDp.toFloat()))
            "tile"
        }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { c ->
            c.set(
                ResourceBuilders.Resources.Builder().setVersion(RES)
                    .addIdToImageMapping("mic", image(R.drawable.tl_mic))
                    .addIdToImageMapping("wave", image(R.drawable.tl_wave))
                    .addIdToImageMapping("pin", image(R.drawable.tl_pin))
                    .addIdToImageMapping("lock", image(R.drawable.tl_lock))
                    .build(),
            )
            "res"
        }

    private fun image(id: Int) = ResourceBuilders.ImageResource.Builder()
        .setAndroidResourceByResId(ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(id).build())
        .build()

    private fun tile(width: Float): TileBuilders.Tile {
        val s = store.state.value
        val v = store.view.value
        val accent = Palette.accent(s?.accent)
        val wide = width >= 210
        val col = LayoutElementBuilders.Column.Builder()
            .setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        fun head(t: String) { col.addContent(text(t, 12f, Palette.INK2)).addContent(space(6f)) }
        fun note(t: String) { col.addContent(text(t, 14f, Palette.INK, lines = 2)) }
        val one = v.tile.removePrefix("note:").takeIf { v.tile.startsWith("note:") }?.let { s?.note(it) }
        val cat = v.tile.removePrefix("cat:").takeIf { v.tile.startsWith("cat:") }?.let { s?.cat(it) }
        when {
            s == null -> { head("noted"); note("פתחו את noted בטלפון") }
            v.tile == WView.TILE_REMS -> {
                head("בקרוב")
                val rems = v.rems(s).take(if (wide) 3 else 2)
                if (rems.isEmpty()) note("אין תזכורות קרובות")
                rems.forEachIndexed { i, r ->
                    if (i > 0) col.addContent(space(5f))
                    col.addContent(chip(r.title, whenText(r.at, r.due), Palette.SURFACE, Palette.INK, open("do", "rem", "r:" + r.id), width))
                }
            }
            one != null -> {
                // one note on the wrist: its open rows, more of them, tickable here
                head(s.cat(one.cat)?.name ?: "פתק")
                col.addContent(strip(one, width))
                val readable = !one.locked && !one.blurBody
                val open = if (readable && !one.readOnly) one.checks.filter { !it.done && it.text.isNotBlank() }.take(if (wide) 3 else 2) else emptyList()
                open.forEach { l -> col.addContent(space(3f)); col.addContent(checkRow(one, l, width)) }
                if (readable && open.isEmpty()) {
                    val total = one.progress.second
                    val peek = if (total > 0) "הכל סומן" else one.lines.firstOrNull { !it.isCheck && it.text.isNotBlank() && it.kind in TEXTY }?.text?.trim()
                    if (peek != null) { col.addContent(space(5f)); col.addContent(text(peek, 13f, Palette.INK2, lines = 2)) }
                }
            }
            else -> {
                val (title, list) = when {
                    cat != null -> cat.name to s.notes.filter { s.top(it.cat) == cat.id }.sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.at })
                    v.tile == WView.TILE_RECENT -> "אחרונים" to v.notes(s).sortedByDescending { it.at }
                    else -> v.notes(s).let { all -> all.filter { it.pinned }.takeIf { it.isNotEmpty() }?.let { "נעוצים" to it } ?: ("אחרונים" to all.sortedByDescending { it.at }) }
                }
                head(title)
                // the first of them with something left to tick; the strips make room for its rows
                val focus = list.take(3).firstOrNull { n -> !n.locked && !n.blurBody && !n.readOnly && n.checks.any { !it.done && it.text.isNotBlank() } }
                val rows = focus?.checks?.filter { !it.done && it.text.isNotBlank() }?.take(if (wide) 2 else 1).orEmpty()
                val notes = if (focus == null) list.take(if (wide) 3 else 2) else (listOf(focus) + list.filter { it.id != focus.id }).take(if (wide) 2 else 1)
                if (notes.isEmpty()) note("אין פתקים עדיין")
                notes.forEachIndexed { i, n ->
                    if (i > 0) col.addContent(space(5f))
                    col.addContent(strip(n, width))
                    if (n.id == focus?.id) rows.forEach { l ->
                        col.addContent(space(3f))
                        col.addContent(checkRow(n, l, width))
                    }
                }
            }
        }
        col.addContent(space(10f))
            .addContent(
                LayoutElementBuilders.Row.Builder()
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .addContent(circle("wave", 42f, Palette.SURFACE2, Palette.INK, open("do", "record", "rec")))
                    .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(12f)).build())
                    .addContent(circle("mic", 50f, accent, Palette.onAccent(s?.accent), open("do", "dictate", "mic")))
                    .build(),
            )
        val root = LayoutElementBuilders.Box.Builder()
            .setWidth(expand()).setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(col.build())
            .build()
        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RES)
            .setFreshnessIntervalMillis(0)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
            .build()
    }

    /** One note as a paper strip: title · progress, tap = open it. */
    private fun strip(n: Note, width: Float): LayoutElement {
        val (done, total) = n.progress
        val title = when {
            n.locked || n.blurTitle -> "פתק נעול".takeIf { n.locked } ?: "פתק מוסתר"
            else -> n.title.ifBlank { n.lines.firstOrNull { it.text.isNotBlank() }?.text ?: "פתק בלי כותרת" }
        }
        val label = if (total > 0 && !n.locked && !n.blurBody) "$title · $done/$total" else title
        return LayoutElementBuilders.Box.Builder()
            .setWidth(dp(width * .72f)).setHeight(dp(34f))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder().setColor(argb(Palette.note(n.color)))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(13f)).build()).build(),
                    )
                    .setPadding(ModifiersBuilders.Padding.Builder().setStart(dp(12f)).setEnd(dp(12f)).build())
                    .setClickable(open("open", n.id, "n:" + n.id))
                    .build(),
            )
            .addContent(text(label, 14f, Palette.NOTE_INK, bold = true))
            .build()
    }

    /** An open checklist row: an empty box in the note's colour and the text; tap = ticked. */
    private fun checkRow(n: Note, l: Line, width: Float): LayoutElement {
        val box = LayoutElementBuilders.Box.Builder()
            .setWidth(dp(16f)).setHeight(dp(16f))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBorder(ModifiersBuilders.Border.Builder().setWidth(dp(2f)).setColor(argb(Palette.note(n.color))).build())
                    .setBackground(ModifiersBuilders.Background.Builder().setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(5f)).build()).build())
                    .build(),
            )
            .build()
        return LayoutElementBuilders.Box.Builder()
            .setWidth(dp(width * .72f)).setHeight(dp(30f))
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder().setColor(argb(Palette.SURFACE))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(12f)).build()).build(),
                    )
                    .setPadding(ModifiersBuilders.Padding.Builder().setStart(dp(10f)).setEnd(dp(10f)).setRtlAware(true).build())
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder().setId("t:${n.id}|${l.id}")
                            .setOnClick(ActionBuilders.LoadAction.Builder().build()).build(),
                    )
                    .build(),
            )
            .addContent(
                LayoutElementBuilders.Row.Builder()
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .addContent(box)
                    .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(8f)).build())
                    .addContent(text(l.text.trim(), 13f, Palette.INK))
                    .build(),
            )
            .build()
    }

    /** A reminder on the tile: what, and when under it. */
    private fun chip(title: String, sub: String, bg: Int, ink: Int, click: ModifiersBuilders.Clickable, width: Float): LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setWidth(dp(width * .72f))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder().setColor(argb(bg))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(13f)).build()).build(),
                    )
                    .setPadding(ModifiersBuilders.Padding.Builder().setStart(dp(12f)).setEnd(dp(12f)).setTop(dp(5f)).setBottom(dp(5f)).build())
                    .setClickable(click)
                    .build(),
            )
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .addContent(text(title, 13f, ink, bold = true))
                    .addContent(text(sub, 11f, Palette.INK2))
                    .build(),
            )
            .build()

    private fun circle(icon: String, size: Float, bg: Int, tint: Int, click: ModifiersBuilders.Clickable): LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setWidth(dp(size)).setHeight(dp(size))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder().setColor(argb(bg))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(size / 2)).build()).build(),
                    )
                    .setClickable(click)
                    .build(),
            )
            .addContent(
                LayoutElementBuilders.Image.Builder().setResourceId(icon).setWidth(dp(size * .46f)).setHeight(dp(size * .46f))
                    .setColorFilter(LayoutElementBuilders.ColorFilter.Builder().setTint(argb(tint)).build())
                    .build(),
            )
            .build()

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false, lines: Int = 1): LayoutElement =
        LayoutElementBuilders.Text.Builder()
            .setText(s)
            .setMaxLines(lines)
            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(size))
                    .setColor(argb(color))
                    .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                    .build(),
            )
            .build()

    private fun space(h: Float): LayoutElement = LayoutElementBuilders.Spacer.Builder().setHeight(dp(h)).build()

    private fun open(key: String, value: String, id: String) = ModifiersBuilders.Clickable.Builder()
        .setId(id)
        .setOnClick(
            ActionBuilders.LaunchAction.Builder().setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(packageName)
                    .setClassName(MainActivity::class.java.name)
                    .addKeyToExtraMapping(key, ActionBuilders.AndroidStringExtra.Builder().setValue(value).build())
                    .build(),
            ).build(),
        )
        .build()

    companion object {
        private val TEXTY = setOf("P", "H1", "H2", "H3", "Quote", "Bullet", "Num")
        private const val RES = "1"
        fun refresh(ctx: Context) = runCatching { getUpdater(ctx).requestUpdate(NotedTile::class.java) }
    }
}
