package roadpack.format

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Road pack format, version 1. See tools/roadpack/FORMAT.md.
 *
 * Coordinates are integer micro-degrees (1e-6°, about 0.1 m). Lengths are decimetres.
 */
object RoadClass {
    const val MOTORWAY = 0; const val TRUNK = 1; const val PRIMARY = 2; const val SECONDARY = 3
    const val TERTIARY = 4; const val UNCLASSIFIED = 5; const val RESIDENTIAL = 6; const val LIVING = 7; const val SERVICE = 8
    val NAMES = listOf("motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "living_street", "service")
}

object MarkKind {
    const val SIGNAL = 0; const val BREAKER = 1; const val CROSSING = 2; const val CAMERA = 3; const val STOP = 4
    val NAMES = listOf("signal", "breaker", "crossing", "camera", "stop")
}

/** A point on an edge: a signal, speed breaker and so on, [atDm] from the edge's start. */
class Mark(val kind: Int, val atDm: Int)

/**
 * A road piece between two nodes of the same tile. [oneway] true means it can only be driven
 * from [from] to [to]. Shape points exclude the end nodes.
 */
class Edge(
    val from: Int, val to: Int, val lengthDm: Int, val cls: Int, val link: Boolean, val oneway: Boolean,
    val toll: Boolean, val maxKmh: Int, val lanes: Int, val name: Int,
    val shapeLat: IntArray, val shapeLon: IntArray, val marks: List<Mark>,
)

/** No turn from [fromEdge] to [toEdge] at node [via]; with [only], every other turn there is banned. */
class Restriction(val fromEdge: Int, val via: Int, val toEdge: Int, val only: Boolean)

class Tile(
    val z: Int, val x: Int, val y: Int,
    val lat: IntArray, val lon: IntArray,
    val edges: List<Edge>, val names: List<String>, val restrictions: List<Restriction>,
) {
    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { o ->
            o.uvar(lat.size)
            var pa = 0; var po = 0
            for (i in lat.indices) { o.svar(lat[i] - pa); o.svar(lon[i] - po); pa = lat[i]; po = lon[i] }
            o.uvar(edges.size)
            var prevFrom = 0
            for (e in edges) {
                o.uvar(e.from - prevFrom); prevFrom = e.from
                o.svar(e.to - e.from)
                o.uvar(e.lengthDm)
                o.writeByte(e.cls or (if (e.link) 16 else 0) or (if (e.oneway) 32 else 0) or (if (e.toll) 64 else 0))
                o.uvar(e.maxKmh); o.uvar(e.lanes); o.uvar(e.name + 1)
                o.uvar(e.shapeLat.size)
                var a = lat[e.from]; var b = lon[e.from]
                for (k in e.shapeLat.indices) { o.svar(e.shapeLat[k] - a); o.svar(e.shapeLon[k] - b); a = e.shapeLat[k]; b = e.shapeLon[k] }
                o.uvar(e.marks.size)
                var at = 0
                for (m in e.marks) { o.uvar(m.kind); o.uvar(m.atDm - at); at = m.atDm }
            }
            o.uvar(names.size)
            for (n in names) { val u = n.toByteArray(Charsets.UTF_8); o.uvar(u.size); o.write(u) }
            o.uvar(restrictions.size)
            for (r in restrictions) { o.uvar(r.fromEdge); o.uvar(r.via); o.uvar(r.toEdge); o.writeByte(if (r.only) 1 else 0) }
        }
        return bytes.toByteArray()
    }

    companion object {
        fun decode(z: Int, x: Int, y: Int, data: ByteArray): Tile = DataInputStream(ByteArrayInputStream(data)).let { i ->
            val n = i.uvar()
            val lat = IntArray(n); val lon = IntArray(n)
            var pa = 0; var po = 0
            for (k in 0 until n) { pa += i.svar(); po += i.svar(); lat[k] = pa; lon[k] = po }
            val ne = i.uvar()
            val edges = ArrayList<Edge>(ne)
            var from = 0
            repeat(ne) {
                from += i.uvar()
                val to = from + i.svar()
                val len = i.uvar()
                val f = i.readUnsignedByte()
                val maxKmh = i.uvar(); val lanes = i.uvar(); val name = i.uvar() - 1
                val ns = i.uvar()
                val sl = IntArray(ns); val so = IntArray(ns)
                var a = lat[from]; var b = lon[from]
                for (k in 0 until ns) { a += i.svar(); b += i.svar(); sl[k] = a; so[k] = b }
                val nm = i.uvar()
                val marks = if (nm == 0) emptyList() else ArrayList<Mark>(nm).also { l ->
                    var at = 0
                    repeat(nm) { val kind = i.uvar(); at += i.uvar(); l.add(Mark(kind, at)) }
                }
                edges.add(Edge(from, to, len, f and 15, f and 16 != 0, f and 32 != 0, f and 64 != 0, maxKmh, lanes, name, sl, so, marks))
            }
            val names = List(i.uvar()) { val b = ByteArray(i.uvar()); i.readFully(b); String(b, Charsets.UTF_8) }
            val rs = List(i.uvar()) { Restriction(i.uvar(), i.uvar(), i.uvar(), i.readUnsignedByte() == 1) }
            Tile(z, x, y, lat, lon, edges, names, rs)
        }
    }
}

/**
 * One file per level: a fixed header, a fixed-size index sorted by (x, y), then gzipped tiles.
 * A client can read the first 20 + 20 × count bytes with one range request, then fetch single
 * tiles by offset.
 *
 * Header: "RPK1", u8 version, u8 level, u8 zoom, u8 reserved, i64 build time (ms), i32 count.
 * Index entry: i32 x, i32 y, i64 offset, i32 length.
 */
object Archive {
    const val HEADER = 20
    const val ENTRY = 20

    class Entry(val x: Int, val y: Int, val offset: Long, val length: Int)

    fun write(file: File, level: Int, z: Int, builtMs: Long, tiles: List<Tile>) {
        val blobs = tiles.sortedWith(compareBy({ it.x }, { it.y })).map { t ->
            val gz = ByteArrayOutputStream()
            GZIPOutputStream(gz).use { it.write(t.encode()) }
            t to gz.toByteArray()
        }
        DataOutputStream(file.outputStream().buffered()).use { o ->
            o.writeBytes("RPK1"); o.writeByte(1); o.writeByte(level); o.writeByte(z); o.writeByte(0)
            o.writeLong(builtMs); o.writeInt(blobs.size)
            var off = HEADER.toLong() + ENTRY.toLong() * blobs.size
            for ((t, b) in blobs) { o.writeInt(t.x); o.writeInt(t.y); o.writeLong(off); o.writeInt(b.size); off += b.size }
            for ((_, b) in blobs) o.write(b)
        }
    }

    class Reader(private val file: File) : AutoCloseable {
        private val raf = RandomAccessFile(file, "r")
        val level: Int; val zoom: Int; val builtMs: Long; val entries: List<Entry>
        init {
            val magic = ByteArray(4); raf.readFully(magic)
            require(String(magic, Charsets.US_ASCII) == "RPK1") { "not a road pack" }
            require(raf.readUnsignedByte() == 1) { "unknown version" }
            level = raf.readUnsignedByte(); zoom = raf.readUnsignedByte(); raf.readUnsignedByte()
            builtMs = raf.readLong()
            entries = List(raf.readInt()) { Entry(raf.readInt(), raf.readInt(), raf.readLong(), raf.readInt()) }
        }
        fun bytes(e: Entry): ByteArray { val b = ByteArray(e.length); raf.seek(e.offset); raf.readFully(b); return b }
        fun tile(e: Entry): Tile = Tile.decode(zoom, e.x, e.y, GZIPInputStream(ByteArrayInputStream(bytes(e))).readBytes())
        override fun close() = raf.close()
    }
}

object TileMath {
    fun fx(lon: Double, z: Int) = (lon + 180.0) / 360.0 * (1 shl z)
    fun fy(lat: Double, z: Int): Double {
        val r = Math.toRadians(lat)
        return (1.0 - ln(tan(r) + 1.0 / kotlin.math.cos(r)) / PI) / 2.0 * (1 shl z)
    }
    fun lonOf(fx: Double, z: Int) = fx / (1 shl z) * 360.0 - 180.0
    fun latOf(fy: Double, z: Int) = Math.toDegrees(atan(sinh(PI * (1 - 2 * fy / (1 shl z)))))
    fun tx(lon: Double, z: Int) = floor(fx(lon, z)).toInt()
    fun ty(lat: Double, z: Int) = floor(fy(lat, z)).toInt()
}

internal fun OutputStream.uvar(v: Int) { var x = v.toLong() and 0xffffffffL; while (x >= 0x80) { write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }; write(x.toInt()) }
internal fun OutputStream.svar(v: Int) = uvar((v shl 1) xor (v shr 31))
internal fun InputStream.uvar(): Int {
    var r = 0L; var s = 0
    while (true) { val b = read(); require(b >= 0) { "truncated" }; r = r or ((b and 0x7f).toLong() shl s); if (b < 0x80) return r.toInt(); s += 7 }
}
internal fun InputStream.svar(): Int { val u = uvar(); return (u ushr 1) xor -(u and 1) }
