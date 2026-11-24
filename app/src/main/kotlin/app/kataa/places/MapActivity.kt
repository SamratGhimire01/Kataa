package app.kataa.places

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import app.kataa.KataaApplication
import app.kataa.R
import app.kataa.core.model.Place
import app.kataa.core.model.PlaceOrigin
import app.kataa.triggers.Triggers
import app.kataa.ui.Look
import app.kataa.ui.Ui

/**
 * Pin a place on the map: drag the map under the pin, name it ("Gym", "Didi's house"), pick how close counts,
 * save. Reminders for that name ("remind me to pay fees when I reach the gym") then fire there. Also lists
 * "My places", where each can be moved or deleted.
 */
class MapActivity : ComponentActivity() {
    private val app get() = application as KataaApplication
    private lateinit var look: Look
    private lateinit var ui: Ui
    private lateinit var map: TileMapView
    private lateinit var name: EditText
    private lateinit var coords: TextView
    private lateinit var radiusText: TextView
    private lateinit var placesBox: LinearLayout
    private var editing: Place? = null

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it }) goToMe() else Toast.makeText(this, "Allow location to jump to where you are.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        look = Look.current(this)
        ui = Ui(this, look)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION") run { window.statusBarColor = Color.TRANSPARENT; window.navigationBarColor = Color.TRANSPARENT }
        WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = look.light; isAppearanceLightNavigationBars = look.light }

        val root = FrameLayout(this).apply { setBackgroundColor(look.bg) }
        map = TileMapView(this).apply { dark = !look.light; accent = look.accent; onMoved = { updateCoords() } }
        root.addView(map, FrameLayout.LayoutParams(-1, -1))

        val back = ui.icon(R.drawable.ic_back, 24f, look.fg).apply {
            background = ui.glass(); setPadding(ui.dpi(10f), ui.dpi(10f), ui.dpi(10f), ui.dpi(10f)); contentDescription = "Back"
            setOnClickListener { finish() }
        }
        root.addView(back, FrameLayout.LayoutParams(ui.dpi(46f), ui.dpi(46f)).apply { leftMargin = ui.dpi(16f); topMargin = ui.dpi(16f) })
        val me = ui.text(14.5f, 700).apply {
            text = "My location"; background = ui.glass(); setPadding(ui.dpi(16f), ui.dpi(11f), ui.dpi(16f), ui.dpi(11f))
            setOnClickListener { goToMe() }
        }
        root.addView(me, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { rightMargin = ui.dpi(16f); topMargin = ui.dpi(16f) })

        // The sheet at the bottom: name, radius, save, my places.
        name = EditText(this).apply {
            hint = "Name this place (Gym, Didi's house)"; setHintTextColor(look.dim); setTextColor(look.fg); textSize = 16f
            typeface = look.font(this@MapActivity, 600); isSingleLine = true
            background = ui.glass(18f); setPadding(ui.dpi(16f), ui.dpi(12f), ui.dpi(16f), ui.dpi(12f))
            setText(intent.getStringExtra(EXTRA_NAME) ?: "")
        }
        coords = ui.text(12.5f, 500, look.dim)
        radiusText = ui.text(14f, 600)
        val radii = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for (r in listOf(50, 100, 200, 500)) addView(ui.text(14f, 700, look.fg, Gravity.CENTER).apply {
                text = "$r m"; background = ui.glass(); setPadding(ui.dpi(14f), ui.dpi(8f), ui.dpi(14f), ui.dpi(8f))
                setOnClickListener { map.pinRadiusM = r.toDouble(); updateCoords() }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dpi(8f) })
        }
        val save = ui.text(16f, 800, look.onAccent, Gravity.CENTER).apply {
            text = "Save place"; background = ui.accentFill(); setPadding(0, ui.dpi(14f), 0, ui.dpi(14f))
            setOnClickListener { savePlace() }
        }
        placesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = ui.glass(26f)
            setPadding(ui.dpi(18f), ui.dpi(16f), ui.dpi(18f), ui.dpi(16f))
            addView(ui.text(13f, 600, look.dim).apply { text = "Move the map so the pin sits on the place" })
            addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dpi(10f) })
            addView(coords, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dpi(6f) })
            addView(radiusText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dpi(10f) })
            addView(radii, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dpi(6f) })
            addView(save, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dpi(14f) })
            addView(ScrollView(this@MapActivity).apply { addView(placesBox) }, LinearLayout.LayoutParams(-1, ui.dpi(150f)).apply { topMargin = ui.dpi(10f) })
        }
        val sheetParams = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { leftMargin = ui.dpi(12f); rightMargin = ui.dpi(12f); bottomMargin = ui.dpi(12f) }
        root.addView(sheet, sheetParams)
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            (back.layoutParams as FrameLayout.LayoutParams).topMargin = bars.top + ui.dpi(12f)
            (me.layoutParams as FrameLayout.LayoutParams).topMargin = bars.top + ui.dpi(12f)
            sheetParams.bottomMargin = bars.bottom + ui.dpi(12f)
            root.requestLayout(); insets
        }
        updateCoords()
        loadPlaces(centerOnFirst = true)
    }

    private fun updateCoords() {
        coords.text = "%.6f, %.6f".format(java.util.Locale.ROOT, map.centerLat, map.centerLng)
        radiusText.text = "Reminders fire within ${map.pinRadiusM.toInt()} m"
    }

    private fun hasLocation() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun goToMe() {
        if (!hasLocation()) { locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)); return }
        Toast.makeText(this, "Finding you…", Toast.LENGTH_SHORT).show()
        Thread {
            val l = Triggers.currentLocation(this)
            runOnUiThread {
                if (l == null) Toast.makeText(this, "Couldn't find your location. Is GPS on?", Toast.LENGTH_LONG).show()
                else map.moveTo(l.latitude, l.longitude, 17.0)
            }
        }.start()
    }

    private fun savePlace() {
        val label = name.text.toString().trim()
        if (label.isEmpty()) { name.error = "Give it a name"; return }
        val lat = map.centerLat; val lng = map.centerLng; val radius = map.pinRadiusM
        val old = editing
        app.cores.worker.execute {
            val r = runCatching {
                val c = app.cores.core
                if (old != null) c.places.update(old.id, label, lat, lng, radius)
                else c.places.save(label, lat, lng, radiusM = radius)
            }
            runCatching { Triggers.refreshGeofences(this, null, force = true) }
            runOnUiThread {
                if (r.isFailure) { Toast.makeText(this, "Couldn't save: ${r.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show(); return@runOnUiThread }
                editing = null
                Toast.makeText(this, "Saved $label. Say \"remind me to … when I reach $label\".", Toast.LENGTH_LONG).show()
                val retry = intent.getStringExtra(EXTRA_RETRY)
                if (retry != null) { setResult(RESULT_OK, Intent().putExtra(EXTRA_RETRY, retry)); finish(); return@runOnUiThread }
                name.setText("")
                loadPlaces(centerOnFirst = false)
            }
        }
    }

    private fun loadPlaces(centerOnFirst: Boolean) {
        app.cores.worker.execute {
            val places = runCatching { app.cores.core.places.userPlaces().filter { it.origin != PlaceOrigin.POI_PACK } }.getOrDefault(emptyList())
            val last = if (centerOnFirst && places.isEmpty()) Triggers.recentLocation(this, 30 * 60_000L) else null
            runOnUiThread {
                map.markers = places.map { TileMapView.Marker(it.lat, it.lng, it.radiusM, it.label) }
                if (centerOnFirst) when {
                    last != null -> map.moveTo(last.latitude, last.longitude, 16.0)
                    places.isNotEmpty() -> map.moveTo(places.first().lat, places.first().lng, 15.0)
                }
                placesBox.removeAllViews()
                placesBox.addView(ui.text(14f, 700).apply { text = if (places.isEmpty()) "No places yet" else "My places" })
                places.forEach { p ->
                    placesBox.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, ui.dpi(8f), 0, ui.dpi(8f))
                        addView(ui.text(15f, 600).apply { text = p.label; setOnClickListener { map.moveTo(p.lat, p.lng, 17.0) } }, LinearLayout.LayoutParams(0, -2, 1f))
                        addView(ui.text(13.5f, 700, look.accent).apply { text = "Move"; setPadding(ui.dpi(10f), 0, ui.dpi(10f), 0)
                            setOnClickListener { editing = p; name.setText(p.label); map.pinRadiusM = p.radiusM; map.moveTo(p.lat, p.lng, 17.0)
                                Toast.makeText(this@MapActivity, "Drag the map, then Save place", Toast.LENGTH_SHORT).show() } })
                        addView(ui.text(13.5f, 700, look.dim).apply { text = "Delete"; setPadding(ui.dpi(10f), 0, 0, 0)
                            setOnClickListener { app.cores.worker.execute { runCatching { app.cores.core.places.delete(p.id) }; runCatching { Triggers.refreshGeofences(this@MapActivity, null, force = true) }; runOnUiThread { loadPlaces(false) } } } })
                    })
                }
            }
        }
    }

    companion object {
        const val EXTRA_NAME = "app.kataa.extra.PLACE_NAME"
        /** What the user said that needed this place; said again after saving so the reminder gets its place. */
        const val EXTRA_RETRY = "app.kataa.extra.RETRY"

        fun intent(context: Context, name: String? = null, retry: String? = null) = Intent(context, MapActivity::class.java)
            .putExtra(EXTRA_NAME, name).putExtra(EXTRA_RETRY, retry)
    }
}
