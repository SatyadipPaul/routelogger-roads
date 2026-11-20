# Route Logger road packs

Compact road data for India, rebuilt weekly from OpenStreetMap. The Route Logger Android app uses
it for on-device routing, and for speed limits and road signals when Overpass is unavailable.

## Download

Each weekly build is a release named `packs-YYYY-MM-DD`. The newest is always at:

| File | Contents |
|---|---|
| [`local.rpk`](../../releases/latest/download/local.rpk) | every drivable road, in zoom-10 tiles (about 37 km) |
| [`main.rpk`](../../releases/latest/download/main.rpk) | motorway, trunk, primary and secondary roads, in zoom-7 tiles (about 300 km) |
| `build.log` | what the build read and the largest tiles |

These are single archives with an index at the front. Clients read the index with one HTTP range
request, then fetch only the tiles they need. **Pin one release per session:** resolve
`releases/latest` to its tag first, then read the index and tiles from that tag's URLs, so a new
weekly build can't change the offsets midway.

The format is described in [roadpack/FORMAT.md](roadpack/FORMAT.md).

## How it is built

[`.github/workflows/build-packs.yml`](.github/workflows/build-packs.yml) runs every Monday, and on
demand:
1. Downloads `india-latest.osm.pbf` from [Geofabrik](https://download.geofabrik.de/asia/india.html).
2. Runs the Kotlin tool in [`roadpack/`](roadpack).
3. Publishes a new release, keeping the newest three.

To build locally:

```
./gradlew -p roadpack installDist test
roadpack/build/install/roadpack/bin/roadpack india-latest.osm.pbf out
```

## Licence

The packs are a derived database of OpenStreetMap data, © OpenStreetMap contributors, and are
available under the [Open Database License (ODbL) 1.0](https://opendatacommons.org/licenses/odbl/1-0/).
Any use must credit "© OpenStreetMap contributors" and keep derived databases under the ODbL.
