package com.example.speedcamera

import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue
import kotlin.math.abs

class OfflineRoutingClient(private val store: OfflineRegionStore) {
    private data class QueueEntry(val nodeId: Long, val estimatedTotal: Double)
    private data class Previous(
        val nodeId: Long,
        val edgeId: Long,
        val roadId: Long,
        val edgeDistance: Double
    )

    fun route(origin: GeoPoint, destination: GeoPoint, destinationLabel: String): RouteData {
        val database = store.databaseForRouting() ?: error("No full offline routing package is installed")
        val started = SystemClock.elapsedRealtime()
        val startNode = nearestNode(database, origin)
        val targetNode = nearestNode(database, destination)
        val coordinates = HashMap<Long, GeoPoint>()
        coordinates[startNode.first] = startNode.second
        coordinates[targetNode.first] = targetNode.second

        val queue = PriorityQueue(compareBy<QueueEntry> { it.estimatedTotal })
        val costs = HashMap<Long, Double>()
        val previous = HashMap<Long, Previous>()
        costs[startNode.first] = 0.0
        queue += QueueEntry(startNode.first, heuristicSeconds(startNode.second, targetNode.second))
        var visited = 0

        while (queue.isNotEmpty()) {
            if (Thread.currentThread().isInterrupted) error("Offline route calculation was cancelled")
            val current = queue.remove()
            val currentCost = costs[current.nodeId] ?: continue
            val currentPoint = coordinates[current.nodeId] ?: nodePoint(database, current.nodeId).also {
                coordinates[current.nodeId] = it
            }
            val expected = currentCost + heuristicSeconds(currentPoint, targetNode.second)
            if (current.estimatedTotal > expected + 0.001) continue
            if (current.nodeId == targetNode.first) break
            visited += 1
            if (visited > MAX_VISITED_NODES) error("Offline route is too complex for this region package")

            database.rawQuery(
                """
                    SELECT e.edge_id, e.to_node, e.road_id, e.distance_m, e.travel_seconds,
                           n.latitude_e6, n.longitude_e6
                    FROM graph_edges e
                    JOIN graph_nodes n ON n.node_id = e.to_node
                    WHERE e.from_node = ?
                """.trimIndent(),
                arrayOf(current.nodeId.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val edgeId = cursor.getLong(0)
                    val nextNode = cursor.getLong(1)
                    val roadId = cursor.getLong(2)
                    val edgeDistance = cursor.getDouble(3)
                    val nextCost = currentCost + cursor.getDouble(4)
                    if (nextCost >= costs.getOrDefault(nextNode, Double.MAX_VALUE)) continue
                    val point = GeoPoint(cursor.getInt(5) / 1_000_000.0, cursor.getInt(6) / 1_000_000.0)
                    coordinates[nextNode] = point
                    costs[nextNode] = nextCost
                    previous[nextNode] = Previous(current.nodeId, edgeId, roadId, edgeDistance)
                    queue += QueueEntry(nextNode, nextCost + heuristicSeconds(point, targetNode.second))
                }
            }
        }

        if (targetNode.first !in previous && targetNode.first != startNode.first) {
            error("No offline driving route was found")
        }

        val pathEdges = mutableListOf<Previous>()
        var cursor = targetNode.first
        while (cursor != startNode.first) {
            val edge = previous[cursor] ?: error("Offline route reconstruction failed")
            pathEdges += edge
            cursor = edge.nodeId
        }
        pathEdges.reverse()
        val geometries = loadEdgeGeometries(database, pathEdges.map { it.edgeId })
        val points = mutableListOf<GeoPoint>()
        val roadIds = mutableListOf<Long>()
        pathEdges.forEach { edge ->
            val geometry = geometries[edge.edgeId] ?: error("Offline route edge geometry is missing")
            if (points.isEmpty()) points.addAll(geometry) else points.addAll(geometry.drop(1))
            repeat((geometry.size - 1).coerceAtLeast(0)) { roadIds += edge.roadId }
        }
        require(points.size >= 2) { "Offline route did not contain enough points" }

        val cumulative = ArrayList<Double>(points.size).apply {
            add(0.0)
            for (index in 1 until points.size) {
                add(last() + RouteProgress.distanceMeters(points[index - 1], points[index]))
            }
        }
        val roadNames = loadRoadNames(database, roadIds.toSet())
        val steps = buildSteps(points, roadIds, roadNames)
        val elapsed = SystemClock.elapsedRealtime() - started
        Log.i(LOG_TAG, "Offline route calculated through $visited nodes in ${elapsed}ms")

        return RouteData(
            points = points,
            steps = steps,
            cumulativeMeters = cumulative,
            totalDistanceMeters = cumulative.last(),
            totalDurationSeconds = costs[targetNode.first] ?: 0.0,
            destination = destination,
            destinationLabel = destinationLabel
        )
    }

    private fun nearestNode(database: SQLiteDatabase, point: GeoPoint): Pair<Long, GeoPoint> {
        for (radius in NEAREST_NODE_RADII) {
            val longitudeRadius = radius
            val latitudeRadius = radius
            database.rawQuery(
                """
                    SELECT n.node_id, n.latitude_e6, n.longitude_e6
                    FROM node_index i
                    JOIN graph_nodes n ON n.node_id = i.node_id
                    WHERE i.min_lon <= ? AND i.max_lon >= ? AND i.min_lat <= ? AND i.max_lat >= ?
                    ORDER BY ((n.latitude_e6 / 1000000.0 - ?) * (n.latitude_e6 / 1000000.0 - ?)) +
                             ((n.longitude_e6 / 1000000.0 - ?) * (n.longitude_e6 / 1000000.0 - ?))
                    LIMIT 1
                """.trimIndent(),
                arrayOf(
                    (point.longitude + longitudeRadius).toString(),
                    (point.longitude - longitudeRadius).toString(),
                    (point.latitude + latitudeRadius).toString(),
                    (point.latitude - latitudeRadius).toString(),
                    point.latitude.toString(),
                    point.latitude.toString(),
                    point.longitude.toString(),
                    point.longitude.toString()
                )
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getLong(0) to GeoPoint(
                        cursor.getInt(1) / 1_000_000.0,
                        cursor.getInt(2) / 1_000_000.0
                    )
                }
            }
        }
        error("No offline road is near this location")
    }

    private fun nodePoint(database: SQLiteDatabase, nodeId: Long): GeoPoint {
        return database.rawQuery(
            "SELECT latitude_e6, longitude_e6 FROM graph_nodes WHERE node_id=?",
            arrayOf(nodeId.toString())
        ).use { cursor ->
            require(cursor.moveToFirst()) { "Offline route node is missing" }
            GeoPoint(cursor.getInt(0) / 1_000_000.0, cursor.getInt(1) / 1_000_000.0)
        }
    }

    private fun loadRoadNames(database: SQLiteDatabase, roadIds: Set<Long>): Map<Long, String> {
        if (roadIds.isEmpty()) return emptyMap()
        val result = mutableMapOf<Long, String>()
        roadIds.chunked(SQLITE_PARAMETER_LIMIT).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            database.rawQuery(
                "SELECT road_id, name FROM roads WHERE road_id IN ($placeholders)",
                chunk.map(Long::toString).toTypedArray()
            ).use { cursor ->
                while (cursor.moveToNext()) result[cursor.getLong(0)] = cursor.getString(1)
            }
        }
        return result
    }

    private fun loadEdgeGeometries(
        database: SQLiteDatabase,
        edgeIds: List<Long>
    ): Map<Long, List<GeoPoint>> {
        val result = mutableMapOf<Long, List<GeoPoint>>()
        edgeIds.chunked(SQLITE_PARAMETER_LIMIT).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            database.rawQuery(
                "SELECT edge_id, geometry FROM graph_edges WHERE edge_id IN ($placeholders)",
                chunk.map(Long::toString).toTypedArray()
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result[cursor.getLong(0)] = decodeGeometry(cursor.getBlob(1))
                }
            }
        }
        return result
    }

    private fun decodeGeometry(blob: ByteArray): List<GeoPoint> {
        val values = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        return buildList(values.remaining() / 2) {
            while (values.remaining() >= 2) {
                add(GeoPoint(values.get() / 1_000_000.0, values.get() / 1_000_000.0))
            }
        }
    }

    private fun buildSteps(
        points: List<GeoPoint>,
        roadIds: List<Long>,
        roadNames: Map<Long, String>
    ): List<RouteStep> {
        val steps = mutableListOf<RouteStep>()
        var previousRoadId: Long? = null
        roadIds.forEachIndexed { edgeIndex, roadId ->
            if (roadId == previousRoadId) return@forEachIndexed
            val routeIndex = edgeIndex.coerceIn(points.indices)
            val roadName = roadNames[roadId].orEmpty()
            val instruction = when {
                previousRoadId == null -> if (roadName.isBlank()) "Start route" else "Start on $roadName"
                routeIndex == 0 || routeIndex + 1 >= points.size ->
                    if (roadName.isBlank()) "Continue route" else "Continue on $roadName"
                else -> turnInstruction(points[routeIndex - 1], points[routeIndex], points[routeIndex + 1], roadName)
            }
            steps += RouteStep(points[routeIndex], instruction, routeIndex)
            previousRoadId = roadId
        }
        steps += RouteStep(points.last(), "Arrive at destination", points.lastIndex)
        return steps
    }

    private fun turnInstruction(before: GeoPoint, turn: GeoPoint, after: GeoPoint, roadName: String): String {
        val incoming = bearing(before, turn)
        val outgoing = bearing(turn, after)
        val delta = ((outgoing - incoming + 540f) % 360f) - 180f
        val road = roadName.takeIf(String::isNotBlank)?.let { " onto $it" }.orEmpty()
        return when {
            abs(delta) < 25f -> if (roadName.isBlank()) "Continue route" else "Continue on $roadName"
            delta > 0 -> "Turn right$road"
            else -> "Turn left$road"
        }
    }

    private fun bearing(from: GeoPoint, to: GeoPoint): Float {
        val results = FloatArray(3)
        android.location.Location.distanceBetween(
            from.latitude,
            from.longitude,
            to.latitude,
            to.longitude,
            results
        )
        return results[1]
    }

    private fun heuristicSeconds(from: GeoPoint, to: GeoPoint): Double =
        RouteProgress.distanceMeters(from, to) / MAX_ROUTE_SPEED_METERS_PER_SECOND * HEURISTIC_WEIGHT

    companion object {
        private const val LOG_TAG = "SpeedCamera"
        private const val MAX_VISITED_NODES = 750_000
        private const val MAX_ROUTE_SPEED_METERS_PER_SECOND = 36.111
        private const val HEURISTIC_WEIGHT = 2.0
        private const val SQLITE_PARAMETER_LIMIT = 900
        private val NEAREST_NODE_RADII = doubleArrayOf(0.002, 0.01, 0.05, 0.2)
    }
}
