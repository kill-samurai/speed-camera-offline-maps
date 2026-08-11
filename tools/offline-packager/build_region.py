#!/usr/bin/env python3
"""Build Speed Camera offline map/routing packages from an OSM PBF extract.

The generated SQLite format is intentionally small and Android-native. It stores
road geometry for the minimap, an address/place FTS index, and a directed driving
graph. Packaging is performed on a desktop; phones only download and query it.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import shutil
import sqlite3
import struct
import time
import unicodedata
import zipfile
from pathlib import Path

import osmium


DRIVABLE_HIGHWAYS = {
    "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
    "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified",
    "residential", "living_street", "service", "road", "track",
}

DEFAULT_SPEED_KPH = {
    "motorway": 90, "motorway_link": 50, "trunk": 80, "trunk_link": 45,
    "primary": 65, "primary_link": 40, "secondary": 55, "secondary_link": 35,
    "tertiary": 45, "tertiary_link": 30, "unclassified": 35,
    "residential": 30, "living_street": 15, "service": 20, "road": 25, "track": 15,
}

BLOCKED_ACCESS = {"no", "private"}


def normalize(value: str) -> str:
    decomposed = unicodedata.normalize("NFKD", value.lower())
    return "".join(char for char in decomposed if not unicodedata.combining(char))


def parse_speed(value: str | None, highway: str) -> int:
    if not value:
        return DEFAULT_SPEED_KPH[highway]
    match = re.search(r"(\d+(?:\.\d+)?)", value)
    if not match:
        return DEFAULT_SPEED_KPH[highway]
    speed = float(match.group(1))
    if "mph" in value.lower():
        speed *= 1.609344
    return max(5, min(130, round(speed)))


def haversine_meters(first: tuple[float, float], second: tuple[float, float]) -> float:
    lat1, lon1 = map(math.radians, first)
    lat2, lon2 = map(math.radians, second)
    dlat = lat2 - lat1
    dlon = lon2 - lon1
    a = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return 6_371_000 * 2 * math.asin(math.sqrt(min(1.0, a)))


def geometry_blob(points: list[tuple[float, float]]) -> bytes:
    values: list[int] = []
    for latitude, longitude in points:
        values.extend((round(latitude * 1_000_000), round(longitude * 1_000_000)))
    return struct.pack(f"<{len(values)}i", *values)


class RegionBuilder(osmium.SimpleHandler):
    def __init__(
        self,
        database: sqlite3.Connection,
        bbox: tuple[float, float, float, float],
        junction_nodes: set[int],
        region_name: str,
    ):
        super().__init__()
        self.database = database
        self.min_lon, self.min_lat, self.max_lon, self.max_lat = bbox
        self.junction_nodes = junction_nodes
        self.region_name = region_name
        self.road_count = 0
        self.edge_count = 0
        self.place_count = 0
        self.started = time.monotonic()

    def in_region(self, latitude: float, longitude: float) -> bool:
        return self.min_lat <= latitude <= self.max_lat and self.min_lon <= longitude <= self.max_lon

    def node(self, node: osmium.osm.Node) -> None:
        if not node.location.valid() or not self.in_region(node.location.lat, node.location.lon):
            return
        tags = node.tags
        if tags.get("place") or tags.get("addr:housenumber") or tags.get("name") and (
            tags.get("amenity") or tags.get("shop") or tags.get("tourism") or tags.get("leisure")
        ):
            self.add_place(node.id, node.location.lat, node.location.lon, tags)

    def way(self, way: osmium.osm.Way) -> None:
        locations = [
            (reference.lat, reference.lon, reference.ref)
            for reference in way.nodes
            if reference.location.valid()
        ]
        if len(locations) < 2 or not any(self.in_region(lat, lon) for lat, lon, _ in locations):
            return

        highway = way.tags.get("highway")
        if highway in DRIVABLE_HIGHWAYS and self.is_accessible(way.tags):
            self.add_road(way, highway, locations)

        if way.tags.get("addr:housenumber") or way.tags.get("name") and way.tags.get("place"):
            middle = locations[len(locations) // 2]
            self.add_place(way.id, middle[0], middle[1], way.tags)

    @staticmethod
    def is_accessible(tags: osmium.osm.TagList) -> bool:
        return not any(tags.get(key) in BLOCKED_ACCESS for key in ("access", "vehicle", "motor_vehicle"))

    def add_road(self, way: osmium.osm.Way, highway: str, locations: list[tuple[float, float, int]]) -> None:
        points = [(lat, lon) for lat, lon, _ in locations]
        min_lat = min(point[0] for point in points)
        max_lat = max(point[0] for point in points)
        min_lon = min(point[1] for point in points)
        max_lon = max(point[1] for point in points)
        speed = parse_speed(way.tags.get("maxspeed"), highway)
        name = way.tags.get("name") or way.tags.get("ref") or ""
        oneway_tag = (way.tags.get("oneway") or "").lower()
        reverse_only = oneway_tag == "-1"
        one_way = reverse_only or oneway_tag in {"yes", "1", "true"} or way.tags.get("junction") == "roundabout"

        self.database.execute(
            "INSERT OR REPLACE INTO roads VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (way.id, highway, name, speed, int(one_way), min_lon, max_lon, min_lat, max_lat, geometry_blob(points)),
        )
        self.database.execute(
            "INSERT OR REPLACE INTO road_index VALUES (?, ?, ?, ?, ?)",
            (way.id, min_lon, max_lon, min_lat, max_lat),
        )
        edges = []
        graph_locations = []
        segment_start = 0
        for index in range(1, len(locations)):
            if index != len(locations) - 1 and locations[index][2] not in self.junction_nodes:
                continue
            segment = locations[segment_start:index + 1]
            first, second = segment[0], segment[-1]
            distance = sum(
                haversine_meters((a[0], a[1]), (b[0], b[1]))
                for a, b in zip(segment, segment[1:])
            )
            travel_seconds = distance / (speed / 3.6)
            forward_geometry = geometry_blob([(lat, lon) for lat, lon, _ in segment])
            if not reverse_only:
                edges.append((first[2], second[2], way.id, distance, travel_seconds, forward_geometry))
            if not one_way or reverse_only:
                reverse_geometry = geometry_blob([(lat, lon) for lat, lon, _ in reversed(segment)])
                edges.append((second[2], first[2], way.id, distance, travel_seconds, reverse_geometry))
            graph_locations.extend((first, second))
            segment_start = index
        self.database.executemany(
            "INSERT OR IGNORE INTO graph_nodes VALUES (?, ?, ?)",
            ((node_id, round(lat * 1_000_000), round(lon * 1_000_000)) for lat, lon, node_id in graph_locations),
        )
        self.database.executemany(
            "INSERT OR IGNORE INTO node_index VALUES (?, ?, ?, ?, ?)",
            ((node_id, lon, lon, lat, lat) for lat, lon, node_id in graph_locations),
        )
        self.database.executemany(
            "INSERT INTO graph_edges(from_node,to_node,road_id,distance_m,travel_seconds,geometry) "
            "VALUES (?, ?, ?, ?, ?, ?)",
            edges,
        )
        self.road_count += 1
        self.edge_count += len(edges)

        if name:
            middle = locations[len(locations) // 2]
            self.insert_place(-way.id, name, f"{name}, {self.region_name}", middle[0], middle[1], "road")

        if self.road_count % 10_000 == 0:
            elapsed = time.monotonic() - self.started
            print(f"Processed {self.road_count:,} roads and {self.edge_count:,} edges in {elapsed:.0f}s", flush=True)

    def add_place(self, osm_id: int, latitude: float, longitude: float, tags: osmium.osm.TagList) -> None:
        street = tags.get("addr:street") or ""
        number = tags.get("addr:housenumber") or ""
        name = tags.get("name") or " ".join(value for value in (street, number) if value)
        if not name:
            return
        locality = tags.get("addr:city") or tags.get("addr:place") or tags.get("addr:suburb") or ""
        display = ", ".join(value for value in (name, locality, self.region_name) if value)
        kind = tags.get("place") or tags.get("amenity") or tags.get("shop") or "address"
        self.insert_place(osm_id, name, display, latitude, longitude, kind)

    def insert_place(self, osm_id: int, name: str, display: str, latitude: float, longitude: float, kind: str) -> None:
        self.database.execute(
            "INSERT OR IGNORE INTO places(osm_id, name, normalized, display_name, latitude, longitude, kind) "
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            (osm_id, name, normalize(f"{name} {display}"), display, latitude, longitude, kind),
        )
        self.place_count += 1


class JunctionCounter(osmium.SimpleHandler):
    def __init__(self, bbox: tuple[float, float, float, float]):
        super().__init__()
        self.min_lon, self.min_lat, self.max_lon, self.max_lat = bbox
        self.counts: dict[int, int] = {}

    def way(self, way: osmium.osm.Way) -> None:
        highway = way.tags.get("highway")
        if highway not in DRIVABLE_HIGHWAYS or not RegionBuilder.is_accessible(way.tags):
            return
        locations = [node for node in way.nodes if node.location.valid()]
        if len(locations) < 2 or not any(
            self.min_lat <= node.lat <= self.max_lat and self.min_lon <= node.lon <= self.max_lon
            for node in locations
        ):
            return
        for node in locations:
            self.counts[node.ref] = min(2, self.counts.get(node.ref, 0) + 1)

    def junctions(self) -> set[int]:
        return {node_id for node_id, count in self.counts.items() if count > 1}


def create_schema(database: sqlite3.Connection) -> None:
    database.executescript(
        """
        PRAGMA journal_mode=OFF;
        PRAGMA synchronous=OFF;
        PRAGMA temp_store=MEMORY;
        PRAGMA page_size=4096;
        CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE roads(
            road_id INTEGER PRIMARY KEY,
            road_class TEXT NOT NULL,
            name TEXT NOT NULL,
            speed_kph INTEGER NOT NULL,
            oneway INTEGER NOT NULL,
            min_lon REAL NOT NULL,
            max_lon REAL NOT NULL,
            min_lat REAL NOT NULL,
            max_lat REAL NOT NULL,
            geometry BLOB NOT NULL
        );
        CREATE VIRTUAL TABLE road_index USING rtree(road_id, min_lon, max_lon, min_lat, max_lat);
        CREATE TABLE graph_nodes(node_id INTEGER PRIMARY KEY, latitude_e6 INTEGER NOT NULL, longitude_e6 INTEGER NOT NULL);
        CREATE VIRTUAL TABLE node_index USING rtree(node_id, min_lon, max_lon, min_lat, max_lat);
        CREATE TABLE graph_edges(
            edge_id INTEGER PRIMARY KEY,
            from_node INTEGER NOT NULL,
            to_node INTEGER NOT NULL,
            road_id INTEGER NOT NULL,
            distance_m REAL NOT NULL,
            travel_seconds REAL NOT NULL,
            geometry BLOB NOT NULL
        );
        CREATE TABLE places(
            place_id INTEGER PRIMARY KEY,
            osm_id INTEGER NOT NULL,
            name TEXT NOT NULL,
            normalized TEXT NOT NULL,
            display_name TEXT NOT NULL,
            latitude REAL NOT NULL,
            longitude REAL NOT NULL,
            kind TEXT NOT NULL,
            UNIQUE(osm_id, name)
        );
        """
    )


def finalize_database(database: sqlite3.Connection, metadata: dict[str, object]) -> None:
    database.executemany(
        "INSERT INTO metadata(key, value) VALUES (?, ?)",
        ((key, json.dumps(value, ensure_ascii=False)) for key, value in metadata.items()),
    )
    database.executescript(
        """
        CREATE INDEX graph_edges_from ON graph_edges(from_node);
        CREATE INDEX graph_edges_road ON graph_edges(road_id);
        CREATE INDEX places_osm_id ON places(osm_id);
        CREATE VIRTUAL TABLE place_search USING fts4(name, normalized, display_name, tokenize=unicode61);
        INSERT INTO place_search(rowid, name, normalized, display_name)
            SELECT place_id, name, normalized, display_name FROM places;
        ANALYZE;
        VACUUM;
        """
    )


def create_map_only(full_database: Path, map_database: Path) -> None:
    shutil.copy2(full_database, map_database)
    database = sqlite3.connect(map_database)
    database.executescript(
        """
        DROP TABLE IF EXISTS graph_edges;
        DROP TABLE IF EXISTS graph_nodes;
        DROP TABLE IF EXISTS node_index;
        DROP TABLE IF EXISTS place_search;
        DELETE FROM metadata WHERE key = 'capabilities';
        INSERT INTO metadata(key, value) VALUES ('capabilities', '["map"]');
        VACUUM;
        """
    )
    database.close()


def zip_database(database_path: Path, output_path: Path, metadata: dict[str, object]) -> None:
    package_metadata = output_path.with_suffix(".metadata.json")
    package_metadata.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n")
    with zipfile.ZipFile(output_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        archive.write(database_path, "region.db")
        archive.write(package_metadata, "package.json")
    package_metadata.unlink()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pbf", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--region-id", required=True, help="Short lowercase ID, for example do or us-fl")
    parser.add_argument("--region-name", required=True, help="Human-readable name shown in the app")
    parser.add_argument("--base-url", default="")
    parser.add_argument("--bbox", required=True, help="minLon,minLat,maxLon,maxLat")
    args = parser.parse_args()

    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,31}", args.region_id):
        parser.error("--region-id must contain lowercase letters, numbers, or hyphens")
    try:
        bbox = tuple(float(value) for value in args.bbox.split(","))
    except ValueError:
        parser.error("--bbox must be minLon,minLat,maxLon,maxLat")
    if len(bbox) != 4:
        parser.error("--bbox must be minLon,minLat,maxLon,maxLat")
    min_lon, min_lat, max_lon, max_lat = bbox
    if not (-180 <= min_lon < max_lon <= 180 and -90 <= min_lat < max_lat <= 90):
        parser.error("--bbox coordinates are invalid or not ordered min-to-max")
    args.output.mkdir(parents=True, exist_ok=True)
    full_database = args.output / f"{args.region_id}-full.db"
    map_database = args.output / f"{args.region_id}-map.db"
    for path in (full_database, map_database):
        if path.exists():
            path.unlink()

    metadata = {
        "formatVersion": 1,
        "regionId": args.region_id,
        "name": args.region_name,
        "version": args.version,
        "bbox": bbox,
        "source": "© OpenStreetMap contributors · Geofabrik extract",
        "capabilities": ["map", "search", "routing"],
    }
    database = sqlite3.connect(full_database)
    create_schema(database)
    print("Finding real road intersections…", flush=True)
    junction_counter = JunctionCounter(bbox)
    junction_counter.apply_file(str(args.pbf), locations=True, idx="flex_mem")
    junction_nodes = junction_counter.junctions()
    print(f"Found {len(junction_nodes):,} shared road nodes", flush=True)
    del junction_counter

    builder = RegionBuilder(database, bbox, junction_nodes, args.region_name)
    database.execute("BEGIN")
    builder.apply_file(str(args.pbf), locations=True, idx="flex_mem")
    database.commit()
    metadata.update({"roadCount": builder.road_count, "edgeCount": builder.edge_count, "placeCount": builder.place_count})
    finalize_database(database, metadata)
    database.close()

    create_map_only(full_database, map_database)
    packages = []
    for package_type, database_path, capabilities in (
        ("map", map_database, ["map"]),
        ("full", full_database, ["map", "search", "routing"]),
    ):
        filename = f"{args.region_id}-{package_type}-{args.version}.zip"
        archive_path = args.output / filename
        package_metadata = dict(metadata, capabilities=capabilities, installedBytes=database_path.stat().st_size)
        zip_database(database_path, archive_path, package_metadata)
        packages.append(
            {
                "type": package_type,
                "capabilities": capabilities,
                "downloadBytes": archive_path.stat().st_size,
                "installedBytes": database_path.stat().st_size,
                "sha256": sha256(archive_path),
                "url": f"{args.base_url.rstrip('/')}/{filename}" if args.base_url else filename,
            }
        )

    catalog = {
        "formatVersion": 1,
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "regions": [{
            "id": args.region_id,
            "name": args.region_name,
            "version": args.version,
            "bbox": bbox,
            "packages": packages,
        }],
    }
    (args.output / "catalog.json").write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(catalog, indent=2))


if __name__ == "__main__":
    main()
