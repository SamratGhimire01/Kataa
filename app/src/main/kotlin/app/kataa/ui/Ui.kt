package app.kataa.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.TextView

/** Small view-building helpers so every screen uses the same type, glass and motion. */
class Ui(val context: Context, val look: Look) {
    private val density = context.resources.displayMetrics.density
    fun dp(v: Float) = v * density
    fun dpi(v: Float) = (v * density + 0.5f).toInt()

    fun text(sizeSp: Float, weight: Int = 400, color: Int = look.fg, gravity: Int = Gravity.START, lines: Int = 0) =
        TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            typeface = look.font(context, weight)
            setTextColor(color)
            includeFontPadding = false
            setLineSpacing(0f, 1.15f)
            this.gravity = gravity
            if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
            if (sizeSp >= 24) letterSpacing = -0.02f
        }

    /** A frosted panel: translucent fill, a brighter top edge, and a 1px rim. */
    fun glass(radiusDp: Float = 999f): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(NebulaView.mix(look.glass, look.glassHi, 0.45f), look.glass)).apply {
            cornerRadius = dp(radiusDp)
            setStroke(dpi(1f).coerceAtLeast(1), look.glassLine)
        }

    fun accentFill(radiusDp: Float = 999f): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(look.accent, look.accent2)).apply { cornerRadius = dp(radiusDp) }

    fun icon(res: Int, sizeDp: Float, tint: Int? = look.fg) = ImageView(context).apply {
        setImageResource(res)
        if (tint != null) imageTintList = ColorStateList.valueOf(tint)
        layoutParams = ViewGroup.LayoutParams(dpi(sizeDp), dpi(sizeDp))
    }

    fun rounded(view: View, radiusDp: Float) {
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(radiusDp)) }
        }
        view.clipToOutline = true
    }

    /** Presses shrink a little and spring back: every tap gets a physical answer. */
    fun pressable(view: View) {
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(120).setInterpolator(Motion.standard).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(Motion.spring).start()
            }
            false
        }
    }
}

/** The app's motion curves, kept in one place so everything moves the same way. */
object Motion {
    /** Ease out: things arrive softly. */
    val standard = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
    /** A small, calm overshoot for things that land (cards, objects, buttons). */
    val spring = PathInterpolator(0.25f, 1.2f, 0.4f, 1f)
    /** For things leaving. */
    val exit = PathInterpolator(0.4f, 0f, 0.2f, 1f)
}

/**
 * Lays children out by fractions of the screen, the way the design was drawn: each child says where
 * its center (or top) sits and how wide it is relative to the layout.
 */
class FracLayout(context: Context) : ViewGroup(context) {
    class Spec(
        val y: Float,
        val widthFrac: Float = 0.84f,
        /** If true, [y] is where the child's vertical center goes; otherwise its top. */
        val centerY: Boolean = false,
        /** 0 = left gutter, 0.5 = centered, 1 = right gutter. */
        val x: Float = 0.5f,
        /** Wrap the child's width instead of filling [widthFrac]. */
        val wrap: Boolean = false,
    ) : LayoutParams(WRAP_CONTENT, WRAP_CONTENT)

    var insetTop = 0
    var insetBottom = 0

    fun add(view: View, spec: Spec): View { addView(view, spec); return view }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec); val h = MeasureSpec.getSize(heightMeasureSpec)
        for (i in 0 until childCount) {
            val c = getChildAt(i); val s = c.layoutParams as Spec
            val cw = (w * s.widthFrac).toInt()
            c.measure(MeasureSpec.makeMeasureSpec(cw, if (s.wrap) MeasureSpec.AT_MOST else MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST))
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val usable = (b - t) - insetTop - insetBottom
        for (i in 0 until childCount) {
            val c = getChildAt(i); val s = c.layoutParams as Spec
            val gutter = (w * (1 - s.widthFrac) / 2).toInt()
            val left = when {
                s.x <= 0f -> gutter
                s.x >= 1f -> w - gutter - c.measuredWidth
                else -> ((w - c.measuredWidth) * s.x).toInt()
            }
            val y = insetTop + (usable * s.y).toInt()
            val top = if (s.centerY) y - c.measuredHeight / 2 else y
            c.layout(left, top, left + c.measuredWidth, top + c.measuredHeight)
        }
    }
}
