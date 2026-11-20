# Road pack format, version 1

Built from an OpenStreetMap `.pbf` extract by the tool in this folder. One format serves both on-device
routing and speed limits and road signals. The code that writes and reads it is
`src/main/kotlin/roadpack/format/Pack.kt`.

## Running

From the repository root:

```
./gradlew -p roadpack installDist test
roadpack/build/install/roadpack/bin/roadpack <extract.osm.pbf> <out dir>                      # writes local.rpk, main.rpk
java -cp "roadpack/build/install/roadpack/lib/*" roadpack.bench.BenchKt <out dir>/local.rpk    # route timing
./gradlew -p roadpack benchJar     # the same benchmark as one jar, to dex with d8 and run on a phone with app_process
```

This is a standalone Gradle project. It is not part of the app build.

## Levels

| File | Level | Zoom | Roads |
|---|---|---|---|
| `local.rpk` | 0 | 10 (about 37 km) | every drivable road: motorway to service, without driveways or parking aisles |
| `main.rpk` | 1 | 7 (about 300 km) | motorway, trunk, primary and secondary, with their links |

## Archive (one file per level)

Big-endian fixed header, then a fixed-size index sorted by x, then y, then the tiles:

| Bytes | Field |
|---|---|
| 4 | `RPK1` |
| 1 | version (1) |
| 1 | level |
| 1 | zoom |
| 1 | reserved |
| 8 | build time, ms since epoch |
| 4 | tile count *n* |
| 20 × *n* | index: i32 x, i32 y, i64 offset from file start, i32 length |
| … | gzipped tiles |

A client reads `20 + 20n` bytes with one HTTP range request, then fetches each tile it needs by
offset and length. Checked 2026-11-20: GitHub release downloads answer range requests with
`206 Partial Content`, after a redirect to `release-assets.githubusercontent.com`.

## Tile (after gunzip)

Integers are LEB128 varints. "s" means zigzag-signed. Coordinates are micro-degrees (1e-6°, about
0.1 m) and lengths are decimetres.

1. **Nodes.** Count, then (s Δlat, s Δlon) from the previous node, in Morton (Z-order) order.
   - Nodes are junctions, dead ends, the via nodes of turn restrictions, and *cut points* where a
     road crosses the tile edge.
   - A cut point has identical coordinates in both tiles. Readers join tiles by matching
     coordinates.
2. **Edges.** Count, then for each edge, sorted by `from`:
   - `from` as Δ from the previous edge's `from`; `to` as s Δ from `from`.
   - length.
   - a flags byte: bits 0–3 class (0 motorway … 8 service), bit 4 link, bit 5 one-way
     (`from`→`to` only), bit 6 toll.
   - posted max km/h (0 = none), lanes (0 = unknown), name index + 1 (0 = none).
   - shape: count, then (s Δlat, s Δlon) starting from the `from` node. Simplified to within
     1.5 m, ends excluded.
   - marks: count, then (kind, Δ distance from the edge start).
     - Kinds: 0 signal, 1 speed breaker, 2 crossing, 3 camera, 4 stop.
     - A mark on a junction belongs to the edges that end there.
3. **Names.** Count, then (byte length, UTF-8). `name:en`, else `name`, else `ref`.
4. **Turn restrictions.** Count, then (from edge, via node, to edge, u8 only).
   - `only = 1` means every other turn from that edge at that node is banned.

One-way ways tagged `oneway=-1` are reversed at build time, so every one-way edge runs `from`→`to`.

## Known gaps in version 1

- **Vehicles:** no per-vehicle access (bike or scooter only, no motorcycles, and so on). Every
  kept road is open to all motor vehicles.
- **Turn restrictions:** stored but not yet used by the router.
- **Two-way marks:** a mark on a two-way edge is placed by distance from `from`. A router going
  the other way should use `length − at`.
