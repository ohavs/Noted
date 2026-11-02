package com.ohav.noted.ui.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ohav.noted.data.LinkInfo
import com.ohav.noted.data.LinkPreview
import com.ohav.noted.design.Btn
import com.ohav.noted.design.Motion
import com.ohav.noted.design.Noted
import com.ohav.noted.design.Ph
import com.ohav.noted.design.Squircle
import com.ohav.noted.design.press

fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Link preview card: picture, site, title, description. Tap opens the page. */
@Composable
fun LinkCard(l: LinkInfo, compact: Boolean = false, modifier: Modifier = Modifier) {
    val c = Noted.colors
    val t = Noted.type
    val ctx = LocalContext.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(Squircle(18.dp))
            .background(c.noteInk.copy(alpha = .07f))
            .press(scale = .98f) { openUrl(ctx, l.url) }
            .animateContentSize(Motion.glide()),
    ) {
        if (l.img.isNotEmpty() && !compact) MediaImage(l.img, Modifier.fillMaxWidth().aspectRatio(1.91f), maxPx = 900)
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (l.img.isNotEmpty() && compact) {
                MediaImage(l.img, Modifier.size(52.dp).clip(Squircle(12.dp)), maxPx = 256)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ph.Globe, null, Modifier.size(14.dp), tint = c.noteInk2)
                    Spacer(Modifier.width(5.dp))
                    Text(l.site.ifEmpty { LinkPreview.host(l.url) }, style = t.meta, color = c.noteInk2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!l.loaded) {
                    val pulse = rememberInfiniteTransition(label = "linkLoad")
                    val a by pulse.animateFloat(.06f, .14f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "a")
                    Spacer(Modifier.fillMaxWidth(.8f).height(14.dp).graphicsLayer { alpha = 1f }.clip(RoundedCornerShape(7.dp)).background(c.noteInk.copy(alpha = a)))
                    Spacer(Modifier.fillMaxWidth(.5f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(c.noteInk.copy(alpha = a)))
                } else {
                    Text(
                        l.title.ifEmpty { l.url },
                        style = t.note.copy(fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 21.sp, textDirection = TextDirection.Content),
                        color = c.noteInk, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    if (l.desc.isNotBlank() && !compact) {
                        Text(
                            l.desc,
                            style = t.note.copy(fontSize = 14.sp, lineHeight = 19.sp, textDirection = TextDirection.Content),
                            color = c.noteInk2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Address field for the link sheet (new card or a link over selected text). */
@Composable
fun LinkField(initial: String, action: String, onDone: (String) -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    var v by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val url = LinkPreview.normalize(v)
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(Squircle(16.dp)).background(c.bg2).padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Ph.Link, null, Modifier.size(18.dp), tint = c.ink2)
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                v, { v = it },
                singleLine = true,
                textStyle = t.body.copy(color = c.ink, textDirection = TextDirection.Ltr),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { url?.let(onDone) }),
                modifier = Modifier.weight(1f).focusRequester(focus),
                decorationBox = { inner ->
                    if (v.isEmpty()) Text("https://", style = t.body.copy(textDirection = TextDirection.Ltr), color = c.ink3)
                    inner()
                },
            )
        }
        Btn(action, Modifier.fillMaxWidth().graphicsLayer { alpha = if (url != null) 1f else .45f }, icon = Ph.Check) { url?.let(onDone) }
    }
}
