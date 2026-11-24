package app.kataa.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The "done" word: letters rise in one after another (soft blur-free fade and a small spring), then a
 * band of light sweeps across in the look's colors. [show] plays it; [hide] fades it out.
 */
class ShineTextView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private var text = ""
    private var widths = FloatArray(0)
    private var startedAt = 0L
    private var hiding = false
    private var hiddenAt = 0L
    private var shader: Shader? = null
    private val m = Matrix()
    private var maxTextPx = 0f
    var look: Look = Look.NEON
        set(value) { field = value; paint.typeface = value.font(context, 800); shader = null; requestLayout(); invalidate() }

    init { visibility = INVISIBLE }

    fun setMaxTextSize(px: Float) { maxTextPx = px }

    fun show(word: String) {
        text = word
        hiding = false
        startedAt = SystemClock.uptimeMillis()
        visibility = VISIBLE
        shader = null
        requestLayout(); invalidate()
    }

    fun hide() {
        if (visibility != VISIBLE || hiding) return
        hiding = true; hiddenAt = SystemClock.uptimeMillis(); invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        paint.textSize = maxTextPx
        // Shrink long words so they fit on one line.
        val full = paint.measureText(text)
        if (full > w * 0.92f && full > 0) paint.textSize = maxTextPx * (w * 0.92f / full)
        val fm = paint.fontMetrics
        setMeasuredDimension(w, (fm.descent - fm.ascent + paint.textSize * 0.5f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (text.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val t = (now - startedAt) / 1000f
        if (widths.size != text.length) widths = FloatArray(text.length)
        paint.getTextWidths(text, widths)
        val total = widths.sum()
        val x0 = (width - total) / 2f
        val fm = paint.fontMetrics
        val baseline = height / 2f - (fm.ascent + fm.descent) / 2f

        if (shader == null) {
            shader = LinearGradient(0f, 0f, 3 * max(total, 1f), 0f,
                intArrayOf(look.accent, look.accent2, Color.WHITE, look.accent2, look.accent),
                floatArrayOf(0f, 0.45f, 0.5f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }
        // The light band travels from left to right once, after the letters land.
        val sweep = NebulaView.ease(((t - 1.1f) / 2.2f))
        m.setTranslate(x0 + (-2 * total) * (1 - sweep), 0f)
        shader!!.setLocalMatrix(m)
        paint.shader = shader

        val fadeOut = if (hiding) 1f - min(1f, (now - hiddenAt) / 350f) else 1f
        var x = x0
        for (i in text.indices) {
            val local = ((t - 0.45f - i * 0.06f) / 0.8f).coerceIn(0f, 1f)
            val p = backOut(local)
            val a = (local / 0.75f).coerceIn(0f, 1f) * fadeOut
            if (a > 0f) {
                paint.alpha = (a * 255).toInt()
                canvas.save()
                val cw = widths[i]
                canvas.translate(0f, (1 - p) * paint.textSize * 0.45f)
                canvas.scale(0.9f + 0.1f * p, 0.9f + 0.1f * p, x + cw / 2f, baseline)
                canvas.drawText(text, i, i + 1, x, baseline, paint)
                canvas.restore()
            }
            x += widths[i]
        }
        val running = (!hiding && t < 3.6f) || (hiding && fadeOut > 0f)
        if (hiding && fadeOut <= 0f) visibility = INVISIBLE
        if (running) postInvalidateOnAnimation()
    }

    /** Gentle spring: a little past the mark, then settles (like cubic-bezier(.25,1.2,.4,1)). */
    private fun backOut(x: Float): Float {
        val c1 = 0.9f; val c3 = c1 + 1
        return 1 + c3 * (x - 1).pow(3) + c1 * (x - 1).pow(2)
    }
}
