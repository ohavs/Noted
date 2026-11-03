@file:OptIn(ExperimentalTextApi::class)

package com.ohav.noted.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.ohav.noted.R

/**
 * The app's one type pairing ("דחוס"): Karantina for display, Heebo for text. Both SIL Open Font License 1.1
 * (google/fonts ofl/karantina, ofl/heebo) — free for any use, bundling included; see assets/licenses/.
 */
object NotedFonts {
    val display: FontFamily by lazy {
        FontFamily(Font(R.font.karantina_regular, FontWeight.Normal), Font(R.font.karantina_bold, FontWeight.Bold))
    }
    val body: FontFamily by lazy {
        FontFamily(listOf(400, 500, 600, 700, 800).map { w ->
            Font(R.font.heebo, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
        })
    }
    val displayWeight = FontWeight.Bold
    /** Karantina is condensed: display sizes scale up to sit level with the text face. */
    const val scale = 1.38f
    const val trackingEm = 0f
}

@Immutable
class NotedType {
    private val pair = NotedFonts
    private fun display(size: Float, line: Float = 1.1f) = TextStyle(
        fontFamily = pair.display, fontWeight = pair.displayWeight,
        fontSize = (size * pair.scale).sp, lineHeight = (size * pair.scale * line).sp,
        letterSpacing = pair.trackingEm.em,
    )
    private fun body(size: Float, weight: FontWeight = FontWeight.Normal, line: Float = 1.45f) = TextStyle(
        fontFamily = pair.body, fontWeight = weight, fontSize = size.sp, lineHeight = (size * line).sp,
    )

    val hero = display(44f, 1.0f)
    val title = display(30f)
    val noteTitle = display(23f, 1.15f)
    val sample = display(34f, 1f)
    val body = body(17f)
    val bodyStrong = body(17f, FontWeight.Bold)
    val note = body(16.5f)
    val label = body(14.5f, FontWeight.Bold, 1.3f)
    val caption = body(13f, FontWeight.SemiBold, 1.3f)
    val section = body(13f, FontWeight.ExtraBold, 1.3f)

    // home / stack (sizes from the prototype CSS)
    val cat = display(58f, 1.05f)
    val subCat = display(27f, 1.1f)
    val cardTitle = display(25f, 1.15f)
    val emptyTitle = display(26f, 1.15f)
    val barTitle = display(24f, 1.1f)
    val snippet = body(16f)
    val meta = body(14f, FontWeight.SemiBold, 1.3f)
    val sub = body(15f, FontWeight.Normal, 1.3f)
    val button = body(17f, FontWeight.Bold, 1.2f)
    val field = body(17f, FontWeight.Normal, 1.3f)
    val tiny = body(12f, FontWeight.Bold, 1.2f)
}
