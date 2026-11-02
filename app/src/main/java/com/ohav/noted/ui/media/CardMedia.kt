package com.ohav.noted.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ohav.noted.data.Block
import com.ohav.noted.data.BlockType
import com.ohav.noted.data.MediaExtra

/** Media blocks on a home card: smaller, read-only versions of the editor's blocks. */
@Composable
fun CardMedia(b: Block, modifier: Modifier = Modifier) {
    when (b.type) {
        BlockType.Images -> PicGrid(MediaExtra.pics(b.extra), modifier, corner = 14.dp, gap = 4.dp, maxShown = 4, maxPx = 640)
        BlockType.Voice -> MediaExtra.voice(b.extra)?.let { VoiceTile(it, compact = true, modifier = modifier) }
        BlockType.Link -> MediaExtra.link(b.extra)?.let { LinkCard(it, compact = true, modifier = modifier) }
        else -> Unit
    }
}
