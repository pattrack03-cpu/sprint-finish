package com.topsprintspeed.finish

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File
import java.io.FileOutputStream

/** Photo-finish PNG (time ruler + marks burned in) and results CSV, for sharing. */
object Exporter {
    private fun dir(ctx: Context) = File(ctx.cacheDir, "exports").apply { mkdirs() }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifEmpty { "race" }

    fun png(ctx: Context, s: ReviewSession, image: Bitmap): File {
        val ruler = 44
        val header = 56
        val maxW = 16000
        val src = if (image.width > maxW) Bitmap.createBitmap(image, 0, 0, maxW, image.height) else image
        val out = Bitmap.createBitmap(src.width, src.height + ruler + header, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(11, 10, 11))
        c.drawBitmap(src, 0f, header.toFloat(), null)

        val gold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(197, 179, 88); textSize = 26f }
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(245, 241, 232); textSize = 20f }
        val line = Paint().apply { color = Color.rgb(240, 216, 106); strokeWidth = 2f }
        val tick = Paint().apply { color = Color.rgb(173, 163, 154); strokeWidth = 1f }

        c.drawText("${s.rec.name}  ·  ${s.heat}  ·  ${s.rec.fps} fps  ·  clip ${s.clipIndex + 1}", 12f, 36f, gold)

        val top = (header + src.height).toFloat()
        for ((x, t) in s.ticks) {
            if (x >= src.width) continue
            val whole = Math.abs(t * 10 - Math.round(t * 10)) < 1e-6 && Math.round(t * 10) % 10 == 0L
            val len = if (whole) 18f else 10f
            c.drawLine(x.toFloat(), top, x.toFloat(), top + len, tick)
            c.drawText(String.format(java.util.Locale.US, "%.1f", t), x + 3f, top + 34f, ink)
        }
        val clip = s.rec.clips[s.clipIndex]
        var place = 0
        for (m in s.marks.sortedBy { it.time }) {
            place++
            if (m.clip != s.clipIndex) continue
            val x = TimeMath.sliceX(m.slice, clip.sliceW, clip.size, s.rec.direction) + clip.sliceW / 2f
            if (x >= src.width) continue
            c.drawLine(x, header.toFloat(), x, top, line)
            val label = "$place. ${m.lane} ${m.athlete}".trim() + "  " + TimeMath.format(TimeMath.officialRoundUp(m.time))
            c.drawText(label, x + 4f, header + 22f + (place % 3) * 24f, gold)
        }

        val f = File(dir(ctx), safe(s.rec.name + "_" + s.heat) + "_clip${s.clipIndex + 1}.png")
        FileOutputStream(f).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    fun csv(ctx: Context, s: ReviewSession): File {
        val sb = StringBuilder("Place,Lane,Athlete,Time,Raw time,Heat,Race\n")
        s.marks.sortedBy { it.time }.forEachIndexed { i, m ->
            fun q(v: String) = "\"" + v.replace("\"", "\"\"") + "\""
            sb.append(i + 1).append(',').append(q(m.lane)).append(',').append(q(m.athlete)).append(',')
                .append(TimeMath.format(TimeMath.officialRoundUp(m.time))).append(',')
                .append(TimeMath.format(m.time, 3)).append(',').append(q(s.heat)).append(',').append(q(s.rec.name))
                .append('\n')
        }
        val f = File(dir(ctx), safe(s.rec.name + "_" + s.heat) + ".csv")
        f.writeText(sb.toString())
        return f
    }
}
