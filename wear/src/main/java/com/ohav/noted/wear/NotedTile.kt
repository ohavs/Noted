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
 * The tile, as the wearer chose in settings ([WView.tile]): all the notes (pinned first), the
 * newest, one category, one note (its lines), or the coming reminders, as paper strips with their
 * checklist progress. Checklist rows tick right here: in the lists, the open rows of the first note
 * that has any sit under its strip; in one-note mode every row toggles (the tile reloads, the
 * change goes to the phone). Tiles can't scroll, so the list pages: "עוד N ↓" under it turns to
 * the next ones, the heading (with ↑) back. A strip opens the note in the app; the tile only reads
 * the stored state, so it draws at once.
 */
class NotedTile : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { c ->
            // a row ticked on the tile: "t:<note>|<line>" ticks it, "u:…" unticks (here at once, sent to the phone)
            val id = requestParams.currentState.lastClickableId
            if (id.startsWith("t:") || id.startsWith("u:")) {
                id.substring(2).split('|', limit = 2).takeIf { it.size == 2 }?.let { (n, l) -> store.toggle(n, l, id[0] == 't') }
            }
            // the pager: "p:<list>|<first item>"
            requestParams.currentState.lastClickableId.takeIf { it.startsWith("p:") }?.removePrefix("p:")?.split('|', limit = 2)
                ?.takeIf { it.size == 2 }?.let { (k, i) -> page = k to (i.toIntOrNull() ?: 0) }
            c.set(tile(requestParams.deviceConfiguration.screenWidthDp.toFloat()))
            "tile"
        }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { c ->
            c.set(ResourceBuilders.Resources.Builder().setVersion(RES).build())
            "res"
        }

    private fun tile(width: Float): TileBuilders.Tile {
        val s = store.state.value
        val v = store.view.value
        val wide = width >= 210
        val per = if (wide) 4 else 3
        val col = LayoutElementBuilders.Column.Builder()
            .setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        fun note(t: String) { col.addContent(text(t, 14f, Palette.INK, lines = 2)) }
        val one = v.tile.removePrefix("note:").takeIf { v.tile.startsWith("note:") }?.let { s?.note(it) }
        val cat = v.tile.removePrefix("cat:").takeIf { v.tile.startsWith("cat:") }?.let { s?.cat(it) }
        when {
            s == null -> { head("noted", null, col); note("פתחו את noted בטלפון") }
            v.tile == WView.TILE_REMS -> {
                val rems = v.rems(s)
                paged(col, "בקרוב", v.tile, rems, if (wide) 3 else 2, "אין תזכורות קרובות") { r, _ ->
                    chip(r.title, whenText(r.at, r.due), Palette.SURFACE, Palette.INK, open("do", "rem", "r:" + r.id), width)
                }
            }
            one != null -> {
                // one note on the wrist: its strip, then its lines, a page at a time
                val readable = !one.locked && !one.blurBody
                val lines = if (readable) one.lines.filter { it.text.isNotBlank() && (it.isCheck || it.kind in TEXTY) } else emptyList()
                head(s.cat(one.cat)?.name ?: "פתק", null, col)
                col.addContent(strip(one, width))
                val tickable = readable && !one.readOnly
                paged(col, null, v.tile, lines, if (wide) 4 else 3, if (readable) null else "") { l, _ ->
                    if (l.isCheck && tickable) checkRow(one, l, width) else line(one, l, width)
                }
            }
            else -> {
                val (title, list) = when {
                    cat != null -> cat.name to s.notes.filter { s.top(it.cat) == cat.id }.sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.at })
                    v.tile == WView.TILE_RECENT -> "אחרונים" to v.notes(s).sortedByDescending { it.at }
                    else -> "פתקים" to v.notes(s).sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.at })
                }
                // the first note with something left to tick: its open rows right under its strip
                val focus = list.firstOrNull { n -> !n.locked && !n.blurBody && !n.readOnly && n.checks.any { !it.done && it.text.isNotBlank() } }
                val items = list.flatMap { n ->
                    if (n.id == focus?.id) listOf<Pair<Note, Line?>>(n to null) + n.checks.filter { !it.done && it.text.isNotBlank() }.map { n to it }
                    else listOf(n to null)
                }
                paged(col, title, v.tile, items, per, "אין פתקים עדיין") { (n, l), _ -> if (l == null) strip(n, width) else checkRow(n, l, width) }
            }
        }
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

    /**
     * [all] a page at a time under the heading [title]: [per] of them, then "עוד N ↓" to the next
     * page (on the last one "מההתחלה ↑"); past the first page the heading reads "↑ title" and
     * turns back one page. [empty]: what to say when there's nothing (null = say nothing).
     */
    private fun <T> paged(
        col: LayoutElementBuilders.Column.Builder, title: String?, key: String, all: List<T>, per: Int, empty: String?,
        item: (T, Int) -> LayoutElement,
    ) {
        val last = if (all.isEmpty()) 0 else (all.size - 1) / per * per
        val from = page.takeIf { it.first == key }?.second?.coerceIn(0, last) ?: 0
        if (title != null) head(title, if (from > 0) "p:$key|${from - per}" else null, col)
        if (all.isEmpty()) {
            if (!empty.isNullOrEmpty()) col.addContent(text(empty, 14f, Palette.INK, lines = 2))
            return
        }
        all.subList(from, minOf(all.size, from + per)).forEachIndexed { i, x ->
            col.addContent(space(if (i == 0 && title == null) 5f else if (i == 0) 0f else 5f))
            col.addContent(item(x, from + i))
        }
        if (all.size <= per) return
        val left = all.size - from - per
        col.addContent(space(2f))
        col.addContent(
            tap(
                if (left > 0) "עוד $left  ↓" else "מההתחלה  ↑",
                "p:$key|${if (left > 0) from + per else 0}", Palette.INK2,
            ),
        )
    }

    /** The heading; with [click] it's a way back up (one page). */
    private fun head(t: String, click: String?, col: LayoutElementBuilders.Column.Builder) {
        col.addContent(if (click == null) text(t, 12f, Palette.INK2) else tap("↑  $t", click, Palette.INK2))
        col.addContent(space(6f))
    }

    /** A line of text that turns the page: a wide, finger-sized target. */
    private fun tap(label: String, id: String, color: Int): LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setWidth(dp(110f)).setHeight(dp(24f))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder().setId(id)
                            .setOnClick(ActionBuilders.LoadAction.Builder().build()).build(),
                    )
                    .build(),
            )
            .addContent(text(label, 12f, color, bold = true))
            .build()

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

    /** One of the note's lines, to read: a checklist row with its box (filled when ticked), else the text. */
    private fun line(n: Note, l: Line, width: Float): LayoutElement {
        val row = LayoutElementBuilders.Row.Builder().setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        if (l.isCheck) {
            val ink = Palette.note(n.color)
            row.addContent(
                LayoutElementBuilders.Box.Builder()
                    .setWidth(dp(14f)).setHeight(dp(14f))
                    .setModifiers(
                        ModifiersBuilders.Modifiers.Builder()
                            .setBorder(ModifiersBuilders.Border.Builder().setWidth(dp(2f)).setColor(argb(ink)).build())
                            .setBackground(
                                ModifiersBuilders.Background.Builder().setColor(argb(if (l.done) ink else 0))
                                    .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(4f)).build()).build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            row.addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(8f)).build())
        }
        row.addContent(text(l.text.trim(), 13f, if (l.done) Palette.INK2 else Palette.INK))
        return LayoutElementBuilders.Box.Builder()
            .setWidth(dp(width * .72f)).setHeight(dp(26f))
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setPadding(ModifiersBuilders.Padding.Builder().setStart(dp(10f)).setEnd(dp(10f)).setRtlAware(true).build())
                    .setClickable(open("open", n.id, "l:${n.id}|${l.id}"))
                    .build(),
            )
            .addContent(row.build())
            .build()
    }

    /** A checklist row to tick (or untick) right on the tile: its box in the note's colour, filled when ticked. */
    private fun checkRow(n: Note, l: Line, width: Float): LayoutElement {
        val ink = Palette.note(n.color)
        val box = LayoutElementBuilders.Box.Builder()
            .setWidth(dp(16f)).setHeight(dp(16f))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBorder(ModifiersBuilders.Border.Builder().setWidth(dp(2f)).setColor(argb(ink)).build())
                    .setBackground(
                        ModifiersBuilders.Background.Builder().setColor(argb(if (l.done) ink else 0))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(5f)).build()).build(),
                    )
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
                        ModifiersBuilders.Clickable.Builder().setId((if (l.done) "u:" else "t:") + "${n.id}|${l.id}")
                            .setOnClick(ActionBuilders.LoadAction.Builder().build()).build(),
                    )
                    .build(),
            )
            .addContent(
                LayoutElementBuilders.Row.Builder()
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .addContent(box)
                    .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(8f)).build())
                    .addContent(text(l.text.trim(), 13f, if (l.done) Palette.INK2 else Palette.INK))
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
        private const val RES = "2"
        /** Which page the pager shows: the list and its first item (lives while the process does). */
        @Volatile private var page: Pair<String, Int> = "" to 0
        fun refresh(ctx: Context) = runCatching { getUpdater(ctx).requestUpdate(NotedTile::class.java) }
    }
}
