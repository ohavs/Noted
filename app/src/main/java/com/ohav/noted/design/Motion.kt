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
 *  (+ [Paced]: glide behind a soft lead, for the stack's big layout moves.)
 * Springs are interruptible by nature — retargeting keeps velocity.
 */
object Motion {
    private const val TOUCH_K = 520f
    private const val TOUCH_C = 25f
    internal const val GLIDE_K = 230f
    internal const val GLIDE_C = 30f

    /** Paced's lead into glide (critically damped, ω=30): eases big layout moves in. */
    internal const val LEAD_K = 900f
    internal const val LEAD_C = 60f

    val touchDamping = TOUCH_C / (2f * sqrt(TOUCH_K))
    val glideDamping = GLIDE_C / (2f * sqrt(GLIDE_K))

    fun <T> touch(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        spring(dampingRatio = touchDamping, stiffness = TOUCH_K, visibilityThreshold = visibilityThreshold)

    fun <T> glide(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        spring(dampingRatio = glideDamping, stiffness = GLIDE_K, visibilityThreshold = visibilityThreshold)
}

/**
 * The glide spring, stepped frame by frame instead of by the wall clock, behind a soft lead.
 *  - Paced: a frame never advances the motion by more than one 60 Hz frame. A tap that builds
 *    heavy content (an unfolding card) stalls the first frames; a clock-driven spring would already
 *    be most of the way there when the next frame shows, so the move reads as a jump.
 *  - Lead: the glide spring doesn't chase the target itself but a lead point that springs to it
 *    first ([Motion.LEAD_K], critically damped). A bare spring starts at full acceleration — a
 *    tall card unfolding kicks off with a jolt; through the lead it eases in, then glides home the
 *    same way, and a retarget (the open card measuring taller a frame later) bends the curve
 *    instead of kinking it.
 * Velocity (of both) survives a cancelled run, so a retarget or an effect restarted by a
 * re-measure carries straight on instead of starting from rest.
 */
@Stable
class Paced(initial: Float, private val eps: Float = .5f) {
    var value by mutableFloatStateOf(initial)
        private set
    var velocity = 0f
        private set
    var target = initial
        private set
    private var lead = initial
    private var leadV = 0f
    private var stoppedAt = 0L
    private var runs = 0
    private var lastFrame = -1L

    /** Jump without motion. */
    fun snapTo(v: Float) {
        value = v; target = v; velocity = 0f; lead = v; leadV = 0f
    }

    /** Moves the value but keeps the momentum (the scroll was moved under us). */
    fun follow(v: Float) {
        lead += v - value
        value = v
    }

    suspend fun glideTo(to: Float, onStep: ((Float) -> Unit)? = null) {
        target = to
        // momentum carries over only from a run still going or cut a moment ago — and then so does
        // the frame clock, or the first frame of the new run would stand still (a one-frame hitch)
        val carry = runs > 0 || System.nanoTime() - stoppedAt <= 60_000_000L
        if (!carry) {
            velocity = 0f; lead = value; leadV = 0f
        }
        var last = if (carry) lastFrame else -1L
        runs++
        try {
            while (true) {
                val now = withFrameNanos { it }
                if (last >= 0) {
                    var dt = min((now - last) / 1e9f, PACE_MAX_DT)
                    // semi-implicit Euler in small slices: stable for these constants at any dt
                    while (dt > 0f) {
                        val h = min(dt, PACE_SLICE)
                        leadV += (-Motion.LEAD_K * (lead - to) - Motion.LEAD_C * leadV) * h
                        lead += leadV * h
                        velocity += (-Motion.GLIDE_K * (value - lead) - Motion.GLIDE_C * velocity) * h
                        value += velocity * h
                        dt -= h
                    }
                    onStep?.invoke(value)
                }
                last = now
                lastFrame = now
                if (abs(value - to) < eps && abs(velocity) < eps * 4f && abs(lead - to) < eps) {
                    snapTo(to)
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

}

internal const val PACE_MAX_DT = 1f / 60f
internal const val PACE_SLICE = 1f / 480f

/**
 * A screen-space offset that glides back to 0 on [Paced]'s curve (lead + glide, frame-paced by its
 * owner). FLIP: the layout jumps straight to where things belong and [add] takes up the jump, so
 * every element travels from exactly where it was shown — a new target mid-flight just adds to
 * the offset, momentum kept. Plain fields: the owner steps it and invalidates what reads it.
 */
class Drift(private val eps: Float = .5f) {
    var value = 0f
        private set
    private var velocity = 0f
    private var lead = 0f
    private var leadV = 0f

    val active: Boolean get() = value != 0f || velocity != 0f || lead != 0f || leadV != 0f

    fun add(d: Float) {
        if (d == 0f || d.isNaN()) return
        value += d
        lead += d
    }

    fun step(dt: Float) {
        if (!active) return
        var left = min(dt, PACE_MAX_DT)
        while (left > 0f) {
            val h = min(left, PACE_SLICE)
            leadV += (-Motion.LEAD_K * lead - Motion.LEAD_C * leadV) * h
            lead += leadV * h
            velocity += (-Motion.GLIDE_K * (value - lead) - Motion.GLIDE_C * velocity) * h
            value += velocity * h
            left -= h
        }
        if (abs(value) < eps && abs(velocity) < eps * 4f && abs(lead) < eps) {
            value = 0f; velocity = 0f; lead = 0f; leadV = 0f
        }
    }
}
