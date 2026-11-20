package roadpack

import roadpack.format.Archive
import roadpack.format.Edge
import roadpack.format.Mark
import roadpack.format.MarkKind
import roadpack.format.Restriction
import roadpack.format.RoadClass
import roadpack.format.Tile
import roadpack.route.Graph
import roadpack.route.Router
import roadpack.route.marks
import roadpack.route.roads
import roadpack.route.shape
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PackTest {
    private fun sample() = Tile(
        10, 719, 456,
        intArrayOf(19_000_000, 19_001_000, 19_002_000, -5), intArrayOf(72_800_000, 72_801_000, 72_799_000, -170_000_000),
        listOf(
            Edge(0, 1, 1500, RoadClass.PRIMARY, false, false, true, 50, 2, 0, intArrayOf(19_000_500), intArrayOf(72_800_700), listOf(Mark(MarkKind.SIGNAL, 1500))),
            Edge(1, 2, 2400, RoadClass.RESIDENTIAL, true, true, false, 0, 0, -1, IntArray(0), IntArray(0), emptyList()),
            Edge(2, 0, 2300, RoadClass.SERVICE, false, false, false, 0, 0, 1, IntArray(0), IntArray(0), listOf(Mark(MarkKind.BREAKER, 100), Mark(MarkKind.CROSSING, 900))),
        ),
        listOf("LBS Marg", "मार्ग"), listOf(Restriction(0, 1, 1, false)),
    )

    @Test fun tileRoundTrips() {
        val t = sample()
        val d = Tile.decode(10, 719, 456, t.encode())
        assertContentEquals(t.lat, d.lat); assertContentEquals(t.lon, d.lon)
        assertEquals(t.names, d.names)
        assertEquals(t.edges.size, d.edges.size)
        for ((a, b) in t.edges.zip(d.edges)) {
            assertEquals(listOf(a.from, a.to, a.lengthDm, a.cls, a.maxKmh, a.lanes, a.name), listOf(b.from, b.to, b.lengthDm, b.cls, b.maxKmh, b.lanes, b.name))
            assertEquals(listOf(a.link, a.oneway, a.toll), listOf(b.link, b.oneway, b.toll))
            assertContentEquals(a.shapeLat, b.shapeLat); assertContentEquals(a.shapeLon, b.shapeLon)
            assertEquals(a.marks.map { it.kind to it.atDm }, b.marks.map { it.kind to it.atDm })
        }
        assertEquals(1, d.restrictions.single().via)
    }

    @Test fun archiveIndexPointsAtTiles() {
        val f = File.createTempFile("pack", ".rpk").apply { deleteOnExit() }
        val other = Tile(10, 700, 400, intArrayOf(1), intArrayOf(2), emptyList(), emptyList(), emptyList())
        Archive.write(f, 0, 10, 1234L, listOf(sample(), other))
        Archive.Reader(f).use { r ->
            assertEquals(1234L, r.builtMs)
            assertEquals(listOf(700 to 400, 719 to 456), r.entries.map { it.x to it.y })
            assertEquals(3, r.tile(r.entries[1]).edges.size)
            assertEquals(1, r.tile(r.entries[0]).lat.size)
        }
    }

    @Test fun routerRespectsOneWayAndJoinsTilesAtSharedPoints() {
        // Two tiles share the cut point (19.0, 72.9). A to cut point is one way, so the route back fails.
        val a = Tile(10, 0, 0, intArrayOf(19_000_000, 19_000_000), intArrayOf(72_890_000, 72_900_000),
            listOf(Edge(0, 1, 10_000, RoadClass.PRIMARY, false, true, false, 0, 0, -1, IntArray(0), IntArray(0), emptyList())), emptyList(), emptyList())
        val b = Tile(10, 1, 0, intArrayOf(19_000_000, 19_000_000), intArrayOf(72_900_000, 72_910_000),
            listOf(Edge(0, 1, 10_000, RoadClass.PRIMARY, false, false, false, 0, 0, -1, IntArray(0), IntArray(0), emptyList())), emptyList(), emptyList())
        val g = Graph(listOf(a, b))
        assertEquals(3, g.size)
        val r = Router(g)
        val s = g.nearest(19_000_000, 72_890_000); val t = g.nearest(19_000_000, 72_910_000)
        assertEquals(20_000L, r.route(s, t)!!.lengthDm)
        assertEquals(r.dijkstra(s, t), r.route(s, t)!!.timeDs)
        assertNull(r.route(t, s))
    }

    @Test fun routeCarriesShapeMarksAndNamesInDrivingOrder() {
        // 0 -> 1 has a shape point and a signal 100 m in; 1 -> 2 is driven backwards along its edge.
        val t = Tile(10, 0, 0, intArrayOf(19_000_000, 19_001_000, 19_002_000), intArrayOf(72_900_000, 72_900_000, 72_900_000),
            listOf(
                Edge(0, 1, 1110, RoadClass.PRIMARY, false, false, false, 0, 0, 0, intArrayOf(19_000_500), intArrayOf(72_900_100), listOf(Mark(MarkKind.SIGNAL, 1000))),
                Edge(2, 1, 1110, RoadClass.PRIMARY, false, false, false, 0, 0, 1, IntArray(0), IntArray(0), listOf(Mark(MarkKind.BREAKER, 100))),
            ), listOf("Ghodbunder Road", "Eastern Express Highway"), emptyList())
        val g = Graph(listOf(t))
        val r = Router(g).route(g.nearest(19_000_000, 72_900_000), g.nearest(19_002_000, 72_900_000))!!
        val (la, lo) = g.shape(r)
        assertContentEquals(intArrayOf(19_000_000, 19_000_500, 19_001_000, 19_002_000), la)
        assertEquals(72_900_100, lo[1])
        val ms = g.marks(r)
        assertEquals(listOf(MarkKind.SIGNAL to 1000L, MarkKind.BREAKER to 1110L + 1010L), ms.map { it.kind to it.atDm })
        assertEquals(listOf("Ghodbunder Road", "Eastern Express Highway"), g.roads(r).map { it.first })
        assertEquals(1, g.nearestK(19_000_000, 72_900_000, 3, leaving = true).first().let { g.lat[it] - 18_999_999 })
    }
}
