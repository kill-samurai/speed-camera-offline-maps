package com.example.speedcamera

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos

data class OfflineRoad(
    val roadClass: String,
    val name: String,
    val points: List<GeoPoint>
)

class OfflineRegionStore(private val manager: OfflineRegionManager) {
    private data class RoadCache(
        val center: GeoPoint,
        val radiusMeters: Double,
        val roads: List<OfflineRoad>
    )

    private val mapExecutor = Executors.newSingleThreadExecutor()
    private val refreshPending = AtomicBoolean(false)
    private val databaseLock = Any()
    private var database: SQLiteDatabase? = null

    @Volatile
    private var capabilities: Set<String> = emptySet()
    @Volatile
    private var roadCache: RoadCache? = null

    init {
        refresh()
    }

    fun hasMap(): Boolean = "map" in capabilities
    fun hasSearch(): Boolean = "search" in capabilities
    fun hasRouting(): Boolean = "routing" in capabilities

    fun refresh() {
        mapExecutor.execute {
            synchronized(databaseLock) {
                database?.close()
                database = null
                capabilities = emptySet()
                roadCache = null
                val installed = manager.installedRegion() ?: return@synchronized
                runCatching {
                    SQLiteDatabase.openDatabase(
                        installed.databasePath,
                        null,
                        SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
                    )
                }.onSuccess { opened ->
                    database = opened
                    capabilities = readCapabilities(opened)
                }.onFailure { Log.e(LOG_TAG, "Could not open offline region database", it) }
            }
        }
    }

    fun closeRegion() {
        synchronized(databaseLock) {
            database?.close()
            database = null
            capabilities = emptySet()
            roadCache = null
        }
    }

    fun close() {
        closeRegion()
        mapExecutor.shutdownNow()
    }

    fun roadsNear(center: GeoPoint, requestedRadiusMeters: Double): List<OfflineRoad> {
        val cached = roadCache
        if (cached != null &&
            cached.radiusMeters >= requestedRadiusMeters &&
            RouteProgress.distanceMeters(cached.center, center) < cached.radiusMeters * 0.35
        ) {
            return cached.roads
        }
        if (hasMap() && refreshPending.compareAndSet(false, true)) {
            val cacheRadius = requestedRadiusMeters.coerceAtLeast(2_200.0)
            mapExecutor.execute {
                try {
                    roadCache = RoadCache(center, cacheRadius, queryRoads(center, cacheRadius))
                } catch (error: Exception) {
                    Log.e(LOG_TAG, "Could not read offline street geometry", error)
                } finally {
                    refreshPending.set(false)
                }
            }
        }
        return cached?.roads.orEmpty()
    }

    fun search(query: String, limit: Int = 5): List<AddressSuggestion> {
        if (!hasSearch()) return emptyList()
        val match = normalizedTokens(query).joinToString(" ") { "$it*" }
        if (match.isBlank()) return emptyList()
        val sql = """
            SELECT p.display_name, p.latitude, p.longitude
            FROM place_search
            JOIN places p ON p.place_id = place_search.rowid
            WHERE place_search MATCH ?
            LIMIT ?
        """.trimIndent()
        return synchronized(databaseLock) {
            val active = database ?: return@synchronized emptyList()
            active.rawQuery(sql, arrayOf(match, limit.toString())).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            AddressSuggestion(
                                label = cursor.getString(0),
                                point = GeoPoint(cursor.getDouble(1), cursor.getDouble(2))
                            )
                        )
                    }
                }
            }
        }
    }

    fun databaseForRouting(): SQLiteDatabase? = synchronized(databaseLock) {
        database?.takeIf { hasRouting() }
    }

    private fun queryRoads(center: GeoPoint, radiusMeters: Double): List<OfflineRoad> {
        val latitudeRadius = radiusMeters / 110_540.0
        val longitudeRadius = radiusMeters /
            (111_320.0 * cos(Math.toRadians(center.latitude)).coerceAtLeast(0.1))
        val arguments = arrayOf(
            (center.longitude + longitudeRadius).toString(),
            (center.longitude - longitudeRadius).toString(),
            (center.latitude + latitudeRadius).toString(),
            (center.latitude - latitudeRadius).toString()
        )
        val sql = """
            SELECT r.road_class, r.name, r.geometry
            FROM road_index i
            JOIN roads r ON r.road_id = i.road_id
            WHERE i.min_lon <= ? AND i.max_lon >= ? AND i.min_lat <= ? AND i.max_lat >= ?
            LIMIT 6000
        """.trimIndent()
        return synchronized(databaseLock) {
            val active = database ?: return@synchronized emptyList()
            active.rawQuery(sql, arguments).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            OfflineRoad(
                                roadClass = cursor.getString(0),
                                name = cursor.getString(1),
                                points = decodeGeometry(cursor.getBlob(2))
                            )
                        )
                    }
                }
            }
        }
    }

    private fun readCapabilities(database: SQLiteDatabase): Set<String> {
        return database.rawQuery("SELECT value FROM metadata WHERE key='capabilities'", null).use { cursor ->
            if (!cursor.moveToFirst()) return emptySet()
            cursor.getString(0)
                .removePrefix("[")
                .removeSuffix("]")
                .split(',')
                .map { it.trim().removeSurrounding("\"") }
                .filter(String::isNotBlank)
                .toSet()
        }
    }

    private fun decodeGeometry(blob: ByteArray): List<GeoPoint> {
        val values = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        return buildList(values.remaining() / 2) {
            while (values.remaining() >= 2) {
                add(GeoPoint(values.get() / 1_000_000.0, values.get() / 1_000_000.0))
            }
        }
    }

    private fun normalizedTokens(query: String): List<String> {
        val normalized = Normalizer.normalize(query.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return Regex("[a-z0-9]+").findAll(normalized).map { it.value }.toList()
    }

    companion object {
        private const val LOG_TAG = "SpeedCamera"
    }
}
