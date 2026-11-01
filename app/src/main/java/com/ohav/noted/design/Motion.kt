package com.ohav.noted.design

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import kotlin.math.sqrt

/**
 * The only two springs in the app (same physics as the prototype, mass = 1):
 *  - touch: k=520, c=25 → ζ≈0.55, lively; press feedback, toggles, things under the finger.
 *  - glide: k=230, c=30 → ζ≈0.99, smooth; layout, screens, colors.
 * Springs are interruptible by nature — retargeting keeps velocity.
 */
object Motion {
    private const val TOUCH_K = 520f
    private const val TOUCH_C = 25f
    private const val GLIDE_K = 230f
    private const val GLIDE_C = 30f

    val touchDamping = TOUCH_C / (2f * sqrt(TOUCH_K))
    val glideDamping = GLIDE_C / (2f * sqrt(GLIDE_K))

    fun <T> touch(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        spring(dampingRatio = touchDamping, stiffness = TOUCH_K, visibilityThreshold = visibilityThreshold)

    fun <T> glide(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        spring(dampingRatio = glideDamping, stiffness = GLIDE_K, visibilityThreshold = visibilityThreshold)
}
