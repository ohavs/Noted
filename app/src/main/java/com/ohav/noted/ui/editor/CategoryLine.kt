package com.ohav.noted.ui.editor

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ohav.noted.data.CatTree
import com.ohav.noted.data.CategoryEntity
import com.ohav.noted.data.foreign
import com.ohav.noted.data.shareInfo
import com.ohav.noted.design.Motion
import com.ohav.noted.design.Noted
import com.ohav.noted.design.Ph
import com.ohav.noted.design.Squircle
import com.ohav.noted.design.press
import com.ohav.noted.ui.categories.CatIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Saved in ‹category›" under the title of a note written from "all notes" — it went to the first
 * category on its own, so it says so. A tap unfolds the categories as a strip of paper tabs right
 * here; picking one moves the note and folds back. Nothing takes focus: the keyboard and the
 * cursor stay where they were, writing just goes on.
 */
@Composable
internal fun CategoryLine(tree: CatTree, currentId: String?, onPick: (CategoryEntity) -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    val current = tree.flat.firstOrNull { it.id == currentId }
    val parent = current?.let { k -> tree.parentOf[k.id]?.let { p -> tree.flat.firstOrNull { it.id == p } } }
    val turn by animateFloatAsState(if (open) 180f else 0f, Motion.touch(), label = "catCaret")
    val paper = LocalPaper.current
    val choices = remember(tree) { tree.flat.filterNot { it.foreign && it.shareInfo.readOnly } }

    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(
            Modifier
                .press(scale = .96f, haptic = false) { haptics.tick(); open = !open }
                .clip(Squircle(12.dp))
                // a dashed paper tab: it isn't a reminder (those are solid ink), it's where the note lives
                .drawBehind {
                    val w = 1.4.dp.toPx()
                    val r = 12.dp.toPx()
                    drawRoundRect(
                        c.noteInk2, topLeft = Offset(w / 2, w / 2),
                        size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
                    )
                }
                .padding(start = 12.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AnimatedContent(
                targetState = current?.id,
                transitionSpec = { (fadeIn(Motion.glide()) togetherWith fadeOut(Motion.glide())) },
                label = "catNow",
            ) { _ ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(CatIcons.of(current?.icon.orEmpty()), null, Modifier.size(17.dp), tint = c.noteInk)
                    Text("נשמר ב", style = t.label, color = c.noteInk2, maxLines = 1)
                    Text(
                        (parent?.let { "${it.name} · " } ?: "") + (current?.name ?: "…"),
                        style = t.label, color = c.noteInk, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 200.dp),
                    )
                }
            }
            Icon(Ph.CaretDown, if (open) "סגירה" else "החלפת קטגוריה", Modifier.size(15.dp).graphicsLayer { rotationZ = turn }, tint = c.noteInk2)
        }

        AnimatedVisibility(
            visible = open,
            enter = expandVertically(Motion.glide(), expandFrom = Alignment.Top) + fadeIn(Motion.glide()),
            exit = shrinkVertically(Motion.glide(), shrinkTowards = Alignment.Top) + fadeOut(Motion.glide()),
        ) {
            val list = rememberLazyListState()
            LaunchedEffect(Unit) {
                val i = choices.indexOfFirst { it.id == currentId }
                if (i > 1) list.scrollToItem(i - 1)
            }
            LazyRow(
                state = list,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(end = 22.dp),
            ) {
                items(choices, key = { it.id }) { k ->
                    val on = k.id == currentId
                    val sub = k.id in tree.parentOf
                    val bg by animateColorAsState(if (on) c.noteInk else c.noteInk.copy(alpha = .07f), Motion.glide(), label = "catBg")
                    val fg by animateColorAsState(if (on) paper else c.noteInk, Motion.glide(), label = "catFg")
                    Column(
                        Modifier
                            .widthIn(min = 76.dp, max = 132.dp)
                            .press(scale = .9f, haptic = false) {
                                if (on) { open = false; haptics.light(); return@press }
                                haptics.confirm()
                                onPick(k)
                                scope.launch { delay(260); open = false }
                            }
                            .clip(Squircle(16.dp))
                            .background(bg)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(CatIcons.of(k.icon), null, Modifier.size(22.dp), tint = fg)
                        Spacer(Modifier.height(6.dp))
                        Text(k.name, style = t.label, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (sub) {
                            val p = tree.parentOf[k.id]?.let { id -> tree.flat.firstOrNull { it.id == id }?.name }
                            if (p != null) Text(p, style = t.caption, color = fg.copy(alpha = .62f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
