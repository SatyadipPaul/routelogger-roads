package roadpack.route

import roadpack.format.Archive
import java.io.File
import kotlin.random.Random

/**
 * Times routing on a road pack: runs on the computer, and on the phone after dexing.
 * Usage: bench <local.rpk> [random pairs] [check against Dijkstra: count]
 */
fun main(args: Array<String>) {
    val pairs = args.getOrNull(1)?.toInt() ?: 300
    val checks = args.getOrNull(2)?.toInt() ?: 30
    var t = System.nanoTime()
    val tiles = Archive.Reader(File(args[0])).use { r -> r.entries.map { r.tile(it) } }
    val readMs = (System.nanoTime() - t) / 1e6
    t = System.nanoTime()
    val g = Graph(tiles)
    val graphMs = (System.nanoTime() - t) / 1e6
    println("loaded ${tiles.size} tiles in ${"%.0f".format(readMs)} ms, graph ${g.size} nodes / ${g.arcs} arcs in ${"%.0f".format(graphMs)} ms, top speed ${g.maxKmh} km/h")
    val router = Router(g)

    val named = listOf(
        Triple("CSMT to Thane station", 18_940_000 to 72_835_300, 19_186_300 to 72_975_700),
        Triple("Andheri station to Vashi", 19_119_700 to 72_846_400, 19_077_100 to 72_998_700),
        Triple("Bandra station to Powai lake", 19_054_400 to 72_840_200, 19_127_000 to 72_905_000),
        Triple("Colaba to Borivali station", 18_915_000 to 72_825_000, 19_229_000 to 72_857_000),
    )
    val rnd = Random(42)
    val ok = ArrayList<Pair<Int, Int>>()
    while (ok.size < pairs + 50) {
        val a = rnd.nextInt(g.size); val b = rnd.nextInt(g.size)
        if (g.fStart[a + 1] > g.fStart[a] && g.bStart[b + 1] > g.bStart[b]) ok.add(a to b)
    }
    // Warm up the JIT.
    for ((a, b) in ok.take(50)) router.route(a, b)

    for ((name, from, to) in named) {
        val s = g.nearest(from.first, from.second); val d = g.nearest(to.first, to.second)
        t = System.nanoTime()
        val r = router.route(s, d)
        val ms = (System.nanoTime() - t) / 1e6
        if (r == null) println("$name: no route") else
            println("$name: ${"%.1f".format(r.lengthDm / 10000.0)} km, ${"%.0f".format(r.timeDs / 600.0)} min, ${"%.1f".format(ms)} ms, ${r.settled} settled")
    }

    var mismatches = 0
    for ((a, b) in ok.drop(50).take(checks)) {
        val r = router.route(a, b)?.timeDs ?: Router.INF
        val d = router.dijkstra(a, b)
        if (r != d) { mismatches++; println("MISMATCH $a->$b: bidirectional A* $r, Dijkstra $d") }
    }
    println("checked $checks random pairs against Dijkstra: $mismatches mismatches")

    val times = ArrayList<Double>(); val settled = ArrayList<Int>(); var none = 0; var dijkstraMs = 0.0
    for ((a, b) in ok.drop(50).take(pairs)) {
        t = System.nanoTime()
        val r = router.route(a, b)
        times.add((System.nanoTime() - t) / 1e6)
        if (r == null) none++ else settled.add(r.settled)
    }
    for ((a, b) in ok.drop(50).take(20)) { t = System.nanoTime(); router.dijkstra(a, b); dijkstraMs += (System.nanoTime() - t) / 1e6 }
    times.sort(); settled.sort()
    fun p(q: Double) = times[((times.size - 1) * q).toInt()]
    println("random pairs: $pairs, unreachable $none")
    println("bidirectional A*: median ${"%.1f".format(p(0.5))} ms, p90 ${"%.1f".format(p(0.9))} ms, max ${"%.1f".format(times.last())} ms, median settled ${settled[settled.size / 2]}")
    println("plain Dijkstra: mean ${"%.1f".format(dijkstraMs / 20)} ms")
}
