package roadpack

import crosby.binary.BinaryParser
import crosby.binary.Osmformat
import crosby.binary.file.BlockInputStream
import roadpack.format.Archive
import roadpack.format.Edge
import roadpack.format.Mark
import roadpack.format.MarkKind
import roadpack.format.Restriction
import roadpack.format.RoadClass
import roadpack.format.Tile
import roadpack.format.TileMath
import java.io.File
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Builds road packs from an OpenStreetMap .pbf extract.
 *
 * Usage: build <in.osm.pbf> <out dir>
 * Writes local.rpk (every drivable road, zoom-10 tiles) and main.rpk (motorway to secondary,
 * zoom-7 tiles), and prints per-tile sizes.
 */
fun main(args: Array<String>) {
    val input = File(args[0]); val out = File(args[1]).apply { mkdirs() }
    val t0 = System.currentTimeMillis()
    val osm = OsmRoads.read(input)
    println("read ${osm.ways.size} ways, ${osm.nodeCount} nodes, ${osm.marks.size} marks, ${osm.restrictions.size} restrictions in ${System.currentTimeMillis() - t0} ms")
    println("bounds ${osm.bounds}")
    val built = System.currentTimeMillis()
    for ((name, level, z, keep) in listOf(
        Level("local", 0, 10) { true },
        Level("main", 1, 7) { it <= RoadClass.SECONDARY },
    )) {
        val tiles = GraphBuilder(osm, z, keep).build()
        val file = File(out, "$name.rpk")
        Archive.write(file, level, z, built, tiles)
        Archive.Reader(file).use { r ->
            println("\n$name (zoom $z): ${tiles.size} tiles, ${file.length() / 1024} KB")
            val sizes = r.entries.map { it.length }.sorted()
            println("  tile size median ${sizes[sizes.size / 2] / 1024} KB, p90 ${sizes[(sizes.size - 1) * 9 / 10] / 1024} KB, largest:")
            for (e in r.entries.sortedByDescending { it.length }.take(15)) {
                val t = tiles.first { it.x == e.x && it.y == e.y }
                val shape = t.edges.sumOf { it.shapeLat.size }
                println("  $z/${e.x}/${e.y}: ${e.length / 1024} KB gz, ${t.lat.size} nodes, ${t.edges.size} edges, $shape shape pts, ${t.edges.sumOf { it.marks.size }} marks, ${t.restrictions.size} restrictions, ${"%.0f".format(t.edges.sumOf { it.lengthDm.toLong() } / 10000.0)} km")
            }
        }
    }
}

private data class Level(val name: String, val level: Int, val z: Int, val keep: (Int) -> Boolean)

class OsmWay(
    val id: Long, val refs: LongArray, val cls: Int, val link: Boolean, val oneway: Int,
    val toll: Boolean, val maxKmh: Int, val lanes: Int, val name: String?,
)

class OsmRestriction(val fromWay: Long, val via: Long, val toWay: Long, val only: Boolean)

/**
 * Road ways and the nodes they use. Node ids are kept in one sorted array, with coordinates in
 * parallel arrays, so all of India fits in a few GB.
 */
class OsmRoads(
    val ways: List<OsmWay>, private val ids: LongArray, private val lat: IntArray, private val lon: IntArray,
    val marks: Map<Long, Int>, val restrictions: List<OsmRestriction>, val bounds: String,
) {
    val nodeCount: Int get() = ids.size
    /** Position of a node id in the node arrays, or -1. */
    fun indexOf(id: Long): Int = java.util.Arrays.binarySearch(ids, id).let { if (it >= 0) it else -1 }
    /** Packed coordinates of a node, or null if it is not a road node. */
    fun coord(id: Long): Long? = indexOf(id).let { if (it < 0) null else pack(lat[it], lon[it]) }
    fun coordAt(index: Int): Long = pack(lat[index], lon[index])

    companion object {
        private val CLASSES = mapOf(
            "motorway" to RoadClass.MOTORWAY, "trunk" to RoadClass.TRUNK, "primary" to RoadClass.PRIMARY,
            "secondary" to RoadClass.SECONDARY, "tertiary" to RoadClass.TERTIARY, "unclassified" to RoadClass.UNCLASSIFIED,
            "road" to RoadClass.UNCLASSIFIED, "residential" to RoadClass.RESIDENTIAL, "living_street" to RoadClass.LIVING,
            "service" to RoadClass.SERVICE,
        )
        private val NO_SERVICE = setOf("parking_aisle", "driveway", "drive-through", "emergency_access")

        fun read(file: File): OsmRoads {
            val ways = ArrayList<OsmWay>()
            val rest = ArrayList<OsmRestriction>()
            var bounds = "?"
            run(file, object : BinaryParser() {
                override fun parse(header: Osmformat.HeaderBlock) {
                    if (header.hasBbox()) header.bbox.let { b -> bounds = "lat ${b.bottom / 1e9}..${b.top / 1e9}, lon ${b.left / 1e9}..${b.right / 1e9}" }
                }
                override fun parseRelations(rels: List<Osmformat.Relation>) {
                    for (r in rels) {
                        val tags = (0 until r.keysCount).associate { getStringById(r.getKeys(it)) to getStringById(r.getVals(it)) }
                        if (tags["type"] != "restriction") continue
                        val kind = tags["restriction"] ?: tags["restriction:motorcar"] ?: continue
                        var mem = 0L; var from = 0L; var via = 0L; var to = 0L
                        for (k in 0 until r.memidsCount) {
                            mem += r.getMemids(k)
                            val role = getStringById(r.getRolesSid(k)); val type = r.getTypes(k)
                            when {
                                role == "from" && type == Osmformat.Relation.MemberType.WAY -> from = mem
                                role == "to" && type == Osmformat.Relation.MemberType.WAY -> to = mem
                                role == "via" && type == Osmformat.Relation.MemberType.NODE -> via = mem
                            }
                        }
                        if (from != 0L && via != 0L && to != 0L) rest.add(OsmRestriction(from, via, to, kind.startsWith("only_")))
                    }
                }
                override fun parseWays(list: List<Osmformat.Way>) {
                    for (w in list) {
                        val tags = HashMap<String, String>()
                        for (k in 0 until w.keysCount) tags[getStringById(w.getKeys(k))] = getStringById(w.getVals(k))
                        val hw = tags["highway"] ?: continue
                        val link = hw.endsWith("_link")
                        val cls = CLASSES[hw.removeSuffix("_link")] ?: continue
                        if (tags["area"] == "yes") continue
                        if (cls == RoadClass.SERVICE && tags["service"] in NO_SERVICE) continue
                        val access = tags["motor_vehicle"] ?: tags["motorcar"] ?: tags["vehicle"] ?: tags["access"]
                        if (access == "no" || access == "private") continue
                        var ref = 0L
                        val refs = LongArray(w.refsCount) { ref += w.getRefs(it); ref }
                        if (refs.size < 2) continue
                        val ow = when (tags["oneway"]) {
                            "yes", "1", "true" -> 1
                            "-1", "reverse" -> -1
                            "no", "0", "false" -> 0
                            else -> if (cls == RoadClass.MOTORWAY || tags["junction"] == "roundabout") 1 else 0
                        }
                        ways.add(OsmWay(w.id, refs, cls, link, ow, tags["toll"] == "yes", speed(tags["maxspeed"]),
                            tags["lanes"]?.substringBefore(';')?.trim()?.toIntOrNull()?.coerceIn(0, 20) ?: 0,
                            tags["name:en"] ?: tags["name"] ?: tags["ref"]))
                    }
                }
                override fun parseNodes(nodes: List<Osmformat.Node>) = Unit
                override fun parseDense(nodes: Osmformat.DenseNodes) = Unit
                override fun complete() = Unit
            })
            var total = 0
            for (w in ways) total += w.refs.size
            val all = LongArray(total)
            var p = 0
            for (w in ways) for (r in w.refs) all[p++] = r
            all.sort()
            var u = 0
            for (i in all.indices) if (i == 0 || all[i] != all[i - 1]) all[u++] = all[i]
            val ids = all.copyOf(u)
            val latA = IntArray(u); val lonA = IntArray(u); val found = BooleanArray(u)
            val marks = HashMap<Long, Int>()
            run(file, object : BinaryParser() {
                fun node(id: Long, lat: Double, lon: Double, tags: Map<String, String>) {
                    val at = java.util.Arrays.binarySearch(ids, id)
                    if (at < 0) return
                    latA[at] = (lat * 1e6).roundToInt(); lonA[at] = (lon * 1e6).roundToInt(); found[at] = true
                    if (tags.isEmpty()) return
                    val hw = tags["highway"]
                    val m = when {
                        hw == "traffic_signals" -> MarkKind.SIGNAL
                        tags["traffic_calming"] != null && tags["traffic_calming"] != "no" -> MarkKind.BREAKER
                        hw == "speed_camera" -> MarkKind.CAMERA
                        hw == "stop" -> MarkKind.STOP
                        hw == "crossing" -> MarkKind.CROSSING
                        else -> -1
                    }
                    if (m >= 0) marks[id] = m
                }
                override fun parseNodes(nodes: List<Osmformat.Node>) {
                    for (n in nodes) node(n.id, parseLat(n.lat), parseLon(n.lon),
                        (0 until n.keysCount).associate { getStringById(n.getKeys(it)) to getStringById(n.getVals(it)) })
                }
                override fun parseDense(d: Osmformat.DenseNodes) {
                    var id = 0L; var lat = 0L; var lon = 0L; var kv = 0
                    for (i in 0 until d.idCount) {
                        id += d.getId(i); lat += d.getLat(i); lon += d.getLon(i)
                        var tags: MutableMap<String, String>? = null
                        if (d.keysValsCount > 0) {
                            while (d.getKeysVals(kv) != 0) {
                                if (tags == null) tags = HashMap()
                                tags[getStringById(d.getKeysVals(kv))] = getStringById(d.getKeysVals(kv + 1)); kv += 2
                            }
                            kv++
                        }
                        node(id, parseLat(lat), parseLon(lon), tags ?: emptyMap())
                    }
                }
                override fun parseWays(ways: List<Osmformat.Way>) = Unit
                override fun parseRelations(rels: List<Osmformat.Relation>) = Unit
                override fun parse(header: Osmformat.HeaderBlock) = Unit
                override fun complete() = Unit
            })
            val complete = ways.filter { w -> w.refs.all { found[java.util.Arrays.binarySearch(ids, it)] } }
            return OsmRoads(complete, ids, latA, lonA, marks, rest, bounds)
        }

        private fun run(file: File, parser: BinaryParser) = file.inputStream().buffered().use { BlockInputStream(it, parser).process() }

        fun speed(v: String?): Int {
            if (v == null) return 0
            val n = Regex("\\d+").find(v)?.value?.toIntOrNull() ?: return 0
            return (if (v.contains("mph")) n * 1.609 else n.toDouble()).roundToInt().coerceIn(0, 200)
        }
    }
}

fun pack(lat: Int, lon: Int): Long = (lat.toLong() shl 32) or (lon.toLong() and 0xffffffffL)
fun latOf(p: Long): Int = (p shr 32).toInt()
fun lonOf(p: Long): Int = p.toInt()

/** Splits ways into edges between junctions, cuts them at tile edges, and groups them into tiles. */
class GraphBuilder(private val osm: OsmRoads, private val z: Int, private val keep: (Int) -> Boolean) {

    private class Piece(val way: OsmWay, val pts: LongArray, val ids: LongArray) // ids: OSM id or 0 for cut points

    fun build(): List<Tile> {
        val ways = osm.ways.filter { keep(it.cls) }
        val uses = IntArray(osm.nodeCount)
        for (w in ways) {
            for (r in w.refs) uses[osm.indexOf(r)]++
            uses[osm.indexOf(w.refs.first())]++; uses[osm.indexOf(w.refs.last())]++
        }
        for (r in osm.restrictions) osm.indexOf(r.via).let { if (it >= 0) uses[it] += 2 }
        val byTile = HashMap<Long, MutableList<Piece>>()
        for (w in ways) {
            val refs = if (w.oneway == -1) w.refs.reversedArray() else w.refs
            var start = 0
            for (i in 1 until refs.size) {
                if (i == refs.size - 1 || uses[osm.indexOf(refs[i])] >= 2) {
                    val ids = refs.slice(start..i)
                    cut(ids.map { osm.coord(it)!! }, ids).forEach { (key, pts, pids) ->
                        byTile.getOrPut(key) { ArrayList() }.add(Piece(w, pts.toLongArray(), pids.toLongArray()))
                    }
                    start = i
                }
            }
        }
        return byTile.map { (key, pieces) -> tile((key shr 32).toInt(), key.toInt(), pieces) }
    }

    /** Splits a polyline wherever it crosses a tile edge; returns (tile key, points, OSM ids). */
    private fun cut(pts: List<Long>, ids: List<Long>): List<Triple<Long, List<Long>, List<Long>>> {
        val outPts = ArrayList<Long>(); val outIds = ArrayList<Long>(); val keys = ArrayList<Long>()
        outPts.add(pts[0]); outIds.add(ids[0])
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            val ax = TileMath.fx(lonOf(a) / 1e6, z); val ay = TileMath.fy(latOf(a) / 1e6, z)
            val bx = TileMath.fx(lonOf(b) / 1e6, z); val by = TileMath.fy(latOf(b) / 1e6, z)
            val ts = ArrayList<Double>()
            fun crossings(p: Double, q: Double) {
                if (floor(p) == floor(q)) return
                val lo = minOf(p, q); val hi = maxOf(p, q)
                var g = floor(lo) + 1
                while (g <= hi) { val t = (g - p) / (q - p); if (t > 1e-9 && t < 1 - 1e-9) ts.add(t); g += 1.0 }
            }
            crossings(ax, bx); crossings(ay, by)
            ts.sort()
            var prevT = 0.0
            for (t in ts + 1.0) {
                val mt = (prevT + t) / 2
                keys.add((floor(ay + (by - ay) * mt).toLong() and 0xffffffffL) or (floor(ax + (bx - ax) * mt).toLong() shl 32))
                if (t < 1.0) {
                    outPts.add(pack((TileMath.latOf(ay + (by - ay) * t, z) * 1e6).roundToInt(), (TileMath.lonOf(ax + (bx - ax) * t, z) * 1e6).roundToInt()))
                    outIds.add(0L)
                } else { outPts.add(b); outIds.add(ids[i]) }
                prevT = t
            }
        }
        // keys[k] is the tile of the step from outPts[k] to outPts[k + 1]; group runs of equal keys.
        val result = ArrayList<Triple<Long, List<Long>, List<Long>>>()
        var s = 0
        for (k in 1..keys.size) {
            if (k == keys.size || keys[k] != keys[s]) {
                result.add(Triple(keys[s], outPts.subList(s, k + 1).toList(), outIds.subList(s, k + 1).toList()))
                s = k
            }
        }
        return result
    }

    private fun tile(x: Int, y: Int, pieces: List<Piece>): Tile {
        // Nodes are identified by coordinates, so cut points match across neighbouring tiles.
        val nodeKeys = LinkedHashSet<Long>()
        for (p in pieces) { nodeKeys.add(p.pts.first()); nodeKeys.add(p.pts.last()) }
        val sorted = nodeKeys.sortedBy { morton(latOf(it), lonOf(it)) }
        val index = HashMap<Long, Int>(sorted.size * 2).apply { sorted.forEachIndexed { i, k -> put(k, i) } }
        val names = LinkedHashMap<String, Int>()
        class Built(val e: Edge, val way: Long, val fromId: Long, val toId: Long)
        val built = pieces.mapNotNull { p ->
            val from = index.getValue(p.pts.first()); val to = index.getValue(p.pts.last())
            if (from == to && p.pts.size <= 2) return@mapNotNull null
            val cum = DoubleArray(p.pts.size)
            for (i in 1 until p.pts.size) cum[i] = cum[i - 1] + metres(p.pts[i - 1], p.pts[i])
            val marks = ArrayList<Mark>()
            for (i in 1 until p.pts.size) {
                val id = p.ids[i]; if (id == 0L) continue
                val m = osm.marks[id] ?: continue
                marks.add(Mark(m, (cum[i] * 10).roundToInt()))
            }
            val keepIdx = simplify(p.pts, 1.5)
            val shape = keepIdx.filter { it != 0 && it != p.pts.size - 1 }.map { p.pts[it] }
            val w = p.way
            Built(Edge(from, to, (cum.last() * 10).roundToInt().coerceAtLeast(1), w.cls, w.link, w.oneway != 0, w.toll, w.maxKmh, w.lanes,
                w.name?.let { names.getOrPut(it) { names.size } } ?: -1,
                IntArray(shape.size) { latOf(shape[it]) }, IntArray(shape.size) { lonOf(shape[it]) }, marks),
                w.id, p.ids.first(), p.ids.last())
        }.sortedBy { it.e.from }
        val restrictions = ArrayList<Restriction>()
        val viaSet = osm.restrictions.groupBy { it.via }
        for ((via, list) in viaSet) {
            val c = osm.coord(via) ?: continue
            val v = index[c] ?: continue
            for (r in list) {
                val f = built.indexOfFirst { it.way == r.fromWay && (it.toId == via || (!it.e.oneway && it.fromId == via)) }
                val t = built.indexOfFirst { it.way == r.toWay && (it.fromId == via || (!it.e.oneway && it.toId == via)) }
                if (f >= 0 && t >= 0) restrictions.add(Restriction(f, v, t, r.only))
            }
        }
        return Tile(z, x, y, IntArray(sorted.size) { latOf(sorted[it]) }, IntArray(sorted.size) { lonOf(sorted[it]) },
            built.map { it.e }, names.keys.toList(), restrictions)
    }

    private fun morton(lat: Int, lon: Int): Long {
        fun spread(v: Int): Long { var x = (v.toLong() and 0xffffffffL); var r = 0L; for (i in 0 until 32) r = r or (((x shr i) and 1L) shl (2 * i)); return r }
        return spread(lat + 90_000_000) or (spread(lon + 180_000_000) shl 1)
    }

    /** Douglas-Peucker: indices of points to keep, within [tolM] metres. */
    private fun simplify(pts: LongArray, tolM: Double): List<Int> {
        if (pts.size <= 2) return pts.indices.toList()
        val keepFlags = BooleanArray(pts.size); keepFlags[0] = true; keepFlags[pts.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>(); stack.add(0 to pts.size - 1)
        val k = cos(Math.toRadians(latOf(pts[0]) / 1e6)) * 0.111_319_5
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            val ax = lonOf(pts[a]) * k; val ay = latOf(pts[a]) * 0.111_319_5
            val bx = lonOf(pts[b]) * k; val by = latOf(pts[b]) * 0.111_319_5
            var best = -1; var bestD = tolM
            for (i in a + 1 until b) {
                val px = lonOf(pts[i]) * k; val py = latOf(pts[i]) * 0.111_319_5
                val dx = bx - ax; val dy = by - ay
                val len2 = dx * dx + dy * dy
                val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
                val d = sqrt((ax + t * dx - px).let { it * it } + (ay + t * dy - py).let { it * it })
                if (d > bestD) { bestD = d; best = i }
            }
            if (best >= 0) { keepFlags[best] = true; stack.add(a to best); stack.add(best to b) }
        }
        return keepFlags.indices.filter { keepFlags[it] }
    }

    companion object {
        fun metres(a: Long, b: Long): Double {
            val la1 = Math.toRadians(latOf(a) / 1e6); val la2 = Math.toRadians(latOf(b) / 1e6)
            val dl = la2 - la1; val dn = Math.toRadians((lonOf(b) - lonOf(a)) / 1e6)
            val h = sin(dl / 2).let { it * it } + cos(la1) * cos(la2) * sin(dn / 2).let { it * it }
            return 2 * 6_371_000.0 * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }
    }
}
