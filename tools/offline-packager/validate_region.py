#!/usr/bin/env python3
"""Smoke-test search and a driving route in a generated offline region database."""

import argparse
import heapq
import json
import math
import re
import sqlite3
import time


def distance(a, b):
    lat1, lon1 = map(math.radians, a)
    lat2, lon2 = map(math.radians, b)
    dlat, dlon = lat2 - lat1, lon2 - lon1
    value = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return 6_371_000 * 2 * math.asin(math.sqrt(min(1, value)))


def nearest(db, point):
    lat, lon = point
    for radius in (0.002, 0.01, 0.05, 0.2):
        row = db.execute(
            """
            SELECT n.node_id,n.latitude_e6,n.longitude_e6 FROM node_index i
            JOIN graph_nodes n ON n.node_id=i.node_id
            WHERE i.min_lon<=? AND i.max_lon>=? AND i.min_lat<=? AND i.max_lat>=?
            ORDER BY ((n.latitude_e6/1000000.0-?)*(n.latitude_e6/1000000.0-?))+
                     ((n.longitude_e6/1000000.0-?)*(n.longitude_e6/1000000.0-?)) LIMIT 1
            """,
            (lon + radius, lon - radius, lat + radius, lat - radius, lat, lat, lon, lon),
        ).fetchone()
        if row:
            return row[0], (row[1] / 1e6, row[2] / 1e6)
    raise RuntimeError("No nearby road node")


def route(db, origin, destination, heuristic_weight):
    start, start_point = nearest(db, origin)
    target, target_point = nearest(db, destination)
    queue = [(distance(start_point, target_point) / 36.111 * heuristic_weight, start)]
    costs = {start: 0.0}
    previous = {}
    points = {start: start_point, target: target_point}
    visited = 0
    while queue:
        estimate, node = heapq.heappop(queue)
        cost = costs[node]
        if estimate > cost + distance(points[node], target_point) / 36.111 * heuristic_weight + 0.001:
            continue
        if node == target:
            break
        visited += 1
        for next_node, road, edge_distance, seconds, lat, lon in db.execute(
            """SELECT e.to_node,e.road_id,e.distance_m,e.travel_seconds,n.latitude_e6,n.longitude_e6
               FROM graph_edges e JOIN graph_nodes n ON n.node_id=e.to_node WHERE e.from_node=?""",
            (node,),
        ):
            next_cost = cost + seconds
            if next_cost >= costs.get(next_node, float("inf")):
                continue
            point = (lat / 1e6, lon / 1e6)
            points[next_node] = point
            costs[next_node] = next_cost
            previous[next_node] = (node, road, edge_distance)
            heapq.heappush(
                queue,
                (next_cost + distance(point, target_point) / 36.111 * heuristic_weight, next_node),
            )
    if target not in previous:
        raise RuntimeError("No route found")
    route_distance, count, node = 0.0, 1, target
    while node != start:
        node, _, edge_distance = previous[node]
        route_distance += edge_distance
        count += 1
    return visited, count, route_distance, costs[target]


def parse_point(value):
    try:
        latitude, longitude = (float(part.strip()) for part in value.split(","))
    except (ValueError, TypeError):
        raise argparse.ArgumentTypeError("point must be latitude,longitude")
    if not (-90 <= latitude <= 90 and -180 <= longitude <= 180):
        raise argparse.ArgumentTypeError("point is outside valid latitude/longitude ranges")
    return latitude, longitude


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("database")
    parser.add_argument("--weight", type=float, default=1.0)
    parser.add_argument("--search", help="Place or address text expected in the package")
    parser.add_argument("--origin", type=parse_point, help="Route start as latitude,longitude")
    parser.add_argument("--destination", type=parse_point, help="Route end as latitude,longitude")
    args = parser.parse_args()
    db = sqlite3.connect(f"file:{args.database}?mode=ro", uri=True)
    metadata = dict(db.execute("SELECT key,value FROM metadata"))
    roads = db.execute("SELECT COUNT(*) FROM roads").fetchone()[0]
    region_name = json.loads(metadata["name"]) if "name" in metadata else "unknown"
    print(f"Region: {region_name}\nRoads: {roads:,}")

    search_result = None
    if args.search:
        terms = [term for term in re.findall(r"[^\W_]+", args.search.lower(), re.UNICODE) if term]
        if not terms:
            parser.error("--search must contain letters or numbers")
        query = " ".join(f"{term}*" for term in terms)
        search_result = db.execute(
            """SELECT p.display_name,p.latitude,p.longitude FROM place_search
               JOIN places p ON p.place_id=place_search.rowid
               WHERE place_search MATCH ? LIMIT 1""",
            (query,),
        ).fetchone()
        if not search_result:
            raise RuntimeError(f"Search did not find: {args.search}")
        print(f"Search: {search_result[0]} ({search_result[1]:.6f},{search_result[2]:.6f})")

    destination = args.destination
    if args.origin and destination is None and search_result:
        destination = search_result[1], search_result[2]
    if bool(args.origin) != bool(destination):
        parser.error("routing requires --origin and either --destination or a successful --search")
    if args.origin and destination:
        started = time.monotonic()
        visited, points, meters, seconds = route(db, args.origin, destination, args.weight)
        print(
            f"Route: {meters/1000:.1f} km, {seconds/3600:.1f} h, "
            f"{points:,} points, {visited:,} visited nodes, {time.monotonic()-started:.1f}s"
        )
    db.close()


if __name__ == "__main__":
    main()
