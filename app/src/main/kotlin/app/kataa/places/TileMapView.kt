package app.kataa.places

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/**
 * A small OpenStreetMap view: drag, pinch, double-tap to zoom. Tiles are fetched from openstreetmap.org only
 * while this screen is open and kept on the phone (tiles seen once work offline later). No map SDK, no key.
 * Nothing about the user is sent: only which map squares are shown, like any map.
 */
class TileMapView(context: Context) : View(context) {
    data class Marker(val lat: Double, val lng: Double, val radiusM: Double, val label: String)

    var centerLat = 27.7172
        private set
    var centerLng = 85.3240
        private set
    var zoom = 13.0
        private set
    var markers: List<Marker> = emptyList()
        set(v) { field = v; invalidate() }
    /** Radius of the place being pinned, drawn around the centre pin. */
    var pinRadiusM = 100.0
        set(v) { field = v; invalidate() }
    var onMoved: (() -> Unit)? = null
    var dark = true
    var accent = 0xFF7C5CFF.toInt()

    private val main = Handler(Looper.getMainLooper())
    private val memory = object : LruCache<String, Bitmap>(64 * 1024 * 1024) { override fun sizeOf(k: String, v: Bitmap) = v.byteCount }
    private val loading = HashSet<String>()
    private val failed = HashMap<String, Long>()
    private val cacheDir = File(context.cacheDir, "tiles").apply { mkdirs() }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f; isFakeBoldText = true }
    private val tileSizePx get() = 256f * resources.displayMetrics.density

    fun moveTo(lat: Double, lng: Double, z: Double = zoom) {
        centerLat = lat.coerceIn(-85.0, 85.0); centerLng = lng; zoom = z.coerceIn(3.0, 19.0)
        invalidate(); onMoved?.invoke()
    }

    // Web Mercator, in tiles at zoom level z.
    private fun xOf(lng: Double, z: Int) = (lng + 180.0) / 360.0 * (1 shl z)
    private fun yOf(lat: Double, z: Int): Double { val r = Math.toRadians(lat); return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * (1 shl z) }
    private fun lngOf(x: Double, z: Int) = x / (1 shl z) * 360.0 - 180.0
    private fun latOf(y: Double, z: Int) = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / (1 shl z)))))

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(if (dark) 0xFF1B1D24.toInt() else 0xFFEDEBE6.toInt())
        val z = floor(zoom).toInt()
        val scale = 2.0.pow(zoom - z).toFloat()
        val ts = tileSizePx * scale
        val cx = xOf(centerLng, z); val cy = yOf(centerLat, z)
        val left = cx - width / 2.0 / ts; val top = cy - height / 2.0 / ts
        val n = 1 shl z
        for (ty in floor(top).toInt()..floor(top + height / ts).toInt()) {
            if (ty < 0 || ty >= n) continue
            for (tx in floor(left).toInt()..floor(left + width / ts).toInt()) {
                val wx = ((tx % n) + n) % n
                val bmp = tile(z, wx, ty) ?: parentTile(z, wx, ty)
                val dx = ((tx - left) * ts).toFloat(); val dy = ((ty - top) * ts).toFloat()
                if (bmp != null) canvas.drawBitmap(bmp.first, bmp.second, RectF(dx, dy, dx + ts, dy + ts), paint)
            }
        }
        // Saved places.
        for (m in markers) {
            val px = ((xOf(m.lng, z) - left) * ts).toFloat(); val py = ((yOf(m.lat, z) - top) * ts).toFloat()
            val r = (m.radiusM / metersPerPx()).toFloat()
            fill.color = (accent and 0x00FFFFFF) or 0x33000000; canvas.drawCircle(px, py, r.coerceAtLeast(10f), fill)
            fill.color = accent; canvas.drawCircle(px, py, 10f, fill)
            text.color = if (dark) 0xFFFFFFFF.toInt() else 0xFF111111.toInt(); canvas.drawText(m.label, px + 16f, py + 10f, text)
        }
        // The pin being placed: always at the centre, with its radius.
        val r = (pinRadiusM / metersPerPx()).toFloat()
        fill.color = (accent and 0x00FFFFFF) or 0x40000000
        canvas.drawCircle(width / 2f, height / 2f, r.coerceAtLeast(14f), fill)
        stroke.color = accent; canvas.drawCircle(width / 2f, height / 2f, r.coerceAtLeast(14f), stroke)
        fill.color = accent; canvas.drawCircle(width / 2f, height / 2f - 26f, 18f, fill)
        canvas.drawRect(width / 2f - 3f, height / 2f - 26f, width / 2f + 3f, height / 2f, fill)
        fill.color = 0xFFFFFFFF.toInt(); canvas.drawCircle(width / 2f, height / 2f - 26f, 7f, fill)
        text.textSize = 24f; text.color = if (dark) 0xCCFFFFFF.toInt() else 0xCC000000.toInt()
        canvas.drawText("© OpenStreetMap contributors", 16f, height - 16f, text)
        text.textSize = 30f
    }

    /** Metres per screen pixel at the centre. */
    fun metersPerPx(): Double = 156543.03392 * cos(Math.toRadians(centerLat)) / 2.0.pow(zoom) / resources.displayMetrics.density

    private fun tile(z: Int, x: Int, y: Int): Pair<Bitmap, android.graphics.Rect?>? {
        val key = "$z/$x/$y"
        memory.get(key)?.let { return it to null }
        load(key)
        return null
    }

    /** While a tile loads, show the bigger tile above it, stretched. */
    private fun parentTile(z: Int, x: Int, y: Int): Pair<Bitmap, android.graphics.Rect?>? {
        for (up in 1..3) {
            val pz = z - up; if (pz < 0) break
            val b = memory.get("$pz/${x shr up}/${y shr up}") ?: continue
            val part = 256 shr up
            val sx = (x and ((1 shl up) - 1)) * part; val sy = (y and ((1 shl up) - 1)) * part
            return b to android.graphics.Rect(sx, sy, sx + part, sy + part)
        }
        return null
    }

    private fun load(key: String) {
        if (key in loading) return
        failed[key]?.let { if (System.currentTimeMillis() - it < 30_000) return }
        loading += key
        io.execute {
            val file = File(cacheDir, key.replace('/', '_') + ".png")
            val bmp = runCatching {
                if (!file.exists() || System.currentTimeMillis() - file.lastModified() > 30L * 86_400_000) {
                    val c = URL("https://tile.openstreetmap.org/$key.png").openConnection() as HttpURLConnection
                    c.setRequestProperty("User-Agent", "Kataa/1.0 (Android memory app)")
                    c.connectTimeout = 8000; c.readTimeout = 10000
                    // Written to a temporary file first: a cut-off download must never be kept as a tile.
                    val part = File(file.path + ".part")
                    c.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
                    if (c.contentLength in 1 until part.length() + 1 || c.contentLength <= 0) part.renameTo(file) else part.delete()
                }
                BitmapFactory.decodeFile(file.path) ?: run { file.delete(); null }
            }.getOrNull() ?: runCatching { if (file.exists()) BitmapFactory.decodeFile(file.path) else null }.getOrNull()
            main.post {
                loading -= key
                if (bmp != null) { memory.put(key, bmp); invalidate() } else failed[key] = System.currentTimeMillis()
            }
        }
    }

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { moveTo(centerLat, centerLng, zoom + ln(d.scaleFactor.toDouble()) / ln(2.0)); return true }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            val z = floor(zoom).toInt(); val ts = tileSizePx * 2.0.pow(zoom - z)
            moveTo(latOf(yOf(centerLat, z) + dy / ts, z), lngOf(xOf(centerLng, z) + dx / ts, z))
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean { moveTo(centerLat, centerLng, zoom + 1); return true }
    })

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        if (!scaler.isInProgress) gestures.onTouchEvent(e)
        return true
    }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); memory.evictAll() }

    companion object {
        private val io = Executors.newFixedThreadPool(2)
    }
}
