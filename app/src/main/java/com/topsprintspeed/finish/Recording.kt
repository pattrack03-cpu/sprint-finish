package com.topsprintspeed.finish

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** One burst of finish-line recording (between Start recording and Pause). */
class Clip(val sliceW: Int, val height: Int) {
    val ts = ArrayList<Long>()          // frame times, System.nanoTime() clock
    val slices = ArrayList<ByteArray>() // RGBA, sliceW x height, bottom row first (OpenGL order)
    val size get() = ts.size
}

/** A whole race: start time plus every finish clip. Saved as a .bvid file until it is timed. */
class Recording(
    var name: String,
    val fps: Int,
    val sliceW: Int,
    val height: Int,
    val direction: Direction,
    var startMono: Long,
    val offsetS: Double,
    val startMode: String,
    val createdAt: Long,
) {
    val clips = ArrayList<Clip>()

    fun timeOf(tsMono: Long): Double = (tsMono - startMono) / 1e9 + offsetS

    fun sliceTime(clip: Int, slice: Int): Double = timeOf(clips[clip].ts[slice])

    fun clipSpan(i: Int): Pair<Double, Double>? {
        val c = clips.getOrNull(i) ?: return null
        if (c.size == 0) return null
        return timeOf(c.ts.first()) to timeOf(c.ts.last())
    }
}

data class Mark(val clip: Int, val slice: Int, val time: Double, val lane: String, val athlete: String)

/** Builds the photo-finish picture for one clip. */
object PhotoFinishImage {
    fun build(clip: Clip, dir: Direction): Bitmap {
        val n = clip.size
        val w = clip.sliceW
        val h = clip.height
        val width = (n * w).coerceAtLeast(1)
        val px = IntArray(width * h)
        for (k in 0 until n) {
            val data = clip.slices[k]
            val x0 = TimeMath.sliceX(k, w, n, dir)
            for (r in 0 until h) {
                val y = h - 1 - r
                val rowBase = y * width + x0
                for (j in 0 until w) {
                    val i = (r * w + j) * 4
                    val red = data[i].toInt() and 0xFF
                    val green = data[i + 1].toInt() and 0xFF
                    val blue = data[i + 2].toInt() and 0xFF
                    px[rowBase + j] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                }
            }
        }
        return Bitmap.createBitmap(px, width, h, Bitmap.Config.ARGB_8888)
    }

    /** Ticks every 0.1 s along the image: (image x, time). */
    fun ticks(rec: Recording, clipIndex: Int): List<Pair<Int, Double>> {
        val clip = rec.clips.getOrNull(clipIndex) ?: return emptyList()
        val out = ArrayList<Pair<Int, Double>>()
        var lastTenth = Long.MIN_VALUE
        for (k in 0 until clip.size) {
            val t = rec.timeOf(clip.ts[k])
            val tenth = Math.floor(t * 10.0).toLong()
            if (lastTenth != Long.MIN_VALUE && tenth != lastTenth) {
                val x = TimeMath.sliceX(k, clip.sliceW, clip.size, rec.direction) +
                    if (rec.direction == Direction.LEFT_TO_RIGHT) clip.sliceW else 0
                out.add(x to tenth / 10.0)
            }
            lastTenth = tenth
        }
        return out
    }
}

data class RaceInfo(
    val file: File,
    val name: String,
    val createdAt: Long,
    val fps: Int,
    val clips: Int,
    val slices: Int,
    val marks: Int,
    val sizeBytes: Long,
)

/**
 * The .bvid file: "SFBV", version, a JSON header (timing data) and the gzip-compressed slice pixels.
 * Note: this is Sprint Finish's own layout. SprintTimer also uses .bvid, but its layout isn't published,
 * so files are not yet interchangeable with SprintTimer.
 */
object BvidFile {
    private val MAGIC = byteArrayOf('S'.code.toByte(), 'F'.code.toByte(), 'B'.code.toByte(), 'V'.code.toByte())
    private const val VERSION = 1

    fun write(file: File, rec: Recording) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 1 shl 16)).use { out ->
            out.write(MAGIC)
            out.writeInt(VERSION)
            val meta = header(rec)
            val bytes = meta.toString().toByteArray(Charsets.UTF_8)
            out.writeInt(bytes.size)
            out.write(bytes)
            val z = GZIPOutputStream(out, 1 shl 16)
            for (c in rec.clips) for (s in c.slices) z.write(s)
            z.finish()
        }
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    }

    private fun header(rec: Recording): JSONObject {
        val meta = JSONObject()
        meta.put("app", "Sprint Finish")
        meta.put("name", rec.name)
        meta.put("fps", rec.fps)
        meta.put("sliceW", rec.sliceW)
        meta.put("height", rec.height)
        meta.put("direction", rec.direction.name)
        meta.put("startMono", rec.startMono)
        meta.put("offsetS", rec.offsetS)
        meta.put("startMode", rec.startMode)
        meta.put("createdAt", rec.createdAt)
        val clips = JSONArray()
        for (c in rec.clips) {
            val o = JSONObject()
            o.put("n", c.size)
            o.put("w", c.sliceW)
            o.put("h", c.height)
            val ts = JSONArray()
            for (t in c.ts) ts.put(t)
            o.put("ts", ts)
            clips.put(o)
        }
        meta.put("clips", clips)
        return meta
    }

    private fun readHeader(input: DataInputStream): JSONObject {
        val magic = ByteArray(4)
        input.readFully(magic)
        require(magic.contentEquals(MAGIC)) { "Not a Sprint Finish .bvid file (it may be from SprintTimer)" }
        val version = input.readInt()
        require(version <= VERSION) { "File made by a newer version" }
        val len = input.readInt()
        require(len in 1..50_000_000) { "Damaged file" }
        val bytes = ByteArray(len)
        input.readFully(bytes)
        return JSONObject(String(bytes, Charsets.UTF_8))
    }

    fun read(file: File): Recording {
        DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { input ->
            val meta = readHeader(input)
            val rec = Recording(
                name = meta.optString("name", file.nameWithoutExtension),
                fps = meta.optInt("fps", 240),
                sliceW = meta.optInt("sliceW", 1),
                height = meta.optInt("height", 720),
                direction = runCatching { Direction.valueOf(meta.optString("direction")) }.getOrDefault(Direction.LEFT_TO_RIGHT),
                startMono = meta.optLong("startMono", 0L),
                offsetS = meta.optDouble("offsetS", 0.0),
                startMode = meta.optString("startMode", ""),
                createdAt = meta.optLong("createdAt", file.lastModified()),
            )
            val z = DataInputStream(GZIPInputStream(input, 1 shl 16))
            val clips = meta.optJSONArray("clips") ?: JSONArray()
            for (i in 0 until clips.length()) {
                val o = clips.getJSONObject(i)
                val c = Clip(o.optInt("w", rec.sliceW), o.optInt("h", rec.height))
                val ts = o.getJSONArray("ts")
                val n = o.optInt("n", ts.length())
                for (k in 0 until n) {
                    c.ts.add(ts.getLong(k))
                    val arr = ByteArray(c.sliceW * c.height * 4)
                    z.readFully(arr)
                    c.slices.add(arr)
                }
                rec.clips.add(c)
            }
            return rec
        }
    }

    fun info(file: File): RaceInfo? = try {
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            val meta = readHeader(input)
            val clips = meta.optJSONArray("clips") ?: JSONArray()
            var slices = 0
            for (i in 0 until clips.length()) slices += clips.getJSONObject(i).optInt("n", 0)
            RaceInfo(
                file = file,
                name = meta.optString("name", file.nameWithoutExtension),
                createdAt = meta.optLong("createdAt", file.lastModified()),
                fps = meta.optInt("fps", 0),
                clips = clips.length(),
                slices = slices,
                marks = readMarks(marksFile(file)).second.size,
                sizeBytes = file.length(),
            )
        }
    } catch (e: Exception) { null }

    fun marksFile(bvid: File) = File(bvid.parentFile, bvid.nameWithoutExtension + ".marks.json")

    fun writeMarks(file: File, heat: String, marks: List<Mark>) {
        val o = JSONObject()
        o.put("heat", heat)
        val a = JSONArray()
        for (m in marks) {
            a.put(JSONObject().put("clip", m.clip).put("slice", m.slice).put("time", m.time)
                .put("lane", m.lane).put("athlete", m.athlete))
        }
        o.put("marks", a)
        file.writeText(o.toString(1))
    }

    fun readMarks(file: File): Pair<String, List<Mark>> {
        if (!file.exists()) return "" to emptyList()
        return try {
            val o = JSONObject(file.readText())
            val a = o.optJSONArray("marks") ?: JSONArray()
            val list = (0 until a.length()).map {
                val m = a.getJSONObject(it)
                Mark(m.optInt("clip"), m.optInt("slice"), m.optDouble("time"), m.optString("lane"), m.optString("athlete"))
            }
            o.optString("heat", "") to list
        } catch (e: Exception) { "" to emptyList() }
    }
}
