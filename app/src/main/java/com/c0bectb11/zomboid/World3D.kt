package com.c0bectb11.zomboid

import kotlin.math.atan2

/** Строит статическую 3D-геометрию мира по клеткам карты. Ось X — x карты, ось Z — y карты, Y — вверх. */
class World3D(val w: World) {
    companion object {
        const val WH = 2.4f            // высота стен
        const val HALF = 0.11f         // половина толщины стены
        const val CHUNK = 20
        fun hash(x: Int, y: Int): Int { var h = x * 374761393 + y * 668265263; h = (h xor (h ushr 13)) * 1274126177; return (h xor (h ushr 16)) and 0x7fffffff }

        val wallCols = intArrayOf(0xFFDCD2C2.toInt(), 0xFFCDB99C.toInt(), 0xFFB9C1C6.toInt(), 0xFFD8BEA6.toInt(), 0xFFC9C3B0.toInt(), 0xFFB8C4B0.toInt())
        val roofCols = intArrayOf(0xFF8A4236.toInt(), 0xFF4E5A66.toInt(), 0xFF6E5C46.toInt(), 0xFF46604E.toInt(), 0xFF6A4440.toInt())
        val carCols = intArrayOf(0xFF8E2E2A.toInt(), 0xFF2C4C7C.toInt(), 0xFFD6D6CC.toInt(), 0xFF35373B.toInt(), 0xFF55744A.toInt(), 0xFFC89A3C.toInt(), 0xFF6A6E74.toInt())
    }

    val chunksX = (w.w + CHUNK - 1) / CHUNK
    val chunksY = (w.h + CHUNK - 1) / CHUNK
    /** Фонари: [x, z основания, x, z головы] */
    val lamps = ArrayList<FloatArray>()

    init {
        for (y in 0 until w.h) for (x in 0 until w.w) {
            if (w.t(x, y) != T.SIDEWALK) continue
            val up = w.t(x, y - 1) == T.ROAD; val dn = w.t(x, y + 1) == T.ROAD
            val lf = w.t(x - 1, y) == T.ROAD; val rt = w.t(x + 1, y) == T.ROAD
            if ((up || dn) && !(lf || rt) && x % 9 == 4) lamps.add(floatArrayOf(x + 0.5f, y + 0.5f, x + 0.5f, y + 0.5f + if (up) -0.8f else 0.8f))
            else if ((lf || rt) && !(up || dn) && y % 9 == 4) lamps.add(floatArrayOf(x + 0.5f, y + 0.5f, x + 0.5f + if (lf) -0.8f else 0.8f, y + 0.5f))
        }
    }

    fun wallLike(t: Int) = t == T.WALL || T.isWindowLike(t) || T.isDoorLike(t)

    fun wallColor(x: Int, y: Int): Int {
        val b = w.buildingOf.getOrElse(w.idx(x.coerceIn(0, w.w - 1), y.coerceIn(0, w.h - 1))) { -1 }
        return wallCols[(if (b < 0) 0 else b) % wallCols.size]
    }

    private fun groundH(t: Int): Float = when {
        t == T.SIDEWALK -> 0.12f
        T.isFloor(t) || T.isFurniture(t) -> 0.1f
        else -> 0f
    }

    private fun jitter(c: Int, h: Int, amt: Float): Int {
        val k = 1f + (((h shr 4) % 21) - 10) / 10f * amt
        val r = (((c shr 16) and 255) * k).toInt().coerceIn(0, 255)
        val g = (((c shr 8) and 255) * k).toInt().coerceIn(0, 255)
        val b = ((c and 255) * k).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    // ================== ЧАНК ==================
    fun buildChunk(mb: MeshBuilder, cx: Int, cy: Int) {
        mb.reset()
        for (y in cy * CHUNK until minOf(w.h, (cy + 1) * CHUNK)) for (x in cx * CHUNK until minOf(w.w, (cx + 1) * CHUNK)) tile(mb, x, y)
        // фонари этого чанка
        for (l in lamps) {
            val lx = l[0].toInt(); val ly = l[1].toInt()
            if (lx / CHUNK != cx || ly / CHUNK != cy) continue
            lamp(mb, l)
        }
    }

    private fun tile(mb: MeshBuilder, x: Int, y: Int) {
        val t = w.t(x, y)
        val hv = hash(x, y)
        val fx = x.toFloat(); val fz = y.toFloat()
        when {
            wallLike(t) -> { groundUnderWall(mb, x, y); walls(mb, x, y, t) }
            T.isFurniture(t) -> { floorTop(mb, floorNear(x, y), x, y, fx, fz, fx + 1, fz + 1); furniture(mb, x, y, t) }
            else -> {
                ground(mb, if (T.isCar(t)) T.ROAD else t, x, y, fx, fz, fx + 1, fz + 1)
                // бордюры
                val h = groundH(t)
                if (h > 0f) for (k in 0 until 4) {
                    val n = w.t(x + World.DX4[k], y + World.DY4[k])
                    val hn = if (wallLike(n)) h else groundH(n)
                    if (hn >= h) continue
                    val side = jitter(0xFF7C7C76.toInt(), hv, 0.05f)
                    when (k) {
                        0 -> mb.quad(fx + 1, hn, fz + 1, fx + 1, hn, fz, fx + 1, h, fz, fx + 1, h, fz + 1, side)
                        1 -> mb.quad(fx, hn, fz, fx, hn, fz + 1, fx, h, fz + 1, fx, h, fz, side)
                        2 -> mb.quad(fx, hn, fz + 1, fx + 1, hn, fz + 1, fx + 1, h, fz + 1, fx, h, fz + 1, side)
                        else -> mb.quad(fx + 1, hn, fz, fx, hn, fz, fx, h, fz, fx + 1, h, fz, side)
                    }
                }
                when (t) {
                    T.TREE -> tree(mb, x, y)
                    T.CAR -> car(mb, x, y, true)
                    T.CAR_V -> car(mb, x, y, false)
                }
            }
        }
    }

    private fun floorNear(x: Int, y: Int): Int {
        for (k in 0 until 4) { val n = w.t(x + World.DX4[k], y + World.DY4[k]); if (T.isFloor(n)) return n }
        return T.WOOD
    }

    /** Поверхность земли по типу */
    private fun ground(mb: MeshBuilder, t: Int, x: Int, y: Int, x0: Float, z0: Float, x1: Float, z1: Float) {
        val hv = hash(x, y)
        when {
            t == T.GRASS || t == T.TREE -> {
                // трава: 4 пятна разного оттенка
                val base = intArrayOf(0xFF4C6A38.toInt(), 0xFF56723E.toInt(), 0xFF4A6436.toInt(), 0xFF5A6E3A.toInt())[hv % 4]
                val mx = (x0 + x1) / 2; val mz = (z0 + z1) / 2
                mb.flat(x0, z0, mx, mz, 0f, jitter(base, hv, 0.06f)); mb.flat(mx, z0, x1, mz, 0f, jitter(base, hv * 3, 0.06f))
                mb.flat(x0, mz, mx, z1, 0f, jitter(base, hv * 5, 0.06f)); mb.flat(mx, mz, x1, z1, 0f, jitter(base, hv * 7, 0.06f))
                if (x1 - x0 > 0.9f && hv % 37 == 0) { // камень
                    val ox = x0 + 0.3f + (hv % 5) * 0.08f; val oz = z0 + 0.3f + (hv % 7) * 0.06f
                    mb.aabb(ox, 0f, oz, ox + 0.22f, 0.12f, oz + 0.16f, 0xFF7E7E78.toInt())
                }
                if (x1 - x0 > 0.9f && hv % 23 == 1) { // кустик травы
                    mb.identity(); mb.translate(x0 + 0.5f, 0f, z0 + 0.5f); mb.rotY((hv % 90).toFloat())
                    mb.box(0f, 0.1f, 0f, 0.05f, 0.2f, 0.25f, 0xFF6A8A44.toInt()); mb.box(0f, 0.1f, 0f, 0.25f, 0.2f, 0.05f, 0xFF6A8A44.toInt())
                }
            }
            t == T.ROAD -> {
                mb.flat(x0, z0, x1, z1, 0f, jitter(0xFF3C3D40.toInt(), hv, 0.05f))
                if (x1 - x0 > 0.9f) {
                    val cxr = x % 25; val cyr = y % 25
                    if (cyr == 1 && cxr in 4..23 && x % 2 == 0) mb.flat(x0 + 0.1f, z0 + 0.46f, x1 - 0.1f, z0 + 0.54f, 0.006f, 0xFFC8A83A.toInt())
                    if (cxr == 1 && cyr in 4..23 && y % 2 == 0) mb.flat(x0 + 0.46f, z0 + 0.1f, x0 + 0.54f, z1 - 0.1f, 0.006f, 0xFFC8A83A.toInt())
                    // зебра у перекрёстков
                    if ((cxr == 4 || cxr == 23) && cyr in 0..2) for (k in 0..2) mb.flat(x0 + 0.15f, z0 + k * 0.33f + 0.06f, x1 - 0.15f, z0 + k * 0.33f + 0.22f, 0.006f, 0xFFD8D8D0.toInt())
                    if ((cyr == 4 || cyr == 23) && cxr in 0..2) for (k in 0..2) mb.flat(x0 + k * 0.33f + 0.06f, z0 + 0.15f, x0 + k * 0.33f + 0.22f, z1 - 0.15f, 0.006f, 0xFFD8D8D0.toInt())
                    if (hv % 19 == 0) mb.flat(x0 + 0.2f, z0 + 0.3f, x0 + 0.7f, z0 + 0.6f, 0.004f, 0xFF2E2F31.toInt())
                }
            }
            t == T.SIDEWALK -> {
                val h = 0.12f
                val mx = (x0 + x1) / 2; val mz = (z0 + z1) / 2; val g = 0.02f
                mb.flat(x0, z0, x1, z1, h - 0.005f, 0xFF6E6E68.toInt())
                mb.flat(x0 + g, z0 + g, mx - g, mz - g, h, jitter(0xFF9C9C96.toInt(), hv, 0.05f))
                mb.flat(mx + g, z0 + g, x1 - g, mz - g, h, jitter(0xFF9C9C96.toInt(), hv * 3, 0.05f))
                mb.flat(x0 + g, mz + g, mx - g, z1 - g, h, jitter(0xFF9C9C96.toInt(), hv * 5, 0.05f))
                mb.flat(mx + g, mz + g, x1 - g, z1 - g, h, jitter(0xFF9C9C96.toInt(), hv * 7, 0.05f))
            }
            T.isFloor(t) -> floorTop(mb, t, x, y, x0, z0, x1, z1)
            else -> mb.flat(x0, z0, x1, z1, 0f, 0xFF4C6A38.toInt())
        }
    }

    private fun floorTop(mb: MeshBuilder, t: Int, x: Int, y: Int, x0: Float, z0: Float, x1: Float, z1: Float) {
        val hv = hash(x, y); val h = 0.1f
        if (t == T.TILEFLOOR) {
            val mx = x.toFloat() + 0.5f; val mz = y.toFloat() + 0.5f
            val a = 0xFFD8D0C0.toInt(); val b = 0xFFB8AE9C.toInt()
            for (q in 0 until 4) {
                val qx0 = if (q % 2 == 0) x.toFloat() else mx; val qz0 = if (q < 2) y.toFloat() else mz
                val qx1 = qx0 + 0.5f; val qz1 = qz0 + 0.5f
                val cx0 = maxOf(qx0, x0); val cz0 = maxOf(qz0, z0); val cx1 = minOf(qx1, x1); val cz1 = minOf(qz1, z1)
                if (cx1 > cx0 && cz1 > cz0) mb.flat(cx0, cz0, cx1, cz1, h, if ((q + (q shr 1)) % 2 == 0) a else b)
            }
        } else {
            // доски вдоль X
            for (k in 0 until 4) {
                val pz0 = y + k * 0.25f; val pz1 = pz0 + 0.25f
                val cz0 = maxOf(pz0, z0); val cz1 = minOf(pz1, z1)
                if (cz1 <= cz0) continue
                mb.flat(x0, cz0, x1, cz1 - 0.01f, h, jitter(0xFF8E6842.toInt(), hv * (k + 3), 0.08f))
                mb.flat(x0, cz1 - 0.01f, x1, cz1, h, 0xFF5E4428.toInt())
            }
            if (hv % 53 == 0 && x1 - x0 > 0.9f) mb.flat(x0 + 0.1f, z0 + 0.1f, x1 - 0.1f, z1 - 0.1f, h + 0.01f, intArrayOf(0xFF8A3A30.toInt(), 0xFF3A5070.toInt(), 0xFF6A5A30.toInt())[hv % 3])
        }
    }

    /** Под тонкой стеной: четверти клетки — пол или улица */
    private fun groundUnderWall(mb: MeshBuilder, x: Int, y: Int) {
        var out = T.GRASS
        for (k in 0 until 4) {
            val n = w.t(x + World.DX4[k], y + World.DY4[k])
            if (!wallLike(n) && !T.isFloor(n) && !T.isFurniture(n) && !T.isCar(n)) { out = if (n == T.TREE) T.GRASS else n; break }
        }
        for (q in 0 until 4) {
            val dx = if (q % 2 == 0) -1 else 1; val dy = if (q < 2) -1 else 1
            var fl = -1
            for (n in intArrayOf(w.t(x + dx, y), w.t(x, y + dy), w.t(x + dx, y + dy))) {
                if (T.isFloor(n)) { fl = n; break }
                if (T.isFurniture(n)) { fl = T.WOOD }
            }
            val x0 = x + (if (dx < 0) 0f else 0.5f); val z0 = y + (if (dy < 0) 0f else 0.5f)
            if (fl >= 0) floorTop(mb, fl, x, y, x0, z0, x0 + 0.5f, z0 + 0.5f)
            else ground(mb, out, x, y, x0, z0, x0 + 0.5f, z0 + 0.5f)
        }
    }

    // ================== СТЕНЫ, ОКНА, ДВЕРИ ==================
    private fun walls(mb: MeshBuilder, x: Int, y: Int, t: Int) {
        val col = wallColor(x, y)
        val cap = mb.darken(col, 0.72f)
        val cx = x + 0.5f; val cz = y + 0.5f; val h = HALF
        val l = wallLike(w.t(x - 1, y)); val r = wallLike(w.t(x + 1, y))
        val u = wallLike(w.t(x, y - 1)); val d = wallLike(w.t(x, y + 1))
        val horiz = l || r
        fun seg(y0: Float, y1: Float, c: Int = col, k: Float = 2f) {
            // сегмент вдоль оси проёма во всю клетку
            if (horiz) mb.aabb(x.toFloat(), y0, cz - h, x + 1f, y1, cz + h, c, k, cap)
            else mb.aabb(cx - h, y0, y.toFloat(), cx + h, y1, y + 1f, c, k, cap)
        }
        when (t) {
            T.WALL -> {
                mb.aabb(cx - h, 0f, cz - h, cx + h, WH, cz + h, col, 2f, cap)
                if (r) mb.aabb(cx, 0f, cz - h, x + 1f, WH, cz + h, col, 2f, cap)
                if (l) mb.aabb(x.toFloat(), 0f, cz - h, cx, WH, cz + h, col, 2f, cap)
                if (d) mb.aabb(cx - h, 0f, cz, cx + h, WH, y + 1f, col, 2f, cap)
                if (u) mb.aabb(cx - h, 0f, y.toFloat(), cx + h, WH, cz, col, 2f, cap)
                // плинтус снаружи — тёмная полоса внизу
                val base = mb.darken(col, 0.6f)
                if (r || l) mb.aabb(if (l) x.toFloat() else cx - h, 0f, cz - h - 0.01f, if (r) x + 1f else cx + h, 0.18f, cz + h + 0.01f, base, 2f)
                if (u || d) mb.aabb(cx - h - 0.01f, 0f, if (u) y.toFloat() else cz - h, cx + h + 0.01f, 0.18f, if (d) y + 1f else cz + h, base, 2f)
            }
            T.WINDOW, T.WINDOW_BROKEN -> {
                seg(0f, 0.95f)
                seg(1.85f, WH)
                // подоконник и рама
                val frame = 0xFFEDE6DA.toInt()
                if (horiz) {
                    mb.aabb(x + 0.04f, 0.95f, cz - h - 0.05f, x + 0.96f, 1.0f, cz + h + 0.05f, frame, 2f)
                    mb.aabb(x.toFloat(), 1.0f, cz - h, x + 0.06f, 1.85f, cz + h, frame, 2f); mb.aabb(x + 0.94f, 1.0f, cz - h, x + 1f, 1.85f, cz + h, frame, 2f)
                } else {
                    mb.aabb(cx - h - 0.05f, 0.95f, y + 0.04f, cx + h + 0.05f, 1.0f, y + 0.96f, frame, 2f)
                    mb.aabb(cx - h, 1.0f, y.toFloat(), cx + h, 1.85f, y + 0.06f, frame, 2f); mb.aabb(cx - h, 1.0f, y + 0.94f, cx + h, 1.85f, y + 1f, frame, 2f)
                }
                if (t == T.WINDOW) {
                    val glass = 0xFF7FA6BA.toInt()
                    if (horiz) { mb.aabb(x + 0.06f, 1.0f, cz - 0.02f, x + 0.94f, 1.85f, cz + 0.02f, glass, 2f); mb.aabb(cx - 0.02f, 1.0f, cz - 0.03f, cx + 0.02f, 1.85f, cz + 0.03f, frame, 2f) }
                    else { mb.aabb(cx - 0.02f, 1.0f, y + 0.06f, cx + 0.02f, 1.85f, y + 0.94f, glass, 2f); mb.aabb(cx - 0.03f, 1.0f, cz - 0.02f, cx + 0.03f, 1.85f, cz + 0.02f, frame, 2f) }
                } else {
                    val g = 0xFF9FC4D4.toInt()
                    if (horiz) { mb.tri(x + 0.06f, 1.0f, cz, x + 0.3f, 1.0f, cz, x + 0.1f, 1.3f, cz, g, 2f); mb.tri(x + 0.94f, 1.85f, cz, x + 0.7f, 1.85f, cz, x + 0.9f, 1.55f, cz, g, 2f) }
                    else { mb.tri(cx, 1.0f, y + 0.06f, cx, 1.0f, y + 0.3f, cx, 1.3f, y + 0.1f, g, 2f); mb.tri(cx, 1.85f, y + 0.94f, cx, 1.85f, y + 0.7f, cx, 1.55f, y + 0.9f, g, 2f) }
                    // осколки на полу
                    for (k in 0 until 4) { val ox = cx + ((hash(x + k, y) % 9) - 4) * 0.09f; val oz = cz + ((hash(x, y + k) % 9) - 4) * 0.09f
                        mb.tri(ox, 0.11f, oz, ox + 0.08f, 0.11f, oz + 0.03f, ox + 0.02f, 0.11f, oz + 0.07f, g) }
                }
            }
            else -> { // дверной проём: перемычка и косяки
                seg(2.05f, WH)
                val jamb = mb.darken(col, 0.8f)
                if (horiz) { mb.aabb(x.toFloat(), 0f, cz - h - 0.02f, x + 0.07f, 2.05f, cz + h + 0.02f, jamb, 2f); mb.aabb(x + 0.93f, 0f, cz - h - 0.02f, x + 1f, 2.05f, cz + h + 0.02f, jamb, 2f) }
                else { mb.aabb(cx - h - 0.02f, 0f, y.toFloat(), cx + h + 0.02f, 2.05f, y + 0.07f, jamb, 2f); mb.aabb(cx - h - 0.02f, 0f, y + 0.93f, cx + h + 0.02f, 2.05f, y + 1f, jamb, 2f) }
            }
        }
    }

    // ================== МЕБЕЛЬ ==================
    private fun furniture(mb: MeshBuilder, x: Int, y: Int, t: Int) {
        // повернуть лицом к свободному полу
        var yaw = 0f
        for (k in 0 until 4) {
            val n = w.t(x + World.DX4[k], y + World.DY4[k])
            if (T.isFloor(n)) { yaw = Math.toDegrees(atan2(World.DX4[k].toDouble(), World.DY4[k].toDouble())).toFloat(); break }
        }
        val hv = hash(x, y)
        mb.identity(); mb.translate(x + 0.5f, 0.1f, y + 0.5f); mb.rotY(yaw)
        val wood = 0xFF7A5434.toInt()
        when (t) {
            T.FRIDGE -> {
                mb.box(0f, 0.85f, -0.05f, 0.76f, 1.7f, 0.66f, 0xFFE6E6E2.toInt())
                mb.box(0f, 1.22f, 0.285f, 0.74f, 0.02f, 0.02f, 0xFFA8A8A8.toInt())
                mb.box(0.28f, 1.45f, 0.29f, 0.04f, 0.3f, 0.04f, 0xFF8A8A8A.toInt()); mb.box(0.28f, 0.85f, 0.29f, 0.04f, 0.3f, 0.04f, 0xFF8A8A8A.toInt())
            }
            T.COUNTER -> {
                val stove = hv % 3 == 0
                mb.box(0f, 0.42f, -0.06f, 0.92f, 0.84f, 0.7f, if (stove) 0xFF2E2E30.toInt() else 0xFFD8D2C4.toInt())
                mb.box(0f, 0.87f, -0.06f, 0.96f, 0.06f, 0.76f, if (stove) 0xFF1E1E20.toInt() else 0xFF5E6064.toInt())
                if (stove) for (k in 0 until 4) mb.box(-0.2f + (k % 2) * 0.4f, 0.91f, -0.2f + (k / 2) * 0.3f, 0.2f, 0.02f, 0.2f, 0xFF101010.toInt())
                else { mb.box(-0.22f, 0.45f, 0.295f, 0.02f, 0.7f, 0.02f, 0xFFA09888.toInt()); mb.box(0.22f, 0.45f, 0.295f, 0.02f, 0.7f, 0.02f, 0xFFA09888.toInt()) }
            }
            T.WARDROBE -> {
                mb.box(0f, 0.95f, -0.15f, 0.92f, 1.9f, 0.56f, 0xFF6A4628.toInt())
                mb.box(0f, 0.95f, 0.135f, 0.02f, 1.8f, 0.02f, 0xFF3A2414.toInt())
                mb.box(-0.08f, 1.0f, 0.14f, 0.04f, 0.12f, 0.03f, 0xFFC8A040.toInt()); mb.box(0.08f, 1.0f, 0.14f, 0.04f, 0.12f, 0.03f, 0xFFC8A040.toInt())
            }
            T.SHELF -> {
                mb.box(0f, 0.8f, -0.28f, 0.92f, 1.6f, 0.06f, wood)
                mb.box(-0.44f, 0.8f, -0.1f, 0.04f, 1.6f, 0.4f, wood); mb.box(0.44f, 0.8f, -0.1f, 0.04f, 1.6f, 0.4f, wood)
                for (s in 0 until 4) {
                    val sy = 0.05f + s * 0.5f
                    mb.box(0f, sy, -0.1f, 0.88f, 0.04f, 0.4f, wood)
                    if (s < 3) for (b in 0 until 6) {
                        if ((hv shr (b + s * 3)) and 1 == 0) continue
                        val c = intArrayOf(0xFFB03A30.toInt(), 0xFF3A6AA0.toInt(), 0xFFD0B040.toInt(), 0xFF4A8A4A.toInt(), 0xFFE0E0D8.toInt())[(hv + b * 7 + s) % 5]
                        mb.box(-0.33f + b * 0.13f, sy + 0.17f, -0.12f, 0.1f, 0.3f, 0.28f, c)
                    }
                }
            }
            T.MEDCAB -> {
                mb.box(0f, 0.9f, -0.22f, 0.7f, 1.1f, 0.36f, 0xFFF0F0EE.toInt())
                mb.box(0f, 0.9f, -0.035f, 0.1f, 0.42f, 0.02f, 0xFFD03030.toInt()); mb.box(0f, 0.9f, -0.035f, 0.42f, 0.1f, 0.02f, 0xFFD03030.toInt())
            }
            T.LOCKER -> {
                mb.box(0f, 0.95f, -0.15f, 0.8f, 1.9f, 0.5f, 0xFF4E5E6E.toInt())
                for (k in 0 until 3) mb.box(0f, 1.55f + k * 0.08f, 0.105f, 0.5f, 0.02f, 0.02f, 0xFF2E3A46.toInt())
                mb.box(0f, 0.95f, 0.105f, 0.02f, 1.8f, 0.02f, 0xFF2E3A46.toInt())
            }
            else -> { // стеллаж магазина
                mb.box(0f, 0.65f, 0f, 0.92f, 1.3f, 0.06f, 0xFF8A8E94.toInt())
                for (s in 0 until 3) {
                    val sy = 0.12f + s * 0.45f
                    mb.box(0f, sy, 0f, 0.92f, 0.04f, 0.84f, 0xFFA8ACB2.toInt())
                    for (b in 0 until 8) {
                        val side = if (b < 4) -1f else 1f
                        val c = intArrayOf(0xFFD04838.toInt(), 0xFFE8C040.toInt(), 0xFF4A8AD0.toInt(), 0xFFE8E8E0.toInt(), 0xFF5AA050.toInt())[(hv + b + s * 3) % 5]
                        if ((hv shr (b + s)) and 3 == 0) continue
                        mb.box(-0.33f + (b % 4) * 0.22f, sy + 0.12f, side * 0.22f, 0.16f, 0.2f, 0.18f, c)
                    }
                }
            }
        }
        mb.identity()
    }

    // ================== УЛИЦА ==================
    private fun tree(mb: MeshBuilder, x: Int, y: Int) {
        val hv = hash(x, y)
        val cx = x + 0.5f; val cz = y + 0.5f
        mb.aabb(cx - 0.1f, 0f, cz - 0.1f, cx + 0.1f, 1.5f, cz + 0.1f, 0xFF5A4030.toInt())
        val autumn = hv % 7 == 0
        val c1 = if (autumn) 0xFFB8682C.toInt() else intArrayOf(0xFF3E6A32.toInt(), 0xFF46723A.toInt(), 0xFF365E2C.toInt())[hv % 3]
        val c2 = if (autumn) 0xFFD08A3A.toInt() else mb.darken(c1, 1.18f)
        val s = 0.9f + (hv % 5) * 0.08f
        mb.blob(cx, 1.85f * s, cz, 0.95f * s, 0.75f * s, 0.95f * s, c1, hv, 2f)
        mb.blob(cx + 0.25f, 2.4f * s, cz - 0.15f, 0.62f * s, 0.55f * s, 0.62f * s, c2, hv + 1, 2f)
        mb.blob(cx - 0.3f, 2.25f * s, cz + 0.25f, 0.55f * s, 0.5f * s, 0.55f * s, c1, hv + 2, 2f)
    }

    private fun car(mb: MeshBuilder, x: Int, y: Int, horiz: Boolean) {
        val hv = hash(x, y)
        val col = carCols[hv % carCols.size]
        mb.identity()
        if (horiz) mb.translate(x + 1f, 0f, y + 0.5f) else { mb.translate(x + 0.5f, 0f, y + 1f); mb.rotY(90f) }
        if (hv % 2 == 0) mb.rotY(180f)
        mb.rotY(((hv shr 5) % 9 - 4).toFloat())
        val dark = 0xFF161616.toInt()
        for (sx in floatArrayOf(-0.58f, 0.58f)) for (sz in floatArrayOf(-0.4f, 0.4f)) mb.box(sx, 0.17f, sz, 0.34f, 0.34f, 0.12f, dark)
        mb.box(0f, 0.45f, 0f, 1.84f, 0.42f, 0.84f, col)
        mb.box(0.86f, 0.36f, 0f, 0.14f, 0.18f, 0.86f, 0xFF3A3A3C.toInt())      // бампер
        mb.box(-0.86f, 0.36f, 0f, 0.14f, 0.18f, 0.86f, 0xFF3A3A3C.toInt())
        val glass = if (hv % 5 == 0) 0xFF1A2228.toInt() else 0xFF34495A.toInt()
        mb.box(-0.12f, 0.86f, 0f, 1.0f, 0.42f, 0.74f, glass)
        mb.box(-0.12f, 1.08f, 0f, 0.86f, 0.04f, 0.7f, col)
        mb.box(-0.12f, 0.86f, 0f, 0.08f, 0.42f, 0.76f, col)                    // стойка
        mb.box(0.93f, 0.52f, 0.28f, 0.03f, 0.1f, 0.16f, 0xFFF4ECC4.toInt(), 1f); mb.box(0.93f, 0.52f, -0.28f, 0.03f, 0.1f, 0.16f, 0xFFF4ECC4.toInt(), 1f)
        mb.box(-0.93f, 0.52f, 0.3f, 0.03f, 0.1f, 0.14f, 0xFFB02020.toInt(), 1f); mb.box(-0.93f, 0.52f, -0.3f, 0.03f, 0.1f, 0.14f, 0xFFB02020.toInt(), 1f)
        if (hv % 3 == 0) mb.box(0.4f, 0.67f, 0.1f, 0.3f, 0.01f, 0.25f, mb.darken(col, 0.6f))   // ржавчина на капоте
        mb.identity()
    }

    private fun lamp(mb: MeshBuilder, l: FloatArray) {
        val pole = 0xFF2E3236.toInt()
        mb.aabb(l[0] - 0.05f, 0.12f, l[1] - 0.05f, l[0] + 0.05f, 3.0f, l[1] + 0.05f, pole)
        mb.aabb(minOf(l[0], l[2]) - 0.03f, 2.92f, minOf(l[1], l[3]) - 0.03f, maxOf(l[0], l[2]) + 0.03f, 2.98f, maxOf(l[1], l[3]) + 0.03f, pole)
        mb.aabb(l[2] - 0.16f, 2.86f, l[3] - 0.16f, l[2] + 0.16f, 2.98f, l[3] + 0.16f, 0xFF3A3E42.toInt())
        mb.aabb(l[2] - 0.12f, 2.82f, l[3] - 0.12f, l[2] + 0.12f, 2.86f, l[3] + 0.12f, 0xFFFFE6A0.toInt(), 1f)
    }

    // ================== КРЫШИ ==================
    fun buildRoof(mb: MeshBuilder, bi: Int) {
        mb.reset()
        val b = w.buildings[bi]
        if (b[2] - b[0] < 2 || b[3] - b[1] < 2) return
        val ov = 0.35f
        val X0 = b[0] + 0.5f - ov; val X1 = b[2] + 0.5f + ov
        val Z0 = b[1] + 0.5f - ov; val Z1 = b[3] + 0.5f + ov
        val col = roofCols[bi % roofCols.size]
        val wall = wallCols[bi % wallCols.size]
        val alongX = (X1 - X0) >= (Z1 - Z0)
        val span = if (alongX) Z1 - Z0 else X1 - X0
        val e = WH; val R = WH + minOf(1.5f, span * 0.32f)
        // полосы черепицы: 4 полосы на скат
        val bands = 4
        if (alongX) {
            val Zm = (Z0 + Z1) / 2
            for (i in 0 until bands) {
                val t0 = i / bands.toFloat(); val t1 = (i + 1) / bands.toFloat()
                val c = if (i % 2 == 0) col else mb.darken(col, 0.9f)
                // северный скат
                val za = Z0 + (Zm - Z0) * t0; val zb = Z0 + (Zm - Z0) * t1; val ya = e + (R - e) * t0; val yb = e + (R - e) * t1
                mb.quad(X0, ya, za, X0, yb, zb, X1, yb, zb, X1, ya, za, c)
                // южный
                val zc = Z1 - (Z1 - Zm) * t0; val zd = Z1 - (Z1 - Zm) * t1
                mb.quad(X0, yb, zd, X0, ya, zc, X1, ya, zc, X1, yb, zd, mb.darken(c, 0.82f))
            }
            mb.tri(X0, e, Z0, X0, e, Z1, X0, R, Zm, wall); mb.tri(X1, e, Z1, X1, e, Z0, X1, R, Zm, wall)
            mb.aabb(X0, R - 0.03f, Zm - 0.06f, X1, R + 0.05f, Zm + 0.06f, mb.darken(col, 0.6f))
        } else {
            val Xm = (X0 + X1) / 2
            for (i in 0 until bands) {
                val t0 = i / bands.toFloat(); val t1 = (i + 1) / bands.toFloat()
                val c = if (i % 2 == 0) col else mb.darken(col, 0.9f)
                val xa = X0 + (Xm - X0) * t0; val xb = X0 + (Xm - X0) * t1; val ya = e + (R - e) * t0; val yb = e + (R - e) * t1
                mb.quad(xa, ya, Z1, xb, yb, Z1, xb, yb, Z0, xa, ya, Z0, c)
                val xc = X1 - (X1 - Xm) * t0; val xd = X1 - (X1 - Xm) * t1
                mb.quad(xd, yb, Z1, xc, ya, Z1, xc, ya, Z0, xd, yb, Z0, mb.darken(c, 0.82f))
            }
            mb.tri(X1, e, Z0, X0, e, Z0, Xm, R, Z0, wall); mb.tri(X0, e, Z1, X1, e, Z1, Xm, R, Z1, wall)
            mb.aabb(Xm - 0.06f, R - 0.03f, Z0, Xm + 0.06f, R + 0.05f, Z1, mb.darken(col, 0.6f))
        }
        // труба
        val hv = hash(b[0], b[1])
        val px = X0 + (X1 - X0) * (0.25f + (hv % 40) / 100f); val pz = Z0 + (Z1 - Z0) * (0.3f + (hv % 30) / 100f)
        mb.aabb(px - 0.2f, e, pz - 0.2f, px + 0.2f, R + 0.45f, pz + 0.2f, 0xFF7A4434.toInt(), 0f, 0xFF2A1A14.toInt())
    }
}
