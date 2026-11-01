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

private fun variable(res: Int, vararg weights: Int) = FontFamily(
    weights.map { w -> Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w))) }
)

private object Families {
    val fredoka by lazy { variable(R.font.fredoka, 400, 500, 600, 700) }
    val assistant by lazy { variable(R.font.assistant, 400, 500, 600, 700, 800) }
    val frank by lazy { variable(R.font.frank_ruhl_libre, 500, 700, 800, 900) }
    val rubik by lazy { variable(R.font.rubik, 400, 500, 600, 700, 800) }
    val heebo by lazy { variable(R.font.heebo, 400, 500, 600, 700, 800) }
    val karantina by lazy {
        FontFamily(Font(R.font.karantina_regular, FontWeight.Normal), Font(R.font.karantina_bold, FontWeight.Bold))
    }
}

/** The three pairs offered in the prototype. `scale` compensates condensed display faces. */
enum class FontPair(val label: String, val desc: String, val displayWeight: FontWeight, val scale: Float, val trackingEm: Float) {
    Soft("רך", "Fredoka + Assistant · עגול וחם", FontWeight.SemiBold, 1f, -0.01f),
    Editorial("עיתונאי", "Frank Ruhl Libre + Rubik · קלאסי וחד", FontWeight.ExtraBold, 1f, -0.02f),
    Condensed("דחוס", "Karantina + Heebo · צפוף ונועז", FontWeight.Bold, 1.38f, 0f);

    val display: FontFamily
        get() = when (this) { Soft -> Families.fredoka; Editorial -> Families.frank; Condensed -> Families.karantina }
    val body: FontFamily
        get() = when (this) { Soft -> Families.assistant; Editorial -> Families.rubik; Condensed -> Families.heebo }
}

@Immutable
class NotedType(val pair: FontPair) {
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
