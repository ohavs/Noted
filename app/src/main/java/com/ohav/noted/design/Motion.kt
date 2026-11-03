package com.ohav.noted.design

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CancellationException
import kotlin.math.abs
import kotlin.math.min
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
    internal const val GLIDE_K = 230f
    internal const val GLIDE_C = 30f

    val touchDamping = TOUCH_C / (2f * sqrt(TOUCH_K))
    val glideDamping = GLIDE_C / (2f * sqrt(GLIDE_K))

    fun <T> touch(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        spring(dampingRatio = touchDamping, stiffness = TOUCH_K, visibilityThreshold = visibilityThreshold)

    fun <T> glide(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        spring(dampingRatio = glideDamping, stiffness = GLIDE_K, visibilityThreshold = visibilityThreshold)
}

/**
 * The glide spring, stepped frame by frame instead of by the wall clock: a frame never advances
 * the motion by more than one 60 Hz frame. A tap that builds heavy content (an unfolding card)
 * stalls the first frames; a clock-driven spring would already be most of the way there when the
 * next frame shows, so the move reads as a jump. Paced, every bit of the travel is on screen.
 * Velocity survives a cancelled run, so a retarget (or an effect restarted by a re-measure)
 * carries straight on instead of starting from rest.
 */
@Stable
class Paced(initial: Float, private val eps: Float = .5f) {
    var value by mutableFloatStateOf(initial)
        private set
    var velocity = 0f
        private set
    var target = initial
        private set
    private var stoppedAt = 0L
    private var runs = 0

    /** Jump without motion. */
    fun snapTo(v: Float) {
        value = v; target = v; velocity = 0f
    }

    /** Moves the value but keeps the momentum (the scroll was moved under us). */
    fun follow(v: Float) {
        value = v
    }

    suspend fun glideTo(to: Float, onStep: ((Float) -> Unit)? = null) {
        target = to
        // momentum carries over only from a run still going or cut a moment ago
        if (runs == 0 && System.nanoTime() - stoppedAt > 60_000_000L) velocity = 0f
        var last = -1L
        runs++
        try {
            while (true) {
                val now = withFrameNanos { it }
                if (last >= 0) {
                    var dt = min((now - last) / 1e9f, MAX_DT)
                    // semi-implicit Euler in small slices: stable for the glide constants at any dt
                    while (dt > 0f) {
                        val h = min(dt, SLICE)
                        velocity += (-Motion.GLIDE_K * (value - to) - Motion.GLIDE_C * velocity) * h
                        value += velocity * h
                        dt -= h
                    }
                    onStep?.invoke(value)
                }
                last = now
                if (abs(value - to) < eps && abs(velocity) < eps * 4f) {
                    value = to
                    velocity = 0f
                    onStep?.invoke(value)
                    return
                }
            }
        } catch (e: CancellationException) {
            stoppedAt = System.nanoTime()
            throw e
        } finally {
            runs--
        }
    }

    private companion object {
        const val MAX_DT = 1f / 60f
        const val SLICE = 1f / 240f
    }
}

