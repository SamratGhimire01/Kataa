package app.kataa.core.places

import app.kataa.core.db.SqlDriver
import app.kataa.core.model.Place
import app.kataa.core.util.TimeSource

/** What the platform layer should register with the OS geofencing API. */
data class GeofencePlan(
    /** Geofences to add (request id -> place). */
    val add: List<Fence>,
    /** Request ids to remove. */
    val remove: List<String>,
    /** All fences that will be active after applying the plan, nearest first. */
    val active: List<Fence>,
)

data class Fence(val requestId: String, val placeId: Long?, val lat: Double, val lng: Double, val radiusM: Double)

/**
 * Android allows 100 geofences per app; a chain like Big Mart can have 150+ branches.
 * So Kataa watches only the nearest matching places (at most [maxPlaceFences]) plus one large
 * "refresh" circle around the device. Leaving that circle triggers [plan] again from the new position.
 */
class GeofencePlanner(
    private val db: SqlDriver,
    private val clock: TimeSource,
    private val maxPlaceFences: Int = 99,
    private val searchRadiusM: Double = 15_000.0,
    private val minRefreshRadiusM: Double = 1_000.0,
    private val maxRefreshRadiusM: Double = 10_000.0,
) {
    fun plan(lat: Double, lng: Double): GeofencePlan = db.transaction {
        val targets = nearestTargets(lat, lng)
        val fences = targets.map { (p, _) -> Fence("place:${p.id}", p.id, p.lat, p.lng, p.radiusM) }.toMutableList()
        if (fences.isNotEmpty()) {
            // Refresh when the device moves roughly halfway to the farthest watched place, so that
            // nearer, unwatched branches are picked up in time.
            val farthest = targets.last().second
            val r = (farthest / 2).coerceIn(minRefreshRadiusM, maxRefreshRadiusM)
            fences += Fence(REFRESH_ID, null, lat, lng, r)
        }
        val current = db.query("SELECT request_id, place_id, lat, lng, radius_m FROM active_geofences") {
            Fence(it.string(0), it.longOrNull(1), it.double(2), it.double(3), it.double(4))
        }.associateBy { it.requestId }
        val wanted = fences.associateBy { it.requestId }
        val add = fences.filter { current[it.requestId] != it }
        val remove = current.keys.filter { it !in wanted || current[it] != wanted[it] }

        db.execute("DELETE FROM active_geofences")
        val now = clock.nowMs()
        fences.forEach {
            db.execute(
                "INSERT INTO active_geofences(request_id, place_id, lat, lng, radius_m, registered_at) VALUES (?, ?, ?, ?, ?, ?)",
                it.requestId, it.placeId, it.lat, it.lng, it.radiusM, now,
            )
        }
        GeofencePlan(add = add, remove = remove, active = fences)
    }

    /** Places that satisfy at least one pending place reminder, nearest first. */
    fun nearestTargets(lat: Double, lng: Double): List<Pair<Place, Double>> {
        val (dLat, dLng) = PlaceRepository.boxDegrees(lat, searchRadiusM)
        return db.query(
            """
            SELECT ${PlaceRepository.COLS.split(", ").joinToString(", ") { "p.$it" }}
            FROM places_rtree t JOIN places p ON p.id = t.id
            WHERE t.min_lat >= ? AND t.max_lat <= ? AND t.min_lng >= ? AND t.max_lng <= ?
              AND EXISTS (
                SELECT 1 FROM reminders r JOIN memories m ON m.id = r.memory_id AND m.deleted_at IS NULL
                WHERE r.type IN ('PLACE_ENTER','PLACE_EXIT') AND r.status IN ('SCHEDULED','SNOOZED')
                  AND (r.place_id = p.id
                       OR (r.place_brand IS NOT NULL AND ${PlaceRepository.brandMatches("r.place_brand", "p.brand", "p.place_type")})
                       OR (r.place_type IS NOT NULL AND r.place_type = p.place_type)))
            """.trimIndent(),
            lat - dLat, lat + dLat, lng - dLng, lng + dLng,
            map = PlaceRepository::mapPlace,
        ).map { it to PlaceRepository.distanceM(lat, lng, it.lat, it.lng) }
            .filter { it.second <= searchRadiusM }
            .sortedBy { it.second }
            .take(maxPlaceFences)
    }

    companion object {
        const val REFRESH_ID = "refresh"
    }
}
