/*
 * Open TimeLimit Copyright <C> 2019 - 2024 Jonas Lochmann
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package io.timelimit.android.ui.pause

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * The pause countdown ring.
 *
 * Material's CircularProgressIndicator was the obvious choice and it was tried first, but it draws
 * its arc inside a rect that ends up about 2dp off-centre relative to its own view, and with a track
 * that reaches the view's edge: the ring reads as misaligned and its bottom looks cut. Rather than
 * guess at that component's internal insets, this draws the two circles itself, centred on the view
 * by construction, so what the layout says is what the screen shows.
 *
 * The visual spec is still Material's: 6dp track, the Primary colour for the active arc over a
 * Secondary container track, a round cap (M3's circular indicator "appears as a dot at low
 * percentages", which a round cap gives for free), clockwise from twelve o'clock.
 *
 * Geometry: the stroke is centred on a circle inset by half a stroke from the view's edge, so the
 * painted ring is always exactly [strokeWidth] narrower than the view and never touches its bounds.
 */
class CountdownRing @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val TRACK_COLOR = 0xFF453E58.toInt()
        private const val ACTIVE_COLOR = 0xFFCFBCFF.toInt()
        private const val STROKE_DP = 6f
    }

    private val stroke = STROKE_DP * resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        color = TRACK_COLOR
    }

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        color = ACTIVE_COLOR
    }

    private val ringBounds = RectF()

    /** 0f to 1f. Set from the ticker; the view never animates itself, so the clock stays honest. */
    var fraction: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (clamped == field) return
            field = clamped
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        // Square, taking the smaller of the two constraints: a ring stretched into an oval is the
        // one shape mistake this component cannot survive.
        val size = minOf(measuredWidth, measuredHeight)

        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        // Inset by a full stroke, not half: a stroke is painted centred on its path, so half a
        // stroke puts the ring's outer edge exactly on the view's edge, where the antialiasing gets
        // cut and the ring reads as clipped. A full stroke leaves a clean margin.
        val inset = stroke
        val size = minOf(w, h)

        ringBounds.set(inset, inset, size - inset, size - inset)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawArc(ringBounds, 0f, 360f, false, trackPaint)

        if (fraction > 0f) {
            canvas.drawArc(ringBounds, -90f, 360f * fraction, false, activePaint)
        }
    }
}
