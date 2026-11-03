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

/**
 * The tile: up to three pinned notes (the newest notes when nothing is pinned) as paper strips
 * with their checklist progress, and the two quick ways in — dictate a note / record one.
 * Everything opens the app; the tile itself only reads the stored state, so it draws at once.
 */
class NotedTile : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { c ->
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
        val accent = Palette.accent(s?.accent)
        val notes = s?.notes.orEmpty().let { all -> all.filter { it.pinned }.ifEmpty { all.sortedByDescending { it.at } } }.take(if (width >= 210) 3 else 2)
        val col = LayoutElementBuilders.Column.Builder()
            .setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text(if (s?.notes.orEmpty().any { it.pinned }) "נעוצים" else "אחרונים", 12f, Palette.INK2))
            .addContent(space(6f))
        if (s == null || notes.isEmpty()) {
            col.addContent(text(if (s == null) "פתחו את noted בטלפון" else "אין פתקים עדיין", 14f, Palette.INK, lines = 2))
        } else {
            notes.forEachIndexed { i, n ->
                if (i > 0) col.addContent(space(5f))
                col.addContent(strip(n, width))
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
        private const val RES = "1"
        fun refresh(ctx: Context) = runCatching { getUpdater(ctx).requestUpdate(NotedTile::class.java) }
    }
}
