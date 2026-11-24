package app.kataa.core.places

import app.kataa.core.db.Row
import app.kataa.core.db.SqlDriver
import app.kataa.core.db.queryOne
import app.kataa.core.model.Place
import app.kataa.core.model.PlaceOrigin
import app.kataa.core.model.Poi
import app.kataa.core.util.TimeSource
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class PlaceRepository(private val db: SqlDriver, private val clock: TimeSource) {

    /** A place the user named ("home", "office", "this Big Mart"). */
    fun save(
        label: String,
        lat: Double,
        lng: Double,
        radiusM: Double = DEFAULT_RADIUS_M,
        brand: String? = null,
        placeType: String? = null,
        origin: PlaceOrigin = PlaceOrigin.USER,
    ): Place {
        require(lat in -90.0..90.0 && lng in -180.0..180.0) { "invalid coordinates" }
        db.execute(
            """
            INSERT INTO places(label, brand, place_type, lat, lng, radius_m, origin, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(label, lat, lng) DO UPDATE SET radius_m = excluded.radius_m,
                brand = coalesce(excluded.brand, places.brand), place_type = coalesce(excluded.place_type, places.place_type)
            """.trimIndent(),
            label.trim(), normalizeBrand(brand), placeType, lat, lng, radiusM.coerceAtLeast(MIN_RADIUS_M), origin.name, clock.nowMs(),
        )
        return db.queryOne("SELECT $COLS FROM places WHERE label = ? AND lat = ? AND lng = ?", label.trim(), lat, lng, map = ::mapPlace)!!
    }

    /**
     * Kataa learns a chain branch when the user confirms they are there ("I'm at Big Mart", or Done on a
     * Big Mart reminder). Skips it when a known branch of the same brand is already within [dedupeM].
     */
    fun learnBranch(brand: String, label: String, lat: Double, lng: Double, dedupeM: Double = 250.0): Place {
        nearest(lat, lng, brand = brand, limit = 1).firstOrNull()?.let { (p, d) -> if (d <= dedupeM) return p }
        return save(label, lat, lng, brand = brand, origin = PlaceOrigin.LEARNED)
    }

    /** Loads or refreshes the offline store pack. Rows are keyed by [Poi.ref]; user and learned places are untouched. */
    fun importPoiPack(pois: List<Poi>): Int = db.transaction {
        val now = clock.nowMs()
        pois.forEach { p ->
            db.execute(
                """
                INSERT INTO places(label, brand, place_type, lat, lng, radius_m, origin, poi_ref, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'POI_PACK', ?, ?)
                ON CONFLICT(poi_ref) WHERE poi_ref IS NOT NULL DO UPDATE SET
                    label = excluded.label, brand = excluded.brand, place_type = excluded.place_type,
                    lat = excluded.lat, lng = excluded.lng, radius_m = excluded.radius_m
                ON CONFLICT DO NOTHING
                """.trimIndent(),
                p.label, normalizeBrand(p.brand), p.placeType, p.lat, p.lng, p.radiusM.coerceAtLeast(MIN_RADIUS_M), p.ref, now,
            )
        }
        pois.size
    }

    /**
     * Loads the offline map of shops and places (app asset places/nepal.tsv.gz, from OpenStreetMap):
     * tab-separated ref, name, brand, kind, lat, lon. Skips it when [version] is already loaded.
     * Returns how many rows were loaded (0 when up to date).
     */
    fun importPlacesPack(version: String, lines: Sequence<String>): Int {
        if (app.kataa.core.db.Migrations.getMeta(db, PACK_KEY) == version) return 0
        val pois = lines.mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 6) return@mapNotNull null
            val lat = f[4].toDoubleOrNull() ?: return@mapNotNull null
            val lng = f[5].toDoubleOrNull() ?: return@mapNotNull null
            val type = f[3].ifBlank { null }
            Poi("osm:${f[0]}", f[1], f[2].ifBlank { f[1] }, type, lat, lng, radiusFor(type))
        }.toList()
        val n = importPoiPack(pois)
        app.kataa.core.db.Migrations.setMeta(db, PACK_KEY, version)
        return n
    }

    /** How many map places are loaded (0 before the pack is imported). */
    fun packSize(): Long = db.queryOne("SELECT count(*) FROM places WHERE origin = 'POI_PACK'") { it.long(0) } ?: 0

    fun get(id: Long): Place? = db.queryOne("SELECT $COLS FROM places WHERE id = ?", id, map = ::mapPlace)

    fun findByLabel(label: String): List<Place> =
        db.query("SELECT $COLS FROM places WHERE label = ? ORDER BY origin = 'USER' DESC, id", label.trim(), map = ::mapPlace)

    fun userPlaces(): List<Place> =
        db.query("SELECT $COLS FROM places WHERE origin <> 'POI_PACK' ORDER BY label", map = ::mapPlace)

    fun delete(id: Long) { db.execute("DELETE FROM places WHERE id = ?", id) }

    /**
     * Nearest places (optionally only one brand or type) within [maxDistanceM], nearest first.
     * Uses the R*Tree for a bounding box, then exact distance.
     */
    fun nearest(
        lat: Double,
        lng: Double,
        brand: String? = null,
        placeType: String? = null,
        maxDistanceM: Double = 20_000.0,
        limit: Int = 20,
    ): List<Pair<Place, Double>> {
        val (dLat, dLng) = boxDegrees(lat, maxDistanceM)
        return db.query(
            """
            SELECT ${COLS.split(", ").joinToString(", ") { "p.$it" }}
            FROM places_rtree t JOIN places p ON p.id = t.id
            WHERE t.min_lat >= ? AND t.max_lat <= ? AND t.min_lng >= ? AND t.max_lng <= ?
              AND (? IS NULL OR ${brandMatches("?", "p.brand", "p.place_type")}) AND (? IS NULL OR p.place_type = ?)
            """.trimIndent(),
            lat - dLat, lat + dLat, lng - dLng, lng + dLng,
            normalizeBrand(brand), normalizeBrand(brand), normalizeBrand(brand), normalizeBrand(brand), normalizeBrand(brand), placeType, placeType,
            map = ::mapPlace,
        ).map { it to distanceM(lat, lng, it.lat, it.lng) }
            .filter { it.second <= maxDistanceM }
            .sortedBy { it.second }
            .take(limit)
    }

    companion object {
        const val DEFAULT_RADIUS_M = 150.0
        const val MIN_RADIUS_M = 100.0
        internal const val COLS = "id, label, brand, place_type, lat, lng, radius_m, origin, poi_ref"
        private const val PACK_KEY = "places_pack_version"

        /** Big stores have big car parks: a wider circle so the reminder fires on arrival, not deep inside. */
        private fun radiusFor(type: String?): Double = when (type) {
            "mall", "department_store", "supermarket", "hospital", "university", "college", "marketplace", "bus_station" -> 150.0
            else -> 100.0
        }

        /**
         * SQL: does brand key [wanted] match place brand [have]? Equal, or [wanted] (4+ letters) starts [have] at a
         * shop or business, so "bigmart" matches the "Big Mart Anamnagar" branch and "bhatbhateni" matches
         * "Bhat-Bhateni Super Market", but not "Bhatbhateni Temple" or a school of that name.
         */
        fun brandMatches(wanted: String, have: String, type: String): String =
            "($have = $wanted OR (length($wanted) >= 4 AND substr($have, 1, length($wanted)) = $wanted" +
                " AND coalesce($type, '') NOT IN ($NOT_SHOPS)))"

        private const val NOT_SHOPS = "'place_of_worship','school','college','university','bus_station','police','library'," +
            "'park','stadium','hospital','clinic','doctors','dentist','post_office','marketplace','veterinary'"

        /** Match key for chains: "Big-Mart ", "BigMart" and "big mart" all become "bigmart". */
        fun normalizeBrand(brand: String?): String? =
            brand?.lowercase()?.replace(Regex("[^\\p{L}\\p{N}]+"), "")?.ifEmpty { null }

        internal fun mapPlace(r: Row) = Place(
            id = r.long(0), label = r.string(1), brand = r.stringOrNull(2), placeType = r.stringOrNull(3),
            lat = r.double(4), lng = r.double(5), radiusM = r.double(6), origin = PlaceOrigin.valueOf(r.string(7)),
            poiRef = r.stringOrNull(8),
        )

        private const val EARTH_RADIUS_M = 6_371_000.0

        /** Great-circle distance in metres. */
        fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val p1 = lat1 * PI / 180; val p2 = lat2 * PI / 180
            val dp = (lat2 - lat1) * PI / 180; val dl = (lng2 - lng1) * PI / 180
            val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }

        /** Half-size of a lat/lng box that contains a circle of [radiusM]. */
        fun boxDegrees(lat: Double, radiusM: Double): Pair<Double, Double> {
            val dLat = radiusM / 111_320.0
            val dLng = radiusM / (111_320.0 * cos(lat * PI / 180).coerceAtLeast(0.01))
            return dLat to dLng
        }
    }
}
