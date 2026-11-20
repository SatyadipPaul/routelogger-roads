package roadpack.route

import roadpack.format.MarkKind
import roadpack.format.RoadClass
import roadpack.format.Tile
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * A road graph joined from one or more tiles. Nodes with identical coordinates in different
 * tiles (the cut points at tile edges) become one node. Costs are travel time in deciseconds.
 */
class Graph(val tiles: List<Tile>) {
    val lat: IntArray; val lon: IntArray
    val fStart: IntArray; val fTo: IntArray; val fCost: IntArray; val fLen: IntArray
    /** For each forward arc: the tile and edge it came from, and whether it runs the edge backwards. */
    val fTile: IntArray; val fEdge: IntArray; val fRev: BooleanArray
    val bStart: IntArray; val bTo: IntArray; val bCost: IntArray
    val maxKmh: Int
    val size: Int get() = lat.size
    val arcs: Int get() = fTo.size

    init {
        val index = HashMap<Long, Int>()
        val la = IntList(); val lo = IntList()
        val aFrom = IntList(); val aTo = IntList(); val aCost = IntList(); val aLen = IntList()
        val aTile = IntList(); val aEdge = IntList(); val aRev = IntList()
        var vmax = 1
        for ((ti, t) in tiles.withIndex()) {
            val local = IntArray(t.lat.size) { i ->
                val key = (t.lat[i].toLong() shl 32) or (t.lon[i].toLong() and 0xffffffffL)
                index.getOrPut(key) { la.add(t.lat[i]); lo.add(t.lon[i]); la.size - 1 }
            }
            for ((ei, e) in t.edges.withIndex()) {
                val kmh = speedKmh(e.cls, e.link, e.maxKmh)
                if (kmh > vmax) vmax = kmh
                var cost = (e.lengthDm * 3.6 / kmh).toInt().coerceAtLeast(1)
                for (m in e.marks) cost += PENALTY_DS[m.kind]
                val a = local[e.from]; val b = local[e.to]
                aFrom.add(a); aTo.add(b); aCost.add(cost); aLen.add(e.lengthDm); aTile.add(ti); aEdge.add(ei); aRev.add(0)
                if (!e.oneway) { aFrom.add(b); aTo.add(a); aCost.add(cost); aLen.add(e.lengthDm); aTile.add(ti); aEdge.add(ei); aRev.add(1) }
            }
        }
        lat = la.toArray(); lon = lo.toArray(); maxKmh = vmax
        val n = lat.size; val m = aFrom.size
        fStart = IntArray(n + 1); bStart = IntArray(n + 1)
        for (i in 0 until m) { fStart[aFrom[i] + 1]++; bStart[aTo[i] + 1]++ }
        for (i in 0 until n) { fStart[i + 1] += fStart[i]; bStart[i + 1] += bStart[i] }
        fTo = IntArray(m); fCost = IntArray(m); fLen = IntArray(m); bTo = IntArray(m); bCost = IntArray(m)
        fTile = IntArray(m); fEdge = IntArray(m); fRev = BooleanArray(m)
        val fp = fStart.copyOf(n); val bp = bStart.copyOf(n)
        for (i in 0 until m) {
            val f = fp[aFrom[i]]++; fTo[f] = aTo[i]; fCost[f] = aCost[i]; fLen[f] = aLen[i]
            fTile[f] = aTile[i]; fEdge[f] = aEdge[i]; fRev[f] = aRev[i] == 1
            val b = bp[aTo[i]]++; bTo[b] = aFrom[i]; bCost[b] = aCost[i]
        }
    }

    /**
     * The [k] nodes nearest a point, nearest first: ones a route can leave from ([leaving]) or
     * arrive at. Trying several guards against a node on a cut-off scrap of road.
     */
    fun nearestK(latE6: Int, lonE6: Int, k: Int, leaving: Boolean): IntArray {
        val kx = cos(Math.toRadians(latE6 / 1e6))
        val best = IntArray(k) { -1 }; val bd = DoubleArray(k) { Double.MAX_VALUE }
        for (i in lat.indices) {
            val ok = if (leaving) fStart[i + 1] > fStart[i] else bStart[i + 1] > bStart[i]
            if (!ok) continue
            val dy = (lat[i] - latE6).toDouble(); val dx = (lon[i] - lonE6) * kx
            val d = dx * dx + dy * dy
            if (d >= bd[k - 1]) continue
            var j = k - 1
            while (j > 0 && bd[j - 1] > d) { bd[j] = bd[j - 1]; best[j] = best[j - 1]; j-- }
            bd[j] = d; best[j] = i
        }
        return best.filter { it >= 0 }.toIntArray()
    }

    fun nearest(latE6: Int, lonE6: Int): Int {
        val k = cos(Math.toRadians(latE6 / 1e6)); var best = -1; var bd = Double.MAX_VALUE
        for (i in lat.indices) {
            if (fStart[i + 1] == fStart[i] && bStart[i + 1] == bStart[i]) continue
            val dy = (lat[i] - latE6).toDouble(); val dx = (lon[i] - lonE6) * k
            val d = dx * dx + dy * dy
            if (d < bd) { bd = d; best = i }
        }
        return best
    }

    companion object {
        /** Speeds in km/h by road class when no limit is posted (India, urban traffic). */
        val DEFAULT_KMH = intArrayOf(80, 60, 45, 35, 30, 25, 20, 10, 10)
        val PENALTY_DS = IntArray(MarkKind.NAMES.size).also {
            it[MarkKind.SIGNAL] = 150; it[MarkKind.BREAKER] = 40; it[MarkKind.CROSSING] = 10; it[MarkKind.STOP] = 50
        }
        fun speedKmh(cls: Int, link: Boolean, posted: Int): Int {
            val d = DEFAULT_KMH[cls.coerceIn(0, RoadClass.SERVICE)]
            val base = if (posted > 0) minOf(posted, d + 20) else d
            return if (link) maxOf(20, base * 3 / 4) else base
        }
    }
}

class Route(val nodes: IntArray, val timeDs: Int, val lengthDm: Long, val settled: Int, val arcs: IntArray = IntArray(0))

/** A mark passed on a route, [atDm] from its start. */
class RouteMark(val kind: Int, val atDm: Long)

/** The drawn line of a route, every shape point included, in micro-degrees. */
fun Graph.shape(route: Route): Pair<IntArray, IntArray> {
    val la = IntList(); val lo = IntList()
    if (route.arcs.isEmpty()) { route.nodes.forEach { la.add(lat[it]); lo.add(lon[it]) }; return la.toArray() to lo.toArray() }
    for (arc in route.arcs) {
        val t = tiles[fTile[arc]]; val e = t.edges[fEdge[arc]]
        val n = e.shapeLat.size + 2
        for (k in 0 until n) {
            val i = if (fRev[arc]) n - 1 - k else k
            if (k == 0 && la.size > 0) continue
            when (i) {
                0 -> { la.add(t.lat[e.from]); lo.add(t.lon[e.from]) }
                n - 1 -> { la.add(t.lat[e.to]); lo.add(t.lon[e.to]) }
                else -> { la.add(e.shapeLat[i - 1]); lo.add(e.shapeLon[i - 1]) }
            }
        }
    }
    return la.toArray() to lo.toArray()
}

/** Signals, breakers and the rest passed along a route, in order. */
fun Graph.marks(route: Route): List<RouteMark> {
    val out = ArrayList<RouteMark>()
    var at = 0L
    for (arc in route.arcs) {
        val e = tiles[fTile[arc]].edges[fEdge[arc]]
        val ms = if (fRev[arc]) e.marks.map { RouteMark(it.kind, at + e.lengthDm - it.atDm) }.reversed()
        else e.marks.map { RouteMark(it.kind, at + it.atDm) }
        out.addAll(ms)
        at += e.lengthDm
    }
    return out
}

/** The named roads of a route with the metres driven on each, longest first. */
fun Graph.roads(route: Route): List<Pair<String, Long>> {
    val byName = LinkedHashMap<String, Long>()
    for (arc in route.arcs) {
        val t = tiles[fTile[arc]]; val e = t.edges[fEdge[arc]]
        if (e.name >= 0) byName.merge(t.names[e.name], e.lengthDm / 10L, Long::plus)
    }
    return byName.entries.sortedByDescending { it.value }.map { it.key to it.value }
}

/** Whether any part of a route is a toll road. */
fun Graph.toll(route: Route): Boolean = route.arcs.any { tiles[fTile[it]].edges[fEdge[it]].toll }

/** Shortest-time search. Reuse one Router per graph; it is not thread-safe. */
class Router(private val g: Graph) {
    private val n = g.size
    private val df = IntArray(n) { INF }; private val db = IntArray(n) { INF }
    private val pf = IntArray(n) { -1 }; private val pb = IntArray(n) { -1 }
    private val doneF = BooleanArray(n); private val doneB = BooleanArray(n)
    private val touched = IntList()
    private val hf = Heap(); private val hb = Heap()
    private val cosLat = cos(Math.toRadians(g.lat[0] / 1e6))
    /** Deciseconds per micro-degree of straight line at the fastest speed in the graph, with 1 % slack. */
    private val dsPerUnit = 0.111_319_5 * 36.0 / g.maxKmh * 0.99

    private fun h(a: Int, b: Int): Double {
        val dy = (g.lat[a] - g.lat[b]).toDouble(); val dx = (g.lon[a] - g.lon[b]) * cosLat
        return sqrt(dx * dx + dy * dy) * dsPerUnit
    }

    private fun reset() {
        for (i in 0 until touched.size) { val v = touched[i]; df[v] = INF; db[v] = INF; pf[v] = -1; pb[v] = -1; doneF[v] = false; doneB[v] = false }
        touched.clear(); hf.clear(); hb.clear()
    }

    /** Bidirectional A* with average potentials (Ikeda et al.); exact. */
    fun route(s: Int, t: Int): Route? {
        reset()
        if (s == t) return Route(intArrayOf(s), 0, 0, 0)
        fun pot(v: Int) = (h(v, t) - h(v, s)) / 2
        df[s] = 0; db[t] = 0; touched.add(s); touched.add(t)
        hf.push(pot(s), s); hb.push(-pot(t), t)
        var mu = INF; var meet = -1; var settled = 0
        while (!hf.isEmpty() && !hb.isEmpty()) {
            if (hf.topKey() + hb.topKey() >= mu) break
            val forward = hf.topKey() <= hb.topKey()
            val heap = if (forward) hf else hb
            val u = heap.pop()
            val done = if (forward) doneF else doneB
            if (done[u]) continue
            done[u] = true; settled++
            val dist = if (forward) df else db; val other = if (forward) db else df
            val par = if (forward) pf else pb
            val start = if (forward) g.fStart else g.bStart
            val to = if (forward) g.fTo else g.bTo; val cost = if (forward) g.fCost else g.bCost
            for (a in start[u] until start[u + 1]) {
                val v = to[a]; val nd = dist[u] + cost[a]
                if (nd < dist[v]) {
                    if (dist[v] == INF && other[v] == INF) touched.add(v)
                    dist[v] = nd; par[v] = u
                    heap.push(nd + if (forward) pot(v) else -pot(v), v)
                }
                if (other[v] != INF && nd + other[v] < mu) { mu = nd + other[v]; meet = v }
            }
        }
        if (meet < 0) return null
        val path = IntList()
        var v = meet
        while (v != -1) { path.add(v); v = pf[v] }
        val head = path.toArray().reversedArray()
        val tail = IntList(); v = pb[meet]
        while (v != -1) { tail.add(v); v = pb[v] }
        val nodes = head + tail.toArray()
        var len = 0L
        val arcs = IntArray(nodes.size - 1)
        for (i in 0 until nodes.size - 1) {
            var best = Int.MAX_VALUE; var bl = 0; var ba = -1
            for (a in g.fStart[nodes[i]] until g.fStart[nodes[i] + 1]) if (g.fTo[a] == nodes[i + 1] && g.fCost[a] < best) { best = g.fCost[a]; bl = g.fLen[a]; ba = a }
            len += bl; arcs[i] = ba
        }
        return Route(nodes, mu, len, settled, arcs)
    }

    /** Plain Dijkstra from s to t, for checking [route]. */
    fun dijkstra(s: Int, t: Int): Int {
        reset()
        df[s] = 0; touched.add(s); hf.push(0.0, s)
        while (!hf.isEmpty()) {
            val u = hf.pop()
            if (doneF[u]) continue
            doneF[u] = true
            if (u == t) return df[t]
            for (a in g.fStart[u] until g.fStart[u + 1]) {
                val v = g.fTo[a]; val nd = df[u] + g.fCost[a]
                if (nd < df[v]) { if (df[v] == INF) touched.add(v); df[v] = nd; hf.push(nd.toDouble(), v) }
            }
        }
        return INF
    }

    companion object { const val INF = Int.MAX_VALUE / 2 }
}

class IntList {
    private var a = IntArray(16); var size = 0; private set
    fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
    operator fun get(i: Int) = a[i]
    fun clear() { size = 0 }
    fun toArray(): IntArray = a.copyOf(size)
}

/** Binary min-heap of (key, node) with lazy deletion. */
class Heap {
    private var k = DoubleArray(1024); private var v = IntArray(1024); private var n = 0
    fun isEmpty() = n == 0
    fun clear() { n = 0 }
    fun topKey() = k[0]
    fun push(key: Double, node: Int) {
        if (n == k.size) { k = k.copyOf(n * 2); v = v.copyOf(n * 2) }
        var i = n++
        while (i > 0) { val p = (i - 1) / 2; if (k[p] <= key) break; k[i] = k[p]; v[i] = v[p]; i = p }
        k[i] = key; v[i] = node
    }
    fun pop(): Int {
        val top = v[0]; n--
        if (n > 0) {
            val key = k[n]; val node = v[n]; var i = 0
            while (true) {
                var c = 2 * i + 1; if (c >= n) break
                if (c + 1 < n && k[c + 1] < k[c]) c++
                if (k[c] >= key) break
                k[i] = k[c]; v[i] = v[c]; i = c
            }
            k[i] = key; v[i] = node
        }
        return top
    }
}
