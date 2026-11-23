package app.kataa.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import app.kataa.R

/**
 * The three color templates. Each is a complete look (colors + typeface); the user switches in
 * Settings and the whole app, the orb included, follows.
 */
enum class Look(
    val id: String,
    val label: String,
    val bg: Int,
    val fg: Int,
    val dim: Int,
    val accent: Int,
    val accent2: Int,
    val onAccent: Int,
    /** Frosted panels: fill, 1px edge, and the brighter top edge that makes them read as glass. */
    val glass: Int,
    val glassLine: Int,
    val glassHi: Int,
    val light: Boolean,
    private val fontRes: Int,
) {
    NEON("neon", "Black and neon",
        bg = 0xFF000000.toInt(), fg = 0xFFEFFFF7.toInt(), dim = 0xFF8FB3A2.toInt(),
        accent = 0xFF00FF88.toInt(), accent2 = 0xFF00C2FF.toInt(), onAccent = 0xFF001A0E.toInt(),
        glass = 0x14FFFFFF, glassLine = 0x29FFFFFF, glassHi = 0x33FFFFFF, light = false, fontRes = R.font.space_grotesk),
    SAFFRON("saffron", "Night blue and saffron",
        bg = 0xFF0B1230.toInt(), fg = 0xFFFBF4E6.toInt(), dim = 0xFFAEB5D6.toInt(),
        accent = 0xFFFFB547.toInt(), accent2 = 0xFFFF7A3D.toInt(), onAccent = 0xFF2A1400.toInt(),
        glass = 0x17FFFFFF, glassLine = 0x38FFD696, glassHi = 0x38FFFFFF, light = false, fontRes = R.font.manrope),
    PASTEL("pastel", "Soft pastel",
        bg = 0xFFF4F1FB.toInt(), fg = 0xFF24213A.toInt(), dim = 0xFF6E6A8C.toInt(),
        accent = 0xFF9B8CFF.toInt(), accent2 = 0xFFFF9ECF.toInt(), onAccent = 0xFFFFFFFF.toInt(),
        glass = 0x8CFFFFFF.toInt(), glassLine = 0xE6FFFFFF.toInt(), glassHi = 0xFFFFFFFF.toInt(), light = true, fontRes = R.font.nunito);

    private var cached: Typeface? = null

    /** The template's typeface at a weight (100..900). Variable fonts: exact weights on Android 9+, bold/regular before. */
    fun font(context: Context, weight: Int): Typeface {
        val base = cached ?: (runCatching { ResourcesCompat.getFont(context, fontRes) }.getOrNull() ?: Typeface.DEFAULT).also { cached = it }
        return if (Build.VERSION.SDK_INT >= 28) Typeface.create(base, weight, false)
        else Typeface.create(base, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
    }

    companion object {
        private const val PREFS = "kataa_ui"

        fun current(context: Context): Look {
            val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("look", NEON.id)
            return entries.firstOrNull { it.id == id } ?: NEON
        }

        fun save(context: Context, look: Look) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("look", look.id).apply()

        fun speakReplies(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("speak", true)
        fun setSpeakReplies(context: Context, on: Boolean) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("speak", on).apply()
    }
}
