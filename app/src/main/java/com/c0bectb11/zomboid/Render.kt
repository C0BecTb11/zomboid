package com.c0bectb11.zomboid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Отрисовка мира: земля, тени, постройки, существа, свет, поле зрения, крыши, эффекты. */
class WorldRenderer(ctx: Context, private val dp: Float) {
    val spr = Sprites(ctx)

    // ---------- состояние анимаций ----------
    private val doorAngle = HashMap<Int, Float>()
    private class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val max: Float,
                           val color: Int, val size: Float, var rot: Float, val vr: Float, val drag: Float = 4f)
    private val particles = ArrayList<Particle>()
    private class Decal(val x: Float, val y: Float, val rot: Float, val scale: Float)
    private val blood = ArrayList<Decal>()
    private class Flash(val x: Float, val y: Float, var life: Float)
    private val flashes = ArrayList<Flash>()
    private val rnd = java.util.Random()
    private val walk = HashMap<Int, FloatArray>()      // id игрока → [фаза, прошлый x, прошлый y]

    private var world: World? = null
    private var vis = FloatArray(0)
    private var visTarget = ByteArray(0)
    private var visT = 0f
    private var roofA = FloatArray(0)
    private val lamps = ArrayList<FloatArray>()      // [x, y, headX, headY]

    var camX = Float.NaN; var camY = 0f
    private var shake = 0f
    var hurtFlash = 0f
    var vibrate = 0
    var ts = 1f; var ox = 0f; var oy = 0f

    // ---------- краски ----------
    private val pTile = Paint()
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
    private val pBlood = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = PorterDuffColorFilter(0xD0701010.toInt(), PorterDuff.Mode.SRC_IN) }
    private val pCorpse = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(zombieMatrix(0.55f)) }
    private val pZombie = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(zombieMatrix(0.85f)) }
    private val pSprite = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val pDecal = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val pCut = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private val pAdd = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val pShade = Paint()
    /** Приглушённая «постапокалиптическая» палитра для земли */
    private val pGround = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        val m = ColorMatrix(); m.setSaturation(0.62f)
        m.postConcat(ColorMatrix(floatArrayOf(0.86f, 0f, 0f, 0f, 4f, 0f, 0.86f, 0f, 0f, 4f, 0f, 0f, 0.84f, 0f, 6f, 0f, 0f, 0f, 1f, 0f)))
        colorFilter = ColorMatrixColorFilter(m)
    }
    private val pKitchen = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = android.graphics.LightingColorFilter(0xFFC8BFAE.toInt(), 0x080604) }
    private val wallCols = intArrayOf(0xFFDCD2C2.toInt(), 0xFFCDB99C.toInt(), 0xFFB9C1C6.toInt(), 0xFFD8BEA6.toInt(), 0xFFC9C3B0.toInt())
    private val pVig = Paint()
    private var vigW = 0f; private var vigH = 0f
    private val rect = RectF()
    private val path = Path()

    private val zombieSkins = arrayOf("z_hold", "zb_hold", "zo_hold", "zw_hold", "zh_hold", "zs_hold")
    private val carColors = intArrayOf(0xFF8E2E2A.toInt(), 0xFF2C4C7C.toInt(), 0xFFD6D6CC.toInt(), 0xFF35373B.toInt(),
        0xFF55744A.toInt(), 0xFFC89A3C.toInt(), 0xFF6A6E74.toInt())
    private val roofColors = intArrayOf(0xFF7A3B32.toInt(), 0xFF4A5560.toInt(), 0xFF6B5A44.toInt(), 0xFF3F5A4A.toInt(), 0xFF5E3F3A.toInt())

    companion object {
        /** Матрица «трупного» цвета: выцветание, зелень, темнее */
        fun zombieMatrix(bright: Float): ColorMatrix {
            val m = ColorMatrix(); m.setSaturation(0.25f)
            val tint = ColorMatrix(floatArrayOf(
                0.8f * bright, 0f, 0f, 0f, 4f,
                0f, 1.0f * bright, 0f, 0f, 14f,
                0f, 0f, 0.72f * bright, 0f, 0f,
                0f, 0f, 0f, 1f, 0f))
            m.postConcat(tint); return m
        }
        fun hash(x: Int, y: Int): Int { var h = x * 374761393 + y * 668265263; h = (h xor (h ushr 13)) * 1274126177; return (h xor (h ushr 16)) and 0x7fffffff }
    }

    private fun attach(w: World) {
        world = w
        vis = FloatArray(w.w * w.h); visTarget = ByteArray(w.w * w.h)
        roofA = FloatArray(w.buildings.size) { 1f }
        lamps.clear()
        for (y in 0 until w.h) for (x in 0 until w.w) {
            if (w.t(x, y) != T.SIDEWALK) continue
            val roadUp = w.t(x, y - 1) == T.ROAD; val roadDown = w.t(x, y + 1) == T.ROAD
            val roadL = w.t(x - 1, y) == T.ROAD; val roadR = w.t(x + 1, y) == T.ROAD
            if ((roadUp || roadDown) && !(roadL || roadR) && x % 9 == 4) {
                lamps.add(floatArrayOf(x + 0.5f, y + 0.5f, x + 0.5f, y + 0.5f + if (roadUp) -0.55f else 0.55f))
            } else if ((roadL || roadR) && !(roadUp || roadDown) && y % 9 == 4) {
                lamps.add(floatArrayOf(x + 0.5f, y + 0.5f, x + 0.5f + if (roadL) -0.55f else 0.55f, y + 0.5f))
            }
        }
    }

    // ================== ОБНОВЛЕНИЕ ==================
    fun update(dt: Float, gm: Game, me: Player, localPid: Int) {
        val w = gm.world
        if (world !== w || roofA.size != w.buildings.size) attach(w)
        // события клеток
        if (w.events.isNotEmpty()) { for (ev in w.events) onTileEvent(w, ev[0], ev[1], ev[2]); w.events.clear() }
        // эффекты боя
        if (gm.fx.isNotEmpty()) { for (f in gm.fx) onFx(f, localPid, me); gm.fx.clear() }
        // двери
        if (doorAngle.isNotEmpty()) {
            val di = doorAngle.entries.iterator()
            while (di.hasNext()) {
                val e = di.next()
                val target = when (w.tAt(e.key)) { T.DOOR_OPEN -> 90f; T.DOOR -> 0f; else -> { di.remove(); continue } }
                val v = e.value; val step = dt * 420f
                if (abs(target - v) <= step) di.remove() else e.setValue(v + if (target > v) step else -step)
            }
        }
        // частицы
        val pi = particles.iterator()
        while (pi.hasNext()) {
            val pt = pi.next()
            pt.life -= dt
            if (pt.life <= 0f) { pi.remove(); continue }
            pt.x += pt.vx * dt; pt.y += pt.vy * dt
            val fr = 1f - min(1f, dt * pt.drag); pt.vx *= fr; pt.vy *= fr
            pt.rot += pt.vr * dt * (pt.vx * pt.vx + pt.vy * pt.vy).coerceAtMost(1f)
        }
        flashes.removeAll { it.life -= dt; it.life <= 0f }
        // поле зрения
        visT -= dt
        if (visT <= 0f) { visT = 0.1f; computeVis(gm, me) }
        val k = min(1f, dt * 7f)
        val r = 24
        val px = floor(me.rx).toInt(); val py = floor(me.ry).toInt()
        for (y in max(0, py - r)..min(w.h - 1, py + r)) for (x in max(0, px - r)..min(w.w - 1, px + r)) {
            val i = y * w.w + x
            vis[i] += (visTarget[i] - vis[i]) * k
        }
        // крыши: снимаем крышу здания, в котором стоит игрок
        val inside = w.buildingOf.getOrElse(w.idx(px.coerceIn(0, w.w - 1), py.coerceIn(0, w.h - 1))) { -1 }
        val insideFloor = inside >= 0 && w.t(px, py) != T.WALL
        for (b in roofA.indices) {
            val target = if (insideFloor && b == inside) 0f else 1f
            roofA[b] += (target - roofA[b]) * min(1f, dt * 6f)
        }
        // камера: плавно, с упреждением по взгляду
        val tx = me.rx + me.fx * 0.8f; val ty = me.ry + me.fy * 0.8f
        if (camX.isNaN() || hypot(tx - camX, ty - camY) > 6f) { camX = tx; camY = ty }
        val ck = min(1f, dt * 5f)
        camX += (tx - camX) * ck; camY += (ty - camY) * ck
        shake = max(0f, shake - dt * 2.5f)
        hurtFlash = max(0f, hurtFlash - dt * 1.4f)
        // походка
        for (p in gm.players) {
            val st = walk.getOrPut(p.id) { floatArrayOf(0f, p.rx, p.ry) }
            val d = hypot(p.rx - st[1], p.ry - st[2])
            if (d < 1f) st[0] += d * 7.5f
            st[1] = p.rx; st[2] = p.ry
        }
    }

    private fun computeVis(gm: Game, me: Player) {
        val w = gm.world
        java.util.Arrays.fill(visTarget, 0)
        if (me.dead) { // мёртвый видит всё вокруг
            val px = floor(me.rx).toInt(); val py = floor(me.ry).toInt()
            for (y in max(0, py - 20)..min(w.h - 1, py + 20)) for (x in max(0, px - 20)..min(w.w - 1, px + 20)) visTarget[y * w.w + x] = 1
            return
        }
        val dark = gm.darkness()
        val R = 22f - 8f * dark
        val ri = R.toInt() + 1
        val px = floor(me.rx).toInt(); val py = floor(me.ry).toInt()
        for (y in max(0, py - ri)..min(w.h - 1, py + ri)) for (x in max(0, px - ri)..min(w.w - 1, px + ri)) {
            val cx = x + 0.5f; val cy = y + 0.5f
            val dx = cx - me.rx; val dy = cy - me.ry
            val d = hypot(dx, dy)
            if (d > R) continue
            if (d > 2.2f) {
                val dot = (dx * me.fx + dy * me.fy) / d
                if (dot < -0.3f) continue           // за спиной не видно
            }
            if (d < 1.2f || w.los(me.rx, me.ry, cx, cy)) visTarget[y * w.w + x] = 1
        }
    }

    private fun onTileEvent(w: World, i: Int, from: Int, to: Int) {
        val cx = i % w.w + 0.5f; val cy = i / w.w + 0.5f
        if ((from == T.DOOR && to == T.DOOR_OPEN) || (from == T.DOOR_OPEN && to == T.DOOR)) {
            doorAngle[i] = doorAngle[i] ?: (if (from == T.DOOR_OPEN) 90f else 0f)
        }
        if (from == T.WINDOW && to == T.WINDOW_BROKEN) {
            burst(cx, cy, 24, 0xFFBFE8F8.toInt(), 0.07f, 3.4f, 1.6f, null)
            burst(cx, cy, 10, 0xFFFFFFFF.toInt(), 0.04f, 4f, 0.9f, null)
        }
        if (T.isDoorLike(from) && to == T.DOOR_BROKEN) {
            burst(cx, cy, 18, 0xFFA97A48.toInt(), 0.09f, 2.6f, 1.8f, null)
            burst(cx, cy, 8, 0xFF6E4A2A.toInt(), 0.06f, 3f, 1.4f, null)
        }
    }

    private fun burst(cx: Float, cy: Float, n: Int, col: Int, size: Float, speed: Float, life: Float, dir: Float?, spread: Float = 0.9f) {
        repeat(n) {
            val a = if (dir == null) rnd.nextFloat() * 6.283f else dir + (rnd.nextFloat() - 0.5f) * 2f * spread
            val sp = speed * (0.3f + rnd.nextFloat())
            particles.add(Particle(cx + (rnd.nextFloat() - 0.5f) * 0.4f, cy + (rnd.nextFloat() - 0.5f) * 0.4f,
                cos(a) * sp, sin(a) * sp, life * (0.6f + rnd.nextFloat() * 0.6f), life * 1.2f,
                col, size * (0.5f + rnd.nextFloat()), rnd.nextFloat() * 360f, (rnd.nextFloat() - 0.5f) * 900f))
        }
        if (particles.size > 500) particles.subList(0, particles.size - 500).clear()
    }

    private fun addBlood(x: Float, y: Float, scale: Float) {
        blood.add(Decal(x + (rnd.nextFloat() - 0.5f) * 0.3f, y + (rnd.nextFloat() - 0.5f) * 0.3f, rnd.nextFloat() * 360f, scale))
        if (blood.size > 260) blood.removeAt(0)
    }

    private fun onFx(f: FloatArray, localPid: Int, me: Player) {
        val x = f[1]; val y = f[2]
        when (f[0].toInt()) {
            1 -> { burst(x, y, 14, 0xFFA01010.toInt(), 0.05f, 3f, 0.6f, f[3], 0.6f); if (rnd.nextFloat() < 0.5f) addBlood(x + cos(f[3]) * 0.4f, y + sin(f[3]) * 0.4f, 0.35f) }
            2 -> { burst(x, y, 26, 0xFF8A0E0E.toInt(), 0.06f, 2.2f, 0.8f, null); addBlood(x, y, 0.8f) }
            3 -> {
                burst(x, y, 10, 0xFFB01818.toInt(), 0.05f, 2f, 0.5f, null)
                if (rnd.nextFloat() < 0.6f) addBlood(x, y, 0.4f)
                if (f[3].toInt() == localPid) { hurtFlash = 1f; shake = max(shake, 0.5f); vibrate = 60 }
            }
            4 -> {
                flashes.add(Flash(x, y, 0.09f))
                burst(x, y, 6, 0xFFFFE070.toInt(), 0.03f, 5f, 0.15f, atan2(me.fy, me.fx), 0.3f)
                if (f[3].toInt() == localPid) { shake = max(shake, 0.35f); vibrate = 25 }
            }
        }
    }

    fun screenX(wx: Float) = ox + wx * ts
    fun screenY(wy: Float) = oy + wy * ts

    // ================== ОТРИСОВКА ==================
    fun draw(c: Canvas, gm: Game, me: Player, W: Float, H: Float, target: Int) {
        val w = gm.world
        if (world !== w) attach(w)
        ts = H / 11f
        val sh = shake * shake
        val sx0 = (rnd.nextFloat() - 0.5f) * sh * ts * 0.35f; val sy0 = (rnd.nextFloat() - 0.5f) * sh * ts * 0.35f
        ox = W / 2 - camX * ts + sx0; oy = H / 2 - camY * ts + sy0
        val x0 = max(0, floor(camX - W / 2 / ts).toInt() - 2); val x1 = min(w.w - 1, floor(camX + W / 2 / ts).toInt() + 2)
        val y0 = max(0, floor(camY - H / 2 / ts).toInt() - 2); val y1 = min(w.h - 1, floor(camY + H / 2 / ts).toInt() + 2)

        // 1. земля
        for (y in y0..y1) for (x in x0..x1) drawGround(c, w, x, y, ox + x * ts, oy + y * ts)
        // 2. тени (в отдельном слое, чтобы не накладывались)
        c.saveLayerAlpha(0f, 0f, W, H, 72)
        pTile.color = Color.BLACK; pFill.color = Color.BLACK
        val so = ts * 0.22f
        for (y in y0..y1) for (x in x0..x1) {
            val t = w.t(x, y); val sx = ox + x * ts; val sy = oy + y * ts
            when {
                t == T.WALL || T.isWindowLike(t) -> { c.save(); c.translate(so, so * 1.2f); wallShape(c, w, x, y, sx, sy, 0f, pTile); c.restore() }
                T.isFurniture(t) -> c.drawRect(sx + ts * 0.15f, sy + ts * 0.2f, sx + ts * 1.05f, sy + ts * 1.08f, pTile)
                t == T.TREE -> c.drawCircle(sx + ts * 0.9f, sy + ts * 0.95f, ts * 0.85f, pFill)
                t == T.CAR -> { rect.set(sx + ts * 0.18f, sy + ts * 0.18f, sx + ts * 2.12f, sy + ts * 1.08f); c.drawRoundRect(rect, ts * 0.3f, ts * 0.3f, pFill) }
                t == T.CAR_V -> { rect.set(sx + ts * 0.18f, sy + ts * 0.18f, sx + ts * 1.08f, sy + ts * 2.12f); c.drawRoundRect(rect, ts * 0.3f, ts * 0.3f, pFill) }
            }
        }
        for ((bi, b) in w.buildings.withIndex()) {
            if (bi >= roofA.size || roofA[bi] < 0.5f || b[2] < x0 - 1 || b[0] > x1 + 1 || b[3] < y0 - 1 || b[1] > y1 + 1) continue
            val l = ox + b[0] * ts; val tp = oy + b[1] * ts; val r = ox + (b[2] + 1) * ts; val bt = oy + (b[3] + 1) * ts
            c.drawRect(l + ts * 0.45f, tp + ts * 0.5f, r + ts * 0.45f, bt + ts * 0.5f, pTile)
        }
        c.restore()
        // 3. постройки
        for (y in y0..y1) for (x in x0..x1) drawStructure(c, w, x, y, ox + x * ts, oy + y * ts)
        // 4. кровь и трупы
        for (d in blood) {
            if (d.x < x0 || d.x > x1 + 1 || d.y < y0 || d.y > y1 + 1) continue
            c.save(); c.translate(ox + d.x * ts, oy + d.y * ts); c.rotate(d.rot)
            spr.pivot(c, "t320", 32f, 32f, ts / 64f * d.scale, pBlood); c.restore()
        }
        for (cp in gm.corpses) {
            if (cp.x < x0 - 1 || cp.x > x1 + 1 || cp.y < y0 - 1 || cp.y > y1 + 1) continue
            val sx = ox + cp.x * ts; val sy = oy + cp.y * ts
            val rot = ((cp.x * 97f + cp.y * 31f) % 360f)
            c.save(); c.translate(sx, sy); c.rotate(rot)
            spr.pivot(c, "t320", 32f, 32f, ts / 64f * 0.95f, pBlood)
            c.rotate(90f); c.scale(1f, 0.92f)
            val skin = if (cp.zombie) zombieSkins[((cp.x * 13 + cp.y * 7).toInt() and 0x7fffffff) % zombieSkins.size] else "p0_hold"
            spr.pivot(c, skin, 17f, 21.5f, ts * 0.7f / 43f, pCorpse)
            c.restore()
        }
        // 5. существа
        val visOf = { x: Float, y: Float -> val ix = floor(x).toInt(); val iy = floor(y).toInt(); if (w.inside(ix, iy)) vis[w.idx(ix, iy)] else 0f }
        for (z in gm.zombies) {
            if (z.rx < x0 - 1 || z.rx > x1 + 1 || z.ry < y0 - 1 || z.ry > y1 + 1) continue
            val a = visOf(z.rx, z.ry)
            if (a < 0.05f) continue
            drawZombie(c, z, ox + z.rx * ts, oy + z.ry * ts, a)
        }
        for (p in gm.players) {
            if (p.dead || !p.connected) continue
            drawPlayer(c, p, ox + p.rx * ts, oy + p.ry * ts)
        }
        // частицы и выстрелы
        for (pt in particles) {
            val a = (pt.life / pt.max).coerceIn(0f, 1f)
            pFill.color = (pt.color and 0x00FFFFFF) or ((a * 255).toInt() shl 24)
            c.save(); c.translate(ox + pt.x * ts, oy + pt.y * ts); c.rotate(pt.rot)
            val sz = pt.size * ts
            c.drawRect(-sz, -sz * 0.45f, sz, sz * 0.45f, pFill)
            c.restore()
        }
        for (s in gm.shots) {
            pLine.shader = LinearGradient(ox + s[0] * ts, oy + s[1] * ts, ox + s[2] * ts, oy + s[3] * ts, 0xFFFFF0A0.toInt(), 0x00FFE070, Shader.TileMode.CLAMP)
            pLine.strokeWidth = 3f * dp
            c.drawLine(ox + s[0] * ts, oy + s[1] * ts, ox + s[2] * ts, oy + s[3] * ts, pLine)
            pLine.shader = null
        }
        drawLamps(c, gm, x0, x1, y0, y1)
        // 6. кроны
        for (y in y0..y1) for (x in x0..x1) if (w.t(x, y) == T.TREE) {
            val sx = ox + (x + 0.5f) * ts; val sy = oy + (y + 0.5f) * ts
            val hv = hash(x, y)
            val name = if (hv % 7 == 0) "t186" else "t183"
            c.save(); c.translate(sx, sy); c.rotate((hash(y, x) % 360).toFloat())
            if (!spr.pivot(c, name, 32f, 32f, ts * (1.6f + (hv % 5) * 0.06f) / 64f)) { pFill.color = 0xFF2C5222.toInt(); c.drawCircle(0f, 0f, ts * 0.85f, pFill) }
            c.restore()
        }
        // 7. туман: всё, что вне поля зрения, темнее
        for (y in y0..y1) for (x in x0..x1) {
            val v = vis[w.idx(x, y)]
            if (v > 0.97f) continue
            pTile.color = Color.argb(((1f - v) * 165).toInt(), 6, 8, 14)
            c.drawRect(ox + x * ts, oy + y * ts, ox + (x + 1) * ts + 0.5f, oy + (y + 1) * ts + 0.5f, pTile)
        }
        // 8. крыши
        for ((bi, b) in w.buildings.withIndex()) {
            if (bi >= roofA.size || roofA[bi] < 0.02f) continue
            if (b[2] < x0 - 1 || b[0] > x1 + 1 || b[3] < y0 - 1 || b[1] > y1 + 1) continue
            drawRoof(c, bi, b, roofA[bi])
        }
        // подсветка цели действия
        if (target >= 0) {
            val tx = target % w.w; val ty = target / w.w
            var rw = 1f; var rh = 1f
            when (w.tAt(target)) { T.CAR -> rw = 2f; T.CAR_V -> rh = 2f }
            val pulse = 0.55f + 0.45f * sin(System.currentTimeMillis() / 180f)
            pLine.color = Color.argb((90 + 120 * pulse).toInt(), 255, 236, 170); pLine.strokeWidth = 2.5f * dp
            rect.set(ox + tx * ts + 3, oy + ty * ts + 3, ox + (tx + rw) * ts - 3, oy + (ty + rh) * ts - 3)
            c.drawRoundRect(rect, 7 * dp, 7 * dp, pLine)
        }
        // 9. свет и время суток
        drawLighting(c, gm, W, H, x0, x1, y0, y1)
        // прогресс действий над головой
        for (p in gm.players) {
            if (p.dead || !p.connected || p.taAct == null) continue
            val sx = ox + p.rx * ts; val sy = oy + p.ry * ts - ts * 0.75f
            val r = ts * 0.22f
            pFill.color = 0xAA101214.toInt(); c.drawCircle(sx, sy, r + 3 * dp, pFill)
            pLine.color = 0xFFF0E0A0.toInt(); pLine.strokeWidth = 3.5f * dp
            rect.set(sx - r, sy - r, sx + r, sy + r); c.drawArc(rect, -90f, 360f * p.taProgress, false, pLine)
            pText.textSize = 11 * dp; pText.color = Color.WHITE
            c.drawText(p.taName, sx, sy - r - 8 * dp, pText)
        }
        // 10. виньетка и вспышка боли
        if (vigW != W || vigH != H) {
            vigW = W; vigH = H
            pVig.shader = RadialGradient(W / 2, H / 2, hypot(W, H) / 2, intArrayOf(0x00000000, 0x00000000, 0x80000000.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, W, H, pVig)
        val low = if (me.hp < 30f && !me.dead) (0.35f + 0.25f * sin(System.currentTimeMillis() / 220f)) * (1f - me.hp / 30f) else 0f
        val hf = max(hurtFlash, low)
        if (hf > 0.01f) {
            pShade.shader = RadialGradient(W / 2, H / 2, hypot(W, H) / 2, intArrayOf(0x00000000, Color.argb((hf * 170).toInt(), 160, 0, 0)), floatArrayOf(0.35f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, W, H, pShade); pShade.shader = null
        }
    }

    // ---------- земля ----------
    private fun drawGround(c: Canvas, w: World, x: Int, y: Int, sx: Float, sy: Float) {
        val t = w.t(x, y)
        when {
            wallLike(t) -> groundUnderWall(c, w, x, y, sx, sy)
            T.isFurniture(t) -> floorUnder(c, w, x, y, sx, sy)
            else -> groundType(c, w, if (T.isCar(t)) T.ROAD else t, x, y, sx, sy)
        }
    }

    private fun groundType(c: Canvas, w: World, t: Int, x: Int, y: Int, sx: Float, sy: Float) {
        val hv = hash(x, y)
        when {
            t == T.GRASS || t == T.TREE -> {
                if (!spr.tile(c, arrayOf("t01", "t02", "t03", "t04")[hv % 4], sx, sy, ts, pGround)) fill(c, sx, sy, 0xFF3D5C2C.toInt())
                when (hv % 29) {
                    0, 1 -> decal(c, arrayOf("t237", "t238", "t239")[hv % 3], sx + ts * 0.5f, sy + ts * 0.5f, 0.32f, hv)
                    2, 3, 4 -> decal(c, "t213", sx + ts * 0.5f, sy + ts * 0.5f, 0.7f, hv)
                    5 -> decal(c, "t161", sx + ts * 0.5f, sy + ts * 0.5f, 0.5f, hv)
                }
            }
            t == T.ROAD -> {
                val road = { tt: Int -> tt == T.ROAD || T.isCar(tt) }
                val cy = y % 25 == 1; val cx = x % 25 == 1
                val vroad = road(w.t(x, y - 1)) && road(w.t(x, y + 1)) && (w.t(x - 1, y) == T.SIDEWALK || w.t(x + 1, y) == T.SIDEWALK || x % 25 == 1)
                val name = when {
                    cy && !cx && x % 2 == 0 && !vroad -> "t56"
                    cx && !cy && y % 2 == 0 && vroad -> "t55"
                    else -> "t86"
                }
                if (!spr.tile(c, name, sx, sy, ts, pGround)) fill(c, sx, sy, 0xFF38383B.toInt())
                when (hv % 17) {
                    0 -> { pLine.color = 0x55101010; pLine.strokeWidth = 1.5f * dp
                        path.reset(); path.moveTo(sx + ts * 0.1f, sy + ts * 0.3f); path.lineTo(sx + ts * 0.4f, sy + ts * 0.45f); path.lineTo(sx + ts * 0.55f, sy + ts * 0.4f); path.lineTo(sx + ts * 0.85f, sy + ts * 0.7f)
                        c.drawPath(path, pLine) }
                    1 -> { pFill.color = 0x30000000; rect.set(sx + ts * 0.15f, sy + ts * 0.3f, sx + ts * 0.8f, sy + ts * 0.75f); c.drawOval(rect, pFill) }
                }
            }
            t == T.SIDEWALK -> {
                if (!spr.tile(c, if (hv % 5 == 0) "t09" else if (hv % 2 == 0) "t07" else "t08", sx, sy, ts, pGround)) fill(c, sx, sy, 0xFF85857F.toInt())
                if (hv % 31 == 0) { c.save(); c.translate(sx + ts * 0.5f, sy + ts * 0.5f); c.rotate((hv % 90).toFloat())
                    pFill.color = 0xDDE8E4D8.toInt(); c.drawRect(-ts * 0.12f, -ts * 0.08f, ts * 0.12f, ts * 0.08f, pFill); c.restore() }
            }
            T.isFloor(t) -> floorTile(c, w, t, x, y, sx, sy)
            else -> spr.tile(c, "t01", sx, sy, ts, pGround)
        }
    }

    private fun fill(c: Canvas, sx: Float, sy: Float, col: Int) { pTile.color = col; c.drawRect(sx, sy, sx + ts + 0.5f, sy + ts + 0.5f, pTile) }

    private fun decal(c: Canvas, name: String, cx: Float, cy: Float, scale: Float, hv: Int) {
        c.save(); c.translate(cx + ((hv shr 3) % 9 - 4) * ts * 0.04f, cy + ((hv shr 6) % 9 - 4) * ts * 0.04f); c.rotate((hv % 360).toFloat())
        spr.pivot(c, name, 32f, 32f, ts / 64f * scale, pDecal); c.restore()
    }

    private fun floorTile(c: Canvas, w: World, t: Int, x: Int, y: Int, sx: Float, sy: Float) {
        val hv = hash(x, y)
        val ok = if (t == T.TILEFLOOR) spr.tile(c, "t12", sx, sy, ts, pKitchen) else spr.tile(c, arrayOf("t43", "t45", "t47")[hv % 3], sx, sy, ts, pGround)
        if (!ok) fill(c, sx, sy, if (t == T.TILEFLOOR) 0xFFC8C2B4.toInt() else 0xFF8B6A43.toInt())
        if (t == T.WOOD && hv % 47 == 0) spr.tile(c, "t511", sx, sy, ts, pGround)
        // мягкая тень у стен — объём
        pTile.color = 0x34000000
        if (wallLike(w.t(x, y - 1))) c.drawRect(sx, sy, sx + ts, sy + ts * 0.2f, pTile)
        if (wallLike(w.t(x - 1, y))) c.drawRect(sx, sy, sx + ts * 0.14f, sy + ts, pTile)
    }

    private fun floorUnder(c: Canvas, w: World, x: Int, y: Int, sx: Float, sy: Float) {
        var f = -1
        for (k in 0 until 4) { val n = w.t(x + World.DX4[k], y + World.DY4[k]); if (T.isFloor(n)) { f = n; break } }
        if (f < 0) { spr.tile(c, "t07", sx, sy, ts, pGround); return }
        floorTile(c, w, f, x, y, sx, sy)
    }

    /** Под тонкой стеной: снаружи — земля улицы, внутри — пол, по половинкам клетки */
    private fun groundUnderWall(c: Canvas, w: World, x: Int, y: Int, sx: Float, sy: Float) {
        var out = T.GRASS
        for (k in 0 until 4) {
            val n = w.t(x + World.DX4[k], y + World.DY4[k])
            if (!wallLike(n) && !T.isFloor(n) && !T.isFurniture(n)) { out = if (n == T.TREE) T.GRASS else n; break }
        }
        groundType(c, w, out, x, y, sx, sy)
        val h = ts / 2
        for (k in 0 until 4) {
            val n = w.t(x + World.DX4[k], y + World.DY4[k])
            val fl = if (T.isFloor(n)) n else if (T.isFurniture(n)) T.WOOD else -1
            if (fl < 0) continue
            c.save()
            when (k) { 0 -> c.clipRect(sx + h, sy, sx + ts, sy + ts); 1 -> c.clipRect(sx, sy, sx + h, sy + ts); 2 -> c.clipRect(sx, sy + h, sx + ts, sy + ts); else -> c.clipRect(sx, sy, sx + ts, sy + h) }
            floorTile(c, w, fl, x, y, sx, sy)
            c.restore()
        }
        // внутренние углы
        for (dy in intArrayOf(-1, 1)) for (dx in intArrayOf(-1, 1)) {
            val n = w.t(x + dx, y + dy)
            if (!T.isFloor(n)) continue
            if (!wallLike(w.t(x + dx, y)) || !wallLike(w.t(x, y + dy))) continue
            c.save()
            c.clipRect(if (dx < 0) sx else sx + h, if (dy < 0) sy else sy + h, if (dx < 0) sx + h else sx + ts, if (dy < 0) sy + h else sy + ts)
            floorTile(c, w, n, x, y, sx, sy)
            c.restore()
        }
    }

    // ---------- постройки ----------
    private fun wallLike(t: Int) = t == T.WALL || T.isWindowLike(t) || T.isDoorLike(t)

    /** Тонкая стена: центр + перемычки к соседним стенам */
    private fun wallShape(c: Canvas, w: World, x: Int, y: Int, sx: Float, sy: Float, grow: Float, p: Paint) {
        val half = ts * 0.17f + grow
        val cx = sx + ts / 2; val cy = sy + ts / 2
        c.drawRect(cx - half, cy - half, cx + half, cy + half, p)
        if (wallLike(w.t(x, y - 1))) c.drawRect(cx - half, sy - grow, cx + half, cy, p)
        if (wallLike(w.t(x, y + 1))) c.drawRect(cx - half, cy, cx + half, sy + ts + grow, p)
        if (wallLike(w.t(x - 1, y))) c.drawRect(sx - grow, cy - half, cx, cy + half, p)
        if (wallLike(w.t(x + 1, y))) c.drawRect(cx, cy - half, sx + ts + grow, cy + half, p)
    }

    private fun wallColor(w: World, x: Int, y: Int): Int {
        val b = w.buildingOf.getOrElse(w.idx(x.coerceIn(0, w.w - 1), y.coerceIn(0, w.h - 1))) { -1 }
        return wallCols[(if (b < 0) 0 else b) % wallCols.size]
    }

    private fun drawStructure(c: Canvas, w: World, x: Int, y: Int, sx: Float, sy: Float) {
        val t = w.t(x, y)
        val hv = hash(x, y)
        val e = sx + ts + 0.5f; val b = sy + ts + 0.5f
        fun brick() {
            val col = wallColor(w, x, y)
            val half = ts * 0.17f
            val cx = sx + ts / 2; val cy = sy + ts / 2
            // южная грань стены (видна сверху-сбоку) — объём
            val l = wallLike(w.t(x - 1, y)); val r = wallLike(w.t(x + 1, y))
            pTile.color = darker(col, 0.55f)
            c.drawRect(if (l) sx else cx - half, cy + half, if (r) sx + ts else cx + half, cy + half + ts * 0.1f, pTile)
            pTile.color = col; wallShape(c, w, x, y, sx, sy, 0f, pTile)
            // светлая кромка
            pTile.color = lighter(col, 1.12f)
            c.drawRect(if (l) sx else cx - half, cy - half, if (r) sx + ts else cx + half, cy - half + ts * 0.04f, pTile)
        }
        when (t) {
            T.WALL -> brick()
            T.DOOR, T.DOOR_OPEN, T.DOOR_BROKEN -> {
                val horiz = wallLike(w.t(x - 1, y)) || wallLike(w.t(x + 1, y))
                c.save()
                if (!horiz) c.rotate(90f, sx + ts / 2, sy + ts / 2)
                if (t == T.DOOR_BROKEN) {
                    pLine.color = 0xFF8A5E34.toInt(); pLine.strokeWidth = ts * 0.1f
                    c.drawLine(sx + ts * 0.05f, sy + ts * 0.45f, sx + ts * 0.35f, sy + ts * 0.58f, pLine)
                    c.drawLine(sx + ts * 0.65f, sy + ts * 0.42f, sx + ts * 0.95f, sy + ts * 0.55f, pLine)
                } else {
                    // косяк
                    pTile.color = darker(wallColor(w, x, y), 0.7f)
                    c.drawRect(sx, sy + ts * 0.33f, sx + ts * 0.08f, sy + ts * 0.67f, pTile); c.drawRect(sx + ts * 0.92f, sy + ts * 0.33f, sx + ts, sy + ts * 0.67f, pTile)
                    val ang = doorAngle[w.idx(x, y)] ?: (if (t == T.DOOR_OPEN) 90f else 0f)
                    val hx = sx + ts * 0.08f; val hy = sy + ts * 0.5f
                    c.rotate(-ang, hx, hy)
                    pFill.color = 0x40000000; c.drawRect(hx + ts * 0.04f, hy - ts * 0.05f, hx + ts * 0.88f, hy + ts * 0.14f, pFill)
                    pTile.color = 0xFF6E4A2A.toInt(); c.drawRect(hx, hy - ts * 0.09f, hx + ts * 0.85f, hy + ts * 0.09f, pTile)
                    pTile.color = 0xFFAE7E4A.toInt(); c.drawRect(hx + ts * 0.03f, hy - ts * 0.06f, hx + ts * 0.82f, hy + ts * 0.06f, pTile)
                    pTile.color = 0xFF96683A.toInt(); c.drawRect(hx + ts * 0.03f, hy, hx + ts * 0.82f, hy + ts * 0.06f, pTile)
                    pFill.color = 0xFFE8C860.toInt(); c.drawCircle(hx + ts * 0.72f, hy, ts * 0.04f, pFill)
                }
                c.restore()
            }
            T.WINDOW, T.WINDOW_BROKEN -> {
                brick()
                val horiz = wallLike(w.t(x - 1, y)) || wallLike(w.t(x + 1, y))
                c.save()
                if (!horiz) c.rotate(90f, sx + ts / 2, sy + ts / 2)
                val cy = sy + ts / 2
                pTile.color = 0xFFEDE6DA.toInt(); c.drawRect(sx + ts * 0.08f, cy - ts * 0.15f, sx + ts * 0.92f, cy + ts * 0.15f, pTile)
                if (t == T.WINDOW) {
                    pTile.color = 0xFF8FC8E2.toInt(); c.drawRect(sx + ts * 0.13f, cy - ts * 0.09f, sx + ts * 0.87f, cy + ts * 0.09f, pTile)
                    pTile.color = 0xCCFFFFFF.toInt(); c.drawRect(sx + ts * 0.2f, cy - ts * 0.06f, sx + ts * 0.4f, cy - ts * 0.03f, pTile)
                    pTile.color = 0xFFEDE6DA.toInt(); c.drawRect(sx + ts * 0.48f, cy - ts * 0.09f, sx + ts * 0.52f, cy + ts * 0.09f, pTile)
                } else {
                    pTile.color = 0xFF23292F.toInt(); c.drawRect(sx + ts * 0.13f, cy - ts * 0.09f, sx + ts * 0.87f, cy + ts * 0.09f, pTile)
                    pFill.color = 0xFF9ED6EE.toInt()
                    path.reset(); path.moveTo(sx + ts * 0.13f, cy - ts * 0.09f); path.lineTo(sx + ts * 0.3f, cy - ts * 0.09f); path.lineTo(sx + ts * 0.15f, cy + ts * 0.06f); path.close(); c.drawPath(path, pFill)
                    path.reset(); path.moveTo(sx + ts * 0.87f, cy + ts * 0.09f); path.lineTo(sx + ts * 0.68f, cy + ts * 0.09f); path.lineTo(sx + ts * 0.85f, cy - ts * 0.05f); path.close(); c.drawPath(path, pFill)
                }
                c.restore()
            }
            T.CAR -> drawCar(c, x, y, true)
            T.CAR_V -> drawCar(c, x, y, false)
            else -> if (T.isFurniture(t)) {
                val name = when (t) {
                    T.FRIDGE -> "t322"; T.COUNTER -> if (hv % 3 == 0) "t297" else "t294"; T.WARDROBE -> "t453"
                    T.SHELF -> "t430"; T.MEDCAB -> "t269"; T.LOCKER -> "t536"; else -> if (hv % 2 == 0) "t431" else "t432"
                }
                if (!spr.tile(c, name, sx, sy, ts)) { pFill.color = 0xFF8A6238.toInt(); rect.set(sx + ts * 0.08f, sy + ts * 0.08f, e - ts * 0.1f, b - ts * 0.1f); c.drawRoundRect(rect, ts * 0.08f, ts * 0.08f, pFill) }
                if (t == T.MEDCAB) {
                    pTile.color = 0xFFD03030.toInt()
                    c.drawRect(sx + ts * 0.45f, sy + ts * 0.3f, sx + ts * 0.55f, sy + ts * 0.7f, pTile)
                    c.drawRect(sx + ts * 0.3f, sy + ts * 0.45f, sx + ts * 0.7f, sy + ts * 0.55f, pTile)
                }
                if (w.containers[w.idx(x, y)]?.isEmpty() == true) { pFill.color = 0x48000000; c.drawRect(sx, sy, e, b, pFill) }
            }
        }
        // кучка вещей на полу
        if (!T.isContainer(t) && t != T.CAR_TAIL) {
            val pile = w.containers[w.idx(x, y)]
            if (pile != null && pile.isNotEmpty()) {
                c.save(); c.translate(sx + ts / 2, sy + ts / 2); c.rotate((hv % 40 - 20).toFloat())
                spr.pivot(c, "t157", 32f, 32f, ts / 64f * 0.85f)
                c.restore()
                Icons.draw(c, pile[0].id, sx + ts * 0.62f, sy + ts * 0.38f, ts * 0.42f)
            }
        }
        // баррикада
        val bl = w.barrAt(w.idx(x, y))
        if (bl > 0) for (k in 0 until bl) {
            c.save(); c.translate(sx + ts / 2, sy + ts / 2)
            if (k == 1) c.scale(-1f, 1f)
            if (k == 2) c.rotate(45f)
            if (!spr.pivot(c, "t265", 32f, 32f, ts / 64f * 1.05f)) { pLine.color = 0xFFA07040.toInt(); pLine.strokeWidth = ts * 0.12f; c.drawLine(-ts * 0.45f, -ts * 0.3f, ts * 0.45f, ts * 0.3f, pLine) }
            c.restore()
        }
    }

    private fun drawCar(c: Canvas, x: Int, y: Int, horiz: Boolean) {
        val hv = hash(x, y)
        val col = carColors[hv % carColors.size]
        val sx = ox + x * ts; val sy = oy + y * ts
        c.save()
        // локальные координаты: машина вдоль X, 2 клетки × 1
        if (horiz) c.translate(sx, sy) else { c.translate(sx + ts, sy); c.rotate(90f) }
        if (hv % 2 == 0) { c.translate(ts * 2f, ts); c.rotate(180f) }
        val L = ts * 1.9f; val Wd = ts * 0.84f
        val l = ts * 0.05f; val tp = (ts - Wd) / 2
        // колёса
        pFill.color = 0xFF151515.toInt()
        for (wx in floatArrayOf(l + L * 0.17f, l + L * 0.73f)) {
            rect.set(wx, tp - ts * 0.05f, wx + L * 0.14f, tp + ts * 0.1f); c.drawRoundRect(rect, 4f, 4f, pFill)
            rect.set(wx, tp + Wd - ts * 0.1f, wx + L * 0.14f, tp + Wd + ts * 0.05f); c.drawRoundRect(rect, 4f, 4f, pFill)
        }
        // кузов
        rect.set(l, tp, l + L, tp + Wd)
        pFill.color = darker(col, 0.6f); c.drawRoundRect(rect, ts * 0.22f, ts * 0.22f, pFill)
        rect.inset(ts * 0.035f, ts * 0.035f)
        pFill.color = col; c.drawRoundRect(rect, ts * 0.2f, ts * 0.2f, pFill)
        // капот (перед — справа): блик
        pFill.color = 0x22FFFFFF; rect.set(l + L * 0.72f, tp + Wd * 0.15f, l + L * 0.96f, tp + Wd * 0.85f); c.drawRoundRect(rect, ts * 0.1f, ts * 0.1f, pFill)
        // лобовое и заднее стекло, крыша
        val glass = if (hv % 5 == 0) 0xFF3A4048.toInt() else 0xFF7FA8C0.toInt()
        pFill.color = glass
        path.reset(); path.moveTo(l + L * 0.56f, tp + Wd * 0.14f); path.lineTo(l + L * 0.68f, tp + Wd * 0.2f); path.lineTo(l + L * 0.68f, tp + Wd * 0.8f); path.lineTo(l + L * 0.56f, tp + Wd * 0.86f); path.close(); c.drawPath(path, pFill)
        path.reset(); path.moveTo(l + L * 0.2f, tp + Wd * 0.18f); path.lineTo(l + L * 0.26f, tp + Wd * 0.14f); path.lineTo(l + L * 0.26f, tp + Wd * 0.86f); path.lineTo(l + L * 0.2f, tp + Wd * 0.82f); path.close(); c.drawPath(path, pFill)
        pFill.color = darker(col, 0.85f); rect.set(l + L * 0.27f, tp + Wd * 0.14f, l + L * 0.55f, tp + Wd * 0.86f); c.drawRoundRect(rect, ts * 0.08f, ts * 0.08f, pFill)
        if (hv % 5 == 0) { // разбитое стекло
            pLine.color = 0xCCD8E8F0.toInt(); pLine.strokeWidth = 1.2f * dp
            c.drawLine(l + L * 0.6f, tp + Wd * 0.3f, l + L * 0.66f, tp + Wd * 0.6f, pLine); c.drawLine(l + L * 0.58f, tp + Wd * 0.6f, l + L * 0.67f, tp + Wd * 0.45f, pLine)
        }
        // фары и стопы
        pFill.color = 0xFFF8F0C0.toInt(); c.drawCircle(l + L * 0.97f, tp + Wd * 0.2f, ts * 0.05f, pFill); c.drawCircle(l + L * 0.97f, tp + Wd * 0.8f, ts * 0.05f, pFill)
        pFill.color = 0xFFB02020.toInt(); c.drawRect(l + ts * 0.02f, tp + Wd * 0.12f, l + ts * 0.07f, tp + Wd * 0.3f, pFill); c.drawRect(l + ts * 0.02f, tp + Wd * 0.7f, l + ts * 0.07f, tp + Wd * 0.88f, pFill)
        // зеркала
        pFill.color = darker(col, 0.7f)
        c.drawRect(l + L * 0.6f, tp - ts * 0.06f, l + L * 0.65f, tp + ts * 0.02f, pFill); c.drawRect(l + L * 0.6f, tp + Wd - ts * 0.02f, l + L * 0.65f, tp + Wd + ts * 0.06f, pFill)
        // ржавчина/грязь
        if (hv % 3 == 0) { pFill.color = 0x40603818; c.drawCircle(l + L * ((hv % 50) / 60f + 0.1f), tp + Wd * 0.7f, ts * 0.12f, pFill) }
        c.restore()
    }

    private fun darker(col: Int, k: Float): Int = Color.argb(255, (Color.red(col) * k).toInt(), (Color.green(col) * k).toInt(), (Color.blue(col) * k).toInt())

    private fun drawRoof(c: Canvas, bi: Int, b: IntArray, alpha: Float) {
        val l = ox + b[0] * ts - ts * 0.08f; val tp = oy + b[1] * ts - ts * 0.08f
        val r = ox + (b[2] + 1) * ts + ts * 0.08f; val bt = oy + (b[3] + 1) * ts + ts * 0.08f
        val col = roofColors[bi % roofColors.size]
        c.saveLayerAlpha(l - 4, tp - 4, r + 4, bt + 4, (alpha * 255).toInt())
        val horiz = (r - l) >= (bt - tp)
        pTile.color = darker(col, 0.55f); c.drawRect(l, tp, r, bt, pTile)
        val inset = ts * 0.08f
        if (horiz) {
            val mid = (tp + bt) / 2
            pTile.color = lighter(col, 1.15f); c.drawRect(l + inset, tp + inset, r - inset, mid, pTile)
            pTile.color = darker(col, 0.82f); c.drawRect(l + inset, mid, r - inset, bt - inset, pTile)
            pTile.color = 0x26000000
            var yy = tp + inset + ts * 0.33f
            while (yy < bt - inset) { if (abs(yy - mid) > 2f) c.drawRect(l + inset, yy, r - inset, yy + 1.5f * dp, pTile); yy += ts * 0.33f }
            pTile.color = darker(col, 0.5f); c.drawRect(l + inset, mid - 2f * dp, r - inset, mid + 2f * dp, pTile)
        } else {
            val mid = (l + r) / 2
            pTile.color = lighter(col, 1.15f); c.drawRect(l + inset, tp + inset, mid, bt - inset, pTile)
            pTile.color = darker(col, 0.82f); c.drawRect(mid, tp + inset, r - inset, bt - inset, pTile)
            pTile.color = 0x26000000
            var xx = l + inset + ts * 0.33f
            while (xx < r - inset) { if (abs(xx - mid) > 2f) c.drawRect(xx, tp + inset, xx + 1.5f * dp, bt - inset, pTile); xx += ts * 0.33f }
            pTile.color = darker(col, 0.5f); c.drawRect(mid - 2f * dp, tp + inset, mid + 2f * dp, bt - inset, pTile)
        }
        // труба
        val hv = hash(b[0], b[1])
        val cx = l + (r - l) * (0.25f + (hv % 50) / 100f); val cy = tp + (bt - tp) * 0.3f
        pTile.color = 0xFF6A3A2A.toInt(); c.drawRect(cx, cy, cx + ts * 0.45f, cy + ts * 0.45f, pTile)
        pTile.color = 0xFF2A1A14.toInt(); c.drawRect(cx + ts * 0.08f, cy + ts * 0.08f, cx + ts * 0.37f, cy + ts * 0.37f, pTile)
        c.restore()
    }

    private fun lighter(col: Int, k: Float): Int = Color.argb(255, min(255, (Color.red(col) * k).toInt()), min(255, (Color.green(col) * k).toInt()), min(255, (Color.blue(col) * k).toInt()))

    // ---------- существа ----------
    private fun drawZombie(c: Canvas, z: Zombie, sx: Float, sy: Float, alpha: Float) {
        val a = Math.toDegrees(atan2(z.fy, z.fx).toDouble()).toFloat()
        val skin = zombieSkins[z.skin]
        val paint = if (z.skin == 0) pSprite else pZombie
        paint.alpha = (alpha * 255).toInt()
        val s = ts * 0.7f / 43f
        c.save(); c.translate(sx, sy)
        if (z.down > 0f) {
            // лежит
            c.rotate(a + 90f)
            pFill.color = Color.argb((alpha * 70).toInt(), 0, 0, 0); c.drawCircle(0f, ts * 0.05f, ts * 0.34f, pFill)
            c.scale(1f, 0.9f)
            spr.pivot(c, skin, 17f, 21.5f, s, paint)
        } else {
            val sway = sin(z.anim * 5f) * 7f
            c.rotate(a + sway)
            pFill.color = Color.argb((alpha * 70).toInt(), 0, 0, 0); c.drawCircle(ts * 0.06f, ts * 0.08f, ts * 0.3f, pFill)
            if (!spr.pivot(c, skin, 17f, 21.5f, s, paint)) { pFill.color = 0xFF5A6A48.toInt(); c.drawCircle(0f, 0f, ts * 0.28f, pFill) }
            if (z.chase > 0f) {
                pFill.color = Color.argb((alpha * 255).toInt(), 255, 40, 30)
                c.drawCircle(ts * 0.13f, -ts * 0.05f, ts * 0.025f, pFill); c.drawCircle(ts * 0.13f, ts * 0.05f, ts * 0.025f, pFill)
            }
        }
        c.restore()
        paint.alpha = 255
    }

    private fun drawPlayer(c: Canvas, p: Player, sx: Float, sy: Float) {
        val a = Math.toDegrees(atan2(p.fy, p.fx).toDouble()).toFloat()
        val ph = walk[p.id]?.get(0) ?: 0f
        val bob = sin(ph)
        c.save(); c.translate(sx, sy); c.rotate(a + bob * 5f)
        val k = (if (p.sneaking) 0.92f else 1f) * (1f + abs(bob) * 0.035f)
        val s = ts * 0.68f / 43f * k
        pFill.color = 0x50000000; c.drawCircle(ts * 0.06f, ts * 0.08f, ts * 0.3f * k, pFill)
        val w = p.weapon()
        val who = if (p.id == 0) "p0" else "p1"
        if (w.kind == ItemKind.GUN) {
            if (!spr.pivot(c, "${who}_gun", 17f, 21.5f, s)) { pFill.color = 0xFF3A6EA5.toInt(); c.drawCircle(0f, 0f, ts * 0.28f, pFill) }
            if (p.attackAnim > 0.6f) { pFill.color = 0xDDFFE070.toInt(); c.drawCircle(38f * s, 14f * s, ts * 0.12f, pFill) }
        } else {
            if (w.id != "fists") {
                c.save()
                c.translate(15f * s, 14f * s)
                c.rotate(-70f * p.attackAnim + 25f * (1f - p.attackAnim) - 10f)
                pLine.color = 0xFF2A2018.toInt(); pLine.strokeWidth = ts * (if (w.id == "knife") 0.075f else 0.12f)
                val len = ts * (0.2f + w.range * 0.28f)
                c.drawLine(0f, 0f, len, 0f, pLine)
                pLine.color = w.color; pLine.strokeWidth = ts * (if (w.id == "knife") 0.045f else 0.085f)
                c.drawLine(ts * 0.03f, 0f, len - ts * 0.02f, 0f, pLine)
                c.restore()
            }
            if (!spr.pivot(c, "${who}_hold", 17f, 21.5f, s)) { pFill.color = 0xFF3A6EA5.toInt(); c.drawCircle(0f, 0f, ts * 0.28f, pFill) }
        }
        c.restore()
    }

    // ---------- свет ----------
    private fun drawLighting(c: Canvas, gm: Game, W: Float, H: Float, x0: Int, x1: Int, y0: Int, y1: Int) {
        val dark = gm.darkness()
        if (dark < 0.02f) return
        // цвет сумерек → ночи
        val dusk = if (dark < 0.7f) 1f - dark / 0.7f else 0f
        val r = (8 + 90 * dusk).toInt(); val g = (10 + 36 * dusk).toInt(); val b = (30 + 10 * dusk).toInt()
        val alpha = (40 + 192 * dark).toInt().coerceAtMost(232)
        c.saveLayer(0f, 0f, W, H, null)
        c.drawColor(Color.argb(alpha, r, g, b))
        // фонарик и свечение вокруг игроков
        for (p in gm.players) {
            if (p.dead || !p.connected) continue
            val px = ox + p.rx * ts; val py = oy + p.ry * ts
            pCut.shader = RadialGradient(px, py, ts * 2.6f, intArrayOf(0xF0000000.toInt(), 0x00000000), null, Shader.TileMode.CLAMP)
            c.drawCircle(px, py, ts * 2.6f, pCut)
            if (dark > 0.25f) {
                val ang = atan2(p.fy, p.fx)
                val len = ts * 9.5f
                path.reset(); path.moveTo(px, py)
                val spread = 0.42f
                for (k in 0..12) { val aa = ang - spread + 2 * spread * k / 12f; path.lineTo(px + cos(aa) * len, py + sin(aa) * len) }
                path.close()
                pCut.shader = RadialGradient(px, py, len, intArrayOf(0xFF000000.toInt(), 0xE0000000.toInt(), 0x00000000), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
                c.drawPath(path, pCut)
            }
        }
        // уличные фонари
        for (l in lamps) {
            if (l[2] < x0 - 5 || l[2] > x1 + 5 || l[3] < y0 - 5 || l[3] > y1 + 5) continue
            val lx = ox + l[2] * ts; val ly = oy + l[3] * ts
            pCut.shader = RadialGradient(lx, ly, ts * 4.2f, intArrayOf(0xE8000000.toInt(), 0x90000000.toInt(), 0x00000000), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(lx, ly, ts * 4.2f, pCut)
        }
        for (f in flashes) {
            val fx = ox + f.x * ts; val fy = oy + f.y * ts
            pCut.shader = RadialGradient(fx, fy, ts * 5f, intArrayOf(0xFF000000.toInt(), 0x00000000), null, Shader.TileMode.CLAMP)
            c.drawCircle(fx, fy, ts * 5f, pCut)
        }
        pCut.shader = null
        c.restore()
        // тёплый свет фонарей поверх
        if (dark > 0.3f) {
            for (l in lamps) {
                if (l[2] < x0 - 5 || l[2] > x1 + 5 || l[3] < y0 - 5 || l[3] > y1 + 5) continue
                val lx = ox + l[2] * ts; val ly = oy + l[3] * ts
                pAdd.shader = RadialGradient(lx, ly, ts * 3.2f, intArrayOf(Color.argb((60 * dark).toInt(), 255, 190, 90), 0x00000000), null, Shader.TileMode.CLAMP)
                c.drawCircle(lx, ly, ts * 3.2f, pAdd)
            }
            for (f in flashes) {
                val fx = ox + f.x * ts; val fy = oy + f.y * ts
                pAdd.shader = RadialGradient(fx, fy, ts * 2.5f, intArrayOf(0x90FFD080.toInt(), 0x00000000), null, Shader.TileMode.CLAMP)
                c.drawCircle(fx, fy, ts * 2.5f, pAdd)
            }
            pAdd.shader = null
        }
    }

    /** Столбы фонарей рисуются поверх крыш не нужно — рисуем после построек */
    fun drawLamps(c: Canvas, gm: Game, x0: Int, x1: Int, y0: Int, y1: Int) {
        val lit = gm.darkness() > 0.3f
        for (l in lamps) {
            if (l[0] < x0 - 1 || l[0] > x1 + 1 || l[1] < y0 - 1 || l[1] > y1 + 1) continue
            val bx = ox + l[0] * ts; val by = oy + l[1] * ts; val hx = ox + l[2] * ts; val hy = oy + l[3] * ts
            pLine.color = 0xFF2E3236.toInt(); pLine.strokeWidth = ts * 0.07f; c.drawLine(bx, by, hx, hy, pLine)
            pFill.color = 0xFF2E3236.toInt(); c.drawCircle(bx, by, ts * 0.1f, pFill)
            pFill.color = if (lit) 0xFFFFE6A0.toInt() else 0xFF8C9094.toInt(); c.drawCircle(hx, hy, ts * 0.09f, pFill)
        }
    }
}
