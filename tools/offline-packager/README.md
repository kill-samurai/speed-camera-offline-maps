# Offline region packager

This desktop tool converts an OpenStreetMap `.osm.pbf` extract into the SQLite
packages consumed by Speed Camera. It produces:

- `map`: nearby road geometry for the minimap;
- `full`: map geometry, offline place/address search, and a directed driving graph.

The Android phone downloads the prepared archive and never processes raw OSM data.
The generator accepts any region that can be described by a bounding box; country
names and filenames are not hardcoded.

## Requirements

- Python 3.11 or newer;
- enough RAM and free disk for the selected PBF and generated databases;
- an OpenStreetMap PBF extract, such as one downloaded from Geofabrik; and
- the `osmium` Python package.

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip osmium
```

## Build a region

Choose a short lowercase region ID and a bounding box in
`minLongitude,minLatitude,maxLongitude,maxLatitude` order. The PBF must contain the
whole bounding box.

```bash
python build_region.py \
  --pbf path/to/region-latest.osm.pbf \
  --output output \
  --region-id my-region \
  --region-name "My Region" \
  --bbox MIN_LON,MIN_LAT,MAX_LON,MAX_LAT \
  --version YYYY-MM-DD \
  --base-url https://github.com/OWNER/REPOSITORY/releases/download/maps-YYYY-MM-DD
```

The output directory contains the map-only and full databases, their downloadable
ZIP archives, and a ready-to-publish `catalog.json`. Generated databases, archives,
PBF files, and packager output are intentionally excluded from Git.

## Validate

Validate the schema and road count:

```bash
python validate_region.py output/my-region-full.db
```

Optionally exercise offline search and routing:

```bash
python validate_region.py output/my-region-full.db \
  --search "Destination name" \
  --origin LATITUDE,LONGITUDE \
  --weight 2
```

Use `--destination LATITUDE,LONGITUDE` instead of `--search` when desired.

## Publish and configure a fork

See [`docs/CUSTOM_OFFLINE_MAPS.md`](../../docs/CUSTOM_OFFLINE_MAPS.md) for the GitHub
Release upload, repository variable, local Android build, and fork-isolation steps.

OpenStreetMap data is licensed under ODbL. Preserve its attribution and comply with
the source provider's download policy.
