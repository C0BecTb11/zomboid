package com.c0bectb11.zomboid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/** Спрайты Kenney «Top-down Shooter» (CC0). */
class Sprites(ctx: Context) {
    private val map = HashMap<String, Bitmap>()
    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val src = Rect()
    private val dst = RectF()

    init {
        val am = ctx.assets
        for (f in am.list("kenney") ?: emptyArray()) {
            if (!f.endsWith(".png")) continue
            try { am.open("kenney/$f").use { map[f.removeSuffix(".png")] = BitmapFactory.decodeStream(it) } } catch (_: Exception) {}
        }
    }

    operator fun get(name: String): Bitmap? = map[name]

    /** Тайл целиком в клетку (с округлением, чтобы не было щелей) */
    fun tile(c: Canvas, name: String, sx: Float, sy: Float, ts: Float): Boolean {
        val b = map[name] ?: return false
        src.set(0, 0, b.width, b.height)
        dst.set(Math.floor(sx.toDouble()).toFloat(), Math.floor(sy.toDouble()).toFloat(),
            Math.ceil((sx + ts).toDouble()).toFloat(), Math.ceil((sy + ts).toDouble()).toFloat())
        c.drawBitmap(b, src, dst, paint)
        return true
    }

    /** Спрайт с центром вращения (px, py) в пикселях исходника, уже повернутый канвасом */
    fun pivot(c: Canvas, name: String, px: Float, py: Float, scale: Float, p: Paint = paint): Boolean {
        val b = map[name] ?: return false
        src.set(0, 0, b.width, b.height)
        dst.set(-px * scale, -py * scale, (b.width - px) * scale, (b.height - py) * scale)
        c.drawBitmap(b, src, dst, p)
        return true
    }
}
