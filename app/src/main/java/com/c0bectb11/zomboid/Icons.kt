package com.c0bectb11.zomboid

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF

/** Иконки предметов в плоском стиле Kenney: заливка + тёмный контур. Рисуются один раз в битмап 96×96. */
object Icons {
    private val cache = HashMap<String, Bitmap>()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val bp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val src = Rect()
    private val dst = RectF()
    private const val OUT = 0xFF2B2B33.toInt()

    fun draw(c: Canvas, id: String, cx: Float, cy: Float, size: Float) {
        val b = cache.getOrPut(id) { render(id) }
        src.set(0, 0, b.width, b.height)
        dst.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        c.drawBitmap(b, src, dst, bp)
    }

    private fun f(c: Canvas, col: Int, block: (Paint) -> Unit) { fill.color = col; block(fill) }
    private fun o(c: Canvas, w: Float = 4f, block: (Paint) -> Unit) { line.color = OUT; line.strokeWidth = w; block(line) }

    private fun rr(c: Canvas, l: Float, t: Float, rt: Float, b: Float, rad: Float, col: Int) {
        r.set(l, t, rt, b)
        f(c, col) { c.drawRoundRect(r, rad, rad, it) }
        o(c) { c.drawRoundRect(r, rad, rad, it) }
    }

    private fun circle(c: Canvas, x: Float, y: Float, rad: Float, col: Int, outline: Boolean = true) {
        f(c, col) { c.drawCircle(x, y, rad, it) }
        if (outline) o(c) { c.drawCircle(x, y, rad, it) }
    }

    private fun path(c: Canvas, col: Int, vararg pts: Float, outline: Boolean = true) {
        val p = Path(); p.moveTo(pts[0], pts[1])
        var i = 2; while (i < pts.size) { p.lineTo(pts[i], pts[i + 1]); i += 2 }
        p.close()
        f(c, col) { c.drawPath(p, it) }
        if (outline) o(c) { c.drawPath(p, it) }
    }

    private fun stroke(c: Canvas, col: Int, w: Float, x1: Float, y1: Float, x2: Float, y2: Float) {
        line.color = OUT; line.strokeWidth = w + 6f; c.drawLine(x1, y1, x2, y2, line)
        line.color = col; line.strokeWidth = w; c.drawLine(x1, y1, x2, y2, line)
    }

    private fun render(id: String): Bitmap {
        val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        when (id) {
            "canned" -> {
                rr(c, 26f, 22f, 70f, 80f, 6f, 0xFFB8C0C8.toInt())
                rr(c, 26f, 38f, 70f, 64f, 2f, 0xFFD04838.toInt())
                f(c, 0xFFF0E0C0.toInt()) { c.drawCircle(48f, 51f, 7f, it) }
                r.set(26f, 16f, 70f, 28f); f(c, 0xFFD8DEE4.toInt()) { c.drawOval(r, it) }; o(c) { c.drawOval(r, it) }
            }
            "chips" -> {
                path(c, 0xFFE8C030.toInt(), 28f, 16f, 68f, 16f, 74f, 82f, 22f, 82f)
                rr(c, 26f, 40f, 70f, 56f, 3f, 0xFFD03A2A.toInt())
                stroke(c, 0xFFF4E070.toInt(), 3f, 30f, 22f, 66f, 22f)
            }
            "bread" -> {
                rr(c, 14f, 34f, 82f, 70f, 18f, 0xFFC88A44.toInt())
                line.color = 0xFF8A5A28.toInt(); line.strokeWidth = 4f
                for (k in 0..2) c.drawLine(30f + k * 16f, 40f, 38f + k * 16f, 56f, line)
            }
            "apple" -> {
                circle(c, 48f, 54f, 26f, 0xFFD43030.toInt())
                f(c, 0x66FFFFFF) { c.drawCircle(38f, 44f, 7f, it) }
                stroke(c, 0xFF6A4020.toInt(), 4f, 48f, 30f, 52f, 18f)
                path(c, 0xFF4CA040.toInt(), 54f, 22f, 70f, 14f, 66f, 28f)
            }
            "water" -> {
                rr(c, 40f, 8f, 56f, 20f, 3f, 0xFF2858A8.toInt())
                path(c, 0xFF9CD4F4.toInt(), 40f, 20f, 56f, 20f, 64f, 32f, 64f, 86f, 32f, 86f, 32f, 32f)
                rr(c, 32f, 46f, 64f, 64f, 2f, 0xFF3C78C8.toInt())
            }
            "soda" -> {
                rr(c, 30f, 18f, 66f, 82f, 8f, 0xFFD83A3A.toInt())
                line.color = 0xFFFFFFFF.toInt(); line.strokeWidth = 5f
                val p = Path(); p.moveTo(32f, 56f); p.cubicTo(42f, 44f, 54f, 64f, 64f, 50f); c.drawPath(p, line)
                rr(c, 34f, 12f, 62f, 20f, 3f, 0xFFC8C8C8.toInt())
            }
            "bandage" -> {
                rr(c, 50f, 52f, 86f, 66f, 3f, 0xFFF4F4F4.toInt())
                circle(c, 42f, 48f, 26f, 0xFFF4F4F4.toInt())
                circle(c, 42f, 48f, 9f, 0xFFD8D8D8.toInt())
                line.color = 0xFFD8D8D8.toInt(); line.strokeWidth = 2.5f; c.drawCircle(42f, 48f, 17f, line)
            }
            "rag" -> {
                path(c, 0xFFB8A888.toInt(), 16f, 30f, 40f, 22f, 62f, 32f, 82f, 24f, 78f, 70f, 54f, 76f, 34f, 68f, 18f, 74f)
                line.color = 0xFF8A7A5C.toInt(); line.strokeWidth = 3f
                c.drawLine(30f, 40f, 66f, 44f, line); c.drawLine(28f, 56f, 64f, 60f, line)
            }
            "disinfectant" -> {
                rr(c, 40f, 10f, 56f, 24f, 3f, 0xFFF0F0F0.toInt())
                rr(c, 28f, 24f, 68f, 86f, 8f, 0xFF40A860.toInt())
                f(c, 0xFFFFFFFF.toInt()) { c.drawRect(44f, 40f, 52f, 70f, it); c.drawRect(33f, 51f, 63f, 59f, it) }
            }
            "tweezers" -> {
                stroke(c, 0xFFC0C4CC.toInt(), 6f, 30f, 14f, 50f, 84f)
                stroke(c, 0xFFC0C4CC.toInt(), 6f, 66f, 14f, 50f, 84f)
                circle(c, 48f, 82f, 5f, 0xFFA0A4AC.toInt())
            }
            "pills" -> {
                rr(c, 30f, 30f, 66f, 84f, 6f, 0xFFE89030.toInt())
                rr(c, 26f, 16f, 70f, 32f, 4f, 0xFFF4F4F4.toInt())
                rr(c, 34f, 46f, 62f, 66f, 2f, 0xFFF4F4F4.toInt())
            }
            "knife" -> {
                path(c, 0xFFD8DCE4.toInt(), 22f, 74f, 64f, 24f, 74f, 18f, 70f, 34f, 32f, 80f)
                stroke(c, 0xFF2A2A2A.toInt(), 9f, 20f, 76f, 30f, 66f)
            }
            "bat" -> {
                stroke(c, 0xFFC08A4C.toInt(), 16f, 66f, 22f, 44f, 54f)
                stroke(c, 0xFFC08A4C.toInt(), 8f, 44f, 54f, 22f, 82f)
                stroke(c, 0xFF2A2A2A.toInt(), 9f, 30f, 72f, 22f, 82f)
            }
            "crowbar" -> {
                stroke(c, 0xFF5A5A66.toInt(), 8f, 26f, 84f, 66f, 22f)
                stroke(c, 0xFF5A5A66.toInt(), 8f, 66f, 22f, 76f, 26f)
                stroke(c, 0xFFC03030.toInt(), 8f, 26f, 84f, 34f, 72f)
            }
            "hammer" -> {
                stroke(c, 0xFFB07A44.toInt(), 9f, 30f, 84f, 54f, 34f)
                path(c, 0xFF8A8E98.toInt(), 38f, 22f, 76f, 34f, 72f, 46f, 34f, 34f)
            }
            "pistol" -> {
                path(c, 0xFF3A3A40.toInt(), 14f, 30f, 82f, 30f, 82f, 46f, 46f, 46f, 40f, 76f, 24f, 76f, 28f, 46f, 14f, 46f)
                stroke(c, 0xFF5A5A62.toInt(), 3f, 20f, 36f, 76f, 36f)
            }
            "ammo" -> {
                rr(c, 16f, 48f, 80f, 82f, 4f, 0xFF6A7A3A.toInt())
                for (k in 0..3) {
                    val x = 26f + k * 14f
                    rr(c, x - 5f, 24f, x + 5f, 50f, 2f, 0xFFD8A830.toInt())
                    f(c, 0xFFB87030.toInt()) { c.drawCircle(x, 24f, 5f, it) }
                }
            }
            "planks" -> {
                c.save(); c.rotate(-20f, 48f, 48f)
                rr(c, 10f, 30f, 86f, 46f, 3f, 0xFFB07A44.toInt())
                rr(c, 10f, 52f, 86f, 68f, 3f, 0xFFC08A54.toInt())
                c.restore()
            }
            else -> circle(c, 48f, 48f, 26f, Items.get(id).color)
        }
        return bmp
    }
}
