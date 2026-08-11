# Offline region packager

This desktop-only tool converts a Geofabrik OpenStreetMap PBF extract into the
SQLite packages consumed by Speed Camera. It produces two options:

- `map`: nearby road geometry for the minimap;
- `full`: map geometry, offline place/address search, and a directed driving graph.

The Android phone downloads the prepared archive and never processes raw OSM data.

## Build Dominican Republic packages

Create a Python 3.13 virtual environment and install `osmium`, then run:

```bash
python build_region.py \
  --pbf haiti-and-domrep-latest.osm.pbf \
  --output output \
  --version YYYY-MM-DD \
  --base-url https://github.com/OWNER/REPOSITORY/releases/download/maps-YYYY-MM-DD
```

The default bounding box keeps Dominican Republic data from the shared Haiti and
Dominican Republic extract. Generated databases and archives should be uploaded as
GitHub Release assets, not committed to Git.

OpenStreetMap data is licensed under ODbL. Keep the attribution and source metadata
visible in the app and in the package repository.
