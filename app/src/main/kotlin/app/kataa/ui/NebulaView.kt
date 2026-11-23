package app.kataa.ui

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The Nebula drop: a wobbly liquid drop with clouds of light swirling inside and small sparkles.
 * It breathes when resting, swells and ripples with the voice, swirls faster while thinking, and on
 * "done" squeezes, bursts a ring of droplets and draws a check. [Mode.AURA] is the soft glow (no
 * edge) that sits behind an answer's object.
 *
 * Drawn on the GPU canvas every frame while visible; all motion is time-based so it is equally smooth
 * at 60, 90 or 120 Hz, and shaders are built once per look, so a frame allocates nothing.
 */
class NebulaView(context: Context) : View(context) {
    enum class Mode { NEBULA, AURA }
    enum class State { IDLE, LISTEN, THINK, DONE }

    var mode = Mode.NEBULA
    var look: Look = Look.NEON
        set(value) { field = value; buildShaders(); invalidate() }

    /** Live voice loudness 0..1 while listening. Smoothed here, so raw recognizer values are fine. */
    var voiceLevel = 0f

    private var state = State.IDLE
    private var doneAt = -100_000L
    private var energy = 0.15f
    private var level = 0f
    private var cy = 0.42f
    private var size = 1.32f
    private var cyTarget = 0.42f
    private var sizeTarget = 1.32f
    private var lastFrame = 0L
    private val start = SystemClock.uptimeMillis()
    private val slow = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val add = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        if (Build.VERSION.SDK_INT >= 29) blendMode = BlendMode.PLUS else xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val blob = Path()
    private val check = Path()
    private val checkPart = Path()
    private val measure = PathMeasure()
    private val m = Matrix()

    private var halo: Shader? = null
    private var gloss: Shader? = null
    private val lobes = arrayOfNulls<Shader>(6)
    private val auraLobes = arrayOfNulls<Shader>(5)

    init { buildShaders() }

    fun setState(s: State) {
        if (s == State.DONE && state != State.DONE) doneAt = SystemClock.uptimeMillis()
        state = s
        invalidate()
    }

    /** Where the drop sits (fraction of height) and how big it is; it glides there. */
    fun moveTo(centerY: Float, scale: Float, instant: Boolean = false) {
        cyTarget = centerY; sizeTarget = scale
        if (instant) { cy = centerY; size = scale }
        invalidate()
    }

    private fun unitRadial(center: Int, edge: Int) = RadialGradient(0f, 0f, 1f, center, edge, Shader.TileMode.CLAMP)

    private fun buildShaders() {
        val l = look
        halo = unitRadial(l.accent, l.accent and 0x00FFFFFF)
        gloss = unitRadial(Color.WHITE, 0x00FFFFFF)
        for (i in lobes.indices) {
            val c = mix(mix(l.accent, l.accent2, i / 5f), Color.WHITE, 0.2f)
            lobes[i] = unitRadial(withAlpha(c, if (l.light) 0.65f else 0.85f), c and 0x00FFFFFF)
        }
        for (i in auraLobes.indices) {
            val c = mix(l.accent, l.accent2, i / 4f)
            auraLobes[i] = unitRadial(withAlpha(c, if (l.light) 0.35f else 0.42f), c and 0x00FFFFFF)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else min(0.05f, (now - lastFrame) / 1000f)
        lastFrame = now
        val t = (now - start) / 1000f * (if (slow) 0.3f else 1f)

        // Smooth everything toward its target with time-based easing.
        val targetLevel = if (state == State.LISTEN) voiceLevel.coerceIn(0f, 1f) else 0f
        level += (targetLevel - level) * (1 - exp(-dt * 6f))
        val e = when (state) { State.IDLE -> 0.15f; State.LISTEN -> 0.55f; State.THINK -> 1f; State.DONE -> 0.25f }
        energy += (e - energy) * (1 - exp(-dt * 2.2f))
        val k = 1 - exp(-dt * 3.2f)
        cy += (cyTarget - cy) * k
        size += (sizeTarget - size) * k

        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cyPx = h * cy
        val base = min(w, h) * 0.19f * size
        val since = (now - doneAt) / 1000f
        val squeeze = if (state == State.DONE && since < 1.8f) {
            if (since < 0.2f) 1 - since * 0.8f
            else 0.84f + 0.16f * (1 - exp(-(since - 0.2f) * 4f) * cos((since - 0.2f) * 9f))
        } else 1f
        val r = base * squeeze * (1 + (if (slow) 0f else sin(t * 1.1f) * 0.025f))

        if (mode == Mode.AURA) drawAura(canvas, cx, cyPx, r, t) else drawNebula(canvas, cx, cyPx, r, t)
        if (state == State.DONE && since < 2.2f && mode == Mode.NEBULA) drawDone(canvas, cx, cyPx, base, since)

        if (isShown && windowVisibility == VISIBLE) postInvalidateOnAnimation()
    }

    private fun drawHalo(c: Canvas, cx: Float, cy: Float, r: Float, alpha: Float) {
        val hr = r * 2.6f
        m.setScale(hr, hr); m.postTranslate(cx, cy); halo!!.setLocalMatrix(m)
        glow.shader = halo; glow.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        c.drawCircle(cx, cy, hr, glow)
    }

    private fun drawNebula(c: Canvas, cx: Float, cy: Float, r: Float, time: Float) {
        val light = look.light
        drawHalo(c, cx, cy, r, if (light) 0.18f else 0.2f + level * 0.15f + energy * 0.08f)

        val tn = time * 0.5f // calm swirl inside
        val think = state == State.THINK
        val sp = if (think) 1.5f else 1f
        blob.rewind()
        val n = 120
        for (i in 0..n) {
            val th = i.toFloat() / n * 2f * PI.toFloat()
            val wob = 0.03f * sin(2 * th + tn * 0.9f) +
                energy * (0.06f * sin(3 * th + tn * 1.3f * sp) + 0.04f * sin(5 * th - tn * 1.9f * sp)) +
                level * 0.05f * sin(6 * th + tn * 3.2f)
            val rr = r * (1 + wob)
            val x = cx + cos(th) * rr; val y = cy + sin(th) * rr
            if (i == 0) blob.moveTo(x, y) else blob.lineTo(x, y)
        }
        blob.close()
        fill.shader = null
        fill.color = withAlpha(mix(look.accent, look.accent2, 0.5f), if (light) 0.35f else 0.22f)
        c.drawPath(blob, fill)

        c.save()
        c.clipPath(blob)
        val paint = if (light) fill else add
        for (i in lobes.indices) {
            val a = tn * (0.5f + energy * (if (think) 1.1f else 0.45f)) * (1 + i * 0.25f) + i * 1.1f
            val off = r * (0.25f + 0.3f * energy)
            val x = cx + cos(a) * off; val y = cy + sin(a * 1.2f) * off
            val rr = r * (0.6f + 0.12f * sin(tn * 1.4f + i * 2) + level * 0.2f)
            m.setScale(rr, rr); m.postTranslate(x, y); lobes[i]!!.setLocalMatrix(m)
            paint.shader = lobes[i]; paint.alpha = 255
            c.drawCircle(x, y, rr, paint)
        }
        paint.shader = null
        for (i in 0 until 18) {
            val seed = i * 12.9898f
            val a = seed + tn * (0.2f + (i % 4) * 0.08f) * sp
            val rr = r * (0.2f + (sin(seed) * 0.5f + 0.5f) * 0.7f)
            val tw = 0.5f + 0.5f * sin(tn * 1.6f + seed)
            dot.color = withAlpha(Color.WHITE, 0.75f * tw)
            c.drawCircle(cx + cos(a) * rr, cy + sin(a) * rr, r * 0.018f * (1 + tw), dot)
        }
        c.restore()

        stroke.shader = null
        stroke.strokeWidth = r * 0.02f
        stroke.color = if (light) withAlpha(Color.WHITE, 0.9f) else withAlpha(Color.WHITE, 0.28f)
        c.drawPath(blob, stroke)

        val gx = cx - r * 0.32f; val gy = cy - r * 0.38f; val gr = r * 0.55f
        m.setScale(gr, gr); m.postTranslate(gx, gy); gloss!!.setLocalMatrix(m)
        fill.shader = gloss; fill.alpha = ((if (light) 0.65f else 0.35f) * 255).toInt()
        c.save(); c.clipPath(blob); c.drawCircle(gx, gy, gr, fill); c.restore()
        fill.shader = null
    }

    private fun drawAura(c: Canvas, cx: Float, cy: Float, r: Float, t: Float) {
        val light = look.light
        drawHalo(c, cx, cy, r, if (light) 0.18f else 0.22f)
        val paint = if (light) fill else add
        for (i in auraLobes.indices) {
            val a = t * 0.45f * (1 + i * 0.3f) + i * 1.3f
            val x = cx + cos(a) * r * 0.35f; val y = cy + sin(a * 1.2f) * r * 0.3f
            val rr = r * (0.9f + 0.15f * sin(t * 1.2f + i))
            m.setScale(rr, rr); m.postTranslate(x, y); auraLobes[i]!!.setLocalMatrix(m)
            paint.shader = auraLobes[i]; paint.alpha = 255
            c.drawCircle(x, y, rr, paint)
        }
        paint.shader = null
        val sparkle = if (light) Color.WHITE else mix(look.accent, Color.WHITE, 0.6f)
        for (i in 0 until 12) {
            val seed = i * 7.31f
            val a = seed + t * 0.25f
            val rr = r * (0.5f + (sin(seed) * 0.5f + 0.5f) * 0.9f)
            val tw = 0.5f + 0.5f * sin(t * 1.6f + seed)
            dot.color = withAlpha(sparkle, (if (light) 0.9f else 0.7f) * tw)
            c.drawCircle(cx + cos(a) * rr, cy + sin(a) * rr * 0.8f, r * 0.02f * (1 + tw), dot)
        }
    }

    private fun drawDone(c: Canvas, cx: Float, cy: Float, r: Float, k: Float) {
        val ring = ease(k / 1.1f)
        if (k < 1.3f) {
            stroke.strokeWidth = max(0.5f, r * 0.06f * (1 - ring))
            stroke.color = withAlpha(look.accent, 1 - ring)
            c.drawCircle(cx, cy, r * (1.1f + ring * 1.3f), stroke)
        }
        if (k < 1.1f) for (i in 0 until 14) {
            val a = i / 14f * 2f * PI.toFloat() + 0.3f
            val d = r * (1.1f + ease(k / 0.9f) * 0.9f)
            dot.color = withAlpha(mix(look.accent, look.accent2, i / 13f), max(0f, 1 - k / 1.1f))
            c.drawCircle(cx + cos(a) * d, cy + sin(a) * d + k * k * r * 0.5f, r * 0.045f * (1 - k / 1.2f), dot)
        }
        val p = ease((k - 0.3f) / 0.65f)
        if (p > 0f) {
            check.rewind()
            check.moveTo(cx - 0.3f * r, cy + 0.02f * r)
            check.lineTo(cx - 0.08f * r, cy + 0.24f * r)
            check.lineTo(cx + 0.34f * r, cy - 0.2f * r)
            measure.setPath(check, false)
            checkPart.rewind()
            measure.getSegment(0f, measure.length * p, checkPart, true)
            stroke.strokeWidth = r * 0.11f
            stroke.color = 0x40000000
            c.save(); c.translate(0f, r * 0.02f); c.drawPath(checkPart, stroke); c.restore()
            stroke.color = withAlpha(Color.WHITE, 0.96f)
            c.drawPath(checkPart, stroke)
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        lastFrame = 0L
        if (visibility == VISIBLE) invalidate()
    }

    companion object {
        fun ease(x: Float): Float { val t = x.coerceIn(0f, 1f); return 1 - (1 - t).pow(3) }

        fun mix(a: Int, b: Int, t: Float): Int {
            val u = t.coerceIn(0f, 1f)
            fun ch(s: Int) = (((a shr s) and 0xFF) + ((((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * u)).toInt()
            return Color.argb(ch(24), ch(16), ch(8), ch(0))
        }

        fun withAlpha(c: Int, a: Float) = (c and 0x00FFFFFF) or ((abs(a).coerceIn(0f, 1f) * 255).toInt() shl 24)
    }
}
