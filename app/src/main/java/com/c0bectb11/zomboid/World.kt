package com.c0bectb11.zomboid

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random

object T {
    const val GRASS = 0
    const val ROAD = 1
    const val SIDEWALK = 2
    const val WOOD = 3
    const val TILEFLOOR = 4
    const val WALL = 5
    const val DOOR = 6          // закрытая дверь
    const val DOOR_OPEN = 7
    const val WINDOW = 8
    const val WINDOW_BROKEN = 9
    const val TREE = 10
    const val DOOR_BROKEN = 11
    const val FRIDGE = 12
    const val COUNTER = 13
    const val WARDROBE = 14
    const val SHELF = 15
    const val MEDCAB = 16
    const val LOCKER = 17
    const val STORESHELF = 18
    const val CAR = 19          // машина горизонтально: голова (x,y), хвост (x+1,y)
    const val CAR_V = 20        // вертикально: голова (x,y), хвост (x,y+1)
    const val CAR_TAIL = 21

    fun isCar(t: Int) = t in CAR..CAR_TAIL
    fun isContainer(t: Int) = isFurniture(t) || t == CAR || t == CAR_V

    fun isFurniture(t: Int) = t in FRIDGE..STORESHELF
    fun isDoorLike(t: Int) = t == DOOR || t == DOOR_OPEN || t == DOOR_BROKEN
    fun isWindowLike(t: Int) = t == WINDOW || t == WINDOW_BROKEN
    fun isFloor(t: Int) = t == WOOD || t == TILEFLOOR
}

class World(val w: Int, val h: Int) {
    val tiles = ByteArray(w * h)
    val tileHp = HashMap<Int, Float>()        // прочность дверей/окон/баррикад
    val barr = HashMap<Int, Int>()            // уровень баррикады 1..3
    val containers = HashMap<Int, MutableList<Stack>>()  // ключ — индекс клетки
    val dirtyTiles = HashSet<Int>()
    val dirtyCont = HashSet<Int>()
    /** Кто сейчас лезет через окно: клетка → id зомби (игроки — отрицательные) */
    val climbOcc = HashMap<Int, Int>()

    /** Окно в горизонтальной стене (перелезать по оси Y) или в вертикальной (по оси X) */
    fun windowAlongY(x: Int, y: Int): Boolean {
        fun wl(t: Int) = t == T.WALL || T.isWindowLike(t) || T.isDoorLike(t)
        return wl(t(x - 1, y)) || wl(t(x + 1, y))
    }

    /** Точка по другую сторону окна от (px,py) */
    fun climbTarget(cell: Int, px: Float, py: Float): FloatArray {
        val x = cell % w; val y = cell / w
        val cx = x + 0.5f; val cy = y + 0.5f
        return if (windowAlongY(x, y)) floatArrayOf(cx, cy + if (py < cy) 0.95f else -0.95f)
        else floatArrayOf(cx + if (px < cx) 0.95f else -0.95f, cy)
    }
    /** События смены клеток для анимаций: [индекс, было, стало] */
    val events = ArrayList<IntArray>()

    fun idx(x: Int, y: Int) = y * w + x
    fun inside(x: Int, y: Int) = x in 0 until w && y in 0 until h
    fun t(x: Int, y: Int): Int = if (inside(x, y)) tiles[idx(x, y)].toInt() else T.WALL
    fun tAt(i: Int): Int = tiles[i].toInt()
    fun set(x: Int, y: Int, v: Int) { if (inside(x, y)) { tiles[idx(x, y)] = v.toByte() } }
    fun setIdx(i: Int, v: Int) { events.add(intArrayOf(i, tiles[i].toInt(), v)); tiles[i] = v.toByte(); dirtyTiles.add(i) }
    fun barrAt(i: Int) = barr[i] ?: 0

    /** Клетка непроходима для движения */
    fun solid(x: Int, y: Int): Boolean {
        if (!inside(x, y)) return true
        val i = idx(x, y)
        if (barrAt(i) > 0) return true
        return when (tAt(i)) {
            T.WALL, T.DOOR, T.WINDOW, T.WINDOW_BROKEN, T.TREE -> true
            else -> T.isFurniture(tAt(i)) || T.isCar(tAt(i))
        }
    }

    /** Клетка блокирует обзор */
    fun opaque(x: Int, y: Int): Boolean {
        val t = t(x, y)
        return t == T.WALL || t == T.DOOR || (T.isFurniture(t) && t != T.COUNTER) ||
            (barrAt(idx(x, y)) >= 2 && inside(x, y))
    }

    /** Её можно выломать зомби */
    fun bashable(i: Int): Boolean {
        val t = tAt(i)
        return barrAt(i) > 0 || t == T.DOOR || t == T.WINDOW
    }

    fun circleBlocked(cx: Float, cy: Float, r: Float): Boolean {
        val x0 = floor(cx - r).toInt(); val x1 = floor(cx + r).toInt()
        val y0 = floor(cy - r).toInt(); val y1 = floor(cy + r).toInt()
        for (y in y0..y1) for (x in x0..x1) {
            if (!solid(x, y)) continue
            val nx = cx.coerceIn(x.toFloat(), x + 1f)
            val ny = cy.coerceIn(y.toFloat(), y + 1f)
            val dx = cx - nx; val dy = cy - ny
            if (dx * dx + dy * dy < r * r) return true
        }
        return false
    }

    /** Прямая видимость между точками (DDA по клеткам) */
    fun los(ax: Float, ay: Float, bx: Float, by: Float): Boolean {
        val dx = bx - ax; val dy = by - ay
        val steps = (maxOf(abs(dx), abs(dy)) * 3).toInt() + 1
        var lastX = Int.MIN_VALUE; var lastY = Int.MIN_VALUE
        val tx = floor(bx).toInt(); val ty = floor(by).toInt()
        val sx = floor(ax).toInt(); val sy = floor(ay).toInt()
        for (s in 1 until steps) {
            val f = s.toFloat() / steps
            val x = floor(ax + dx * f).toInt(); val y = floor(ay + dy * f).toInt()
            if (x == lastX && y == lastY) continue
            lastX = x; lastY = y
            if ((x == tx && y == ty) || (x == sx && y == sy)) continue
            if (opaque(x, y)) return false
        }
        return true
    }

    // ---------- Поле потоков для зомби (Дейкстра от игроков) ----------
    val flow = IntArray(w * h) { Int.MAX_VALUE }

    fun moveCost(i: Int): Int {
        val t = tAt(i)
        if (t == T.WALL || t == T.TREE || T.isFurniture(t) || T.isCar(t)) return -1
        var c = 10
        if (t == T.WINDOW_BROKEN) c += 35
        if (t == T.DOOR || t == T.WINDOW) c += 60
        c += barrAt(i) * 80
        return c
    }

    fun computeFlow(sources: List<Pair<Int, Int>>, maxCost: Int = 900) {
        java.util.Arrays.fill(flow, Int.MAX_VALUE)
        val pq = PriorityQueue<Long>()
        for ((x, y) in sources) {
            if (!inside(x, y)) continue
            val i = idx(x, y); flow[i] = 0; pq.add(i.toLong())
        }
        while (pq.isNotEmpty()) {
            val e = pq.poll()!!
            val d = (e shr 32).toInt(); val i = (e and 0xffffffffL).toInt()
            if (d > flow[i] || d > maxCost) continue
            val x = i % w; val y = i / w
            for (k in 0 until 4) {
                val nx = x + DX4[k]; val ny = y + DY4[k]
                if (!inside(nx, ny)) continue
                val ni = idx(nx, ny)
                // стоимость входа считается по клетке, из которой идём к игроку
                val c = moveCost(ni); if (c < 0) continue
                val nd = d + c
                if (nd < flow[ni]) { flow[ni] = nd; pq.add((nd.toLong() shl 32) or ni.toLong()) }
            }
        }
    }

    // ---------- Генерация города ----------
    fun generate(seed: Long): Pair<Float, Float> {
        val rnd = Random(seed)
        tiles.fill(T.GRASS.toByte())
        val step = 25
        // дороги
        for (k in 0..(w / step)) {
            val r0 = k * step
            for (a in 0 until w) for (o in 0..2) { set(a, r0 + o, T.ROAD); set(r0 + o, a, T.ROAD) }
        }
        for (k in 0..(w / step)) {
            val r0 = k * step
            for (a in 0 until w) {
                for (o in intArrayOf(-1, 3)) {
                    if (t(a, r0 + o) == T.GRASS) set(a, r0 + o, T.SIDEWALK)
                    if (t(r0 + o, a) == T.GRASS) set(r0 + o, a, T.SIDEWALK)
                }
            }
        }
        // кварталы
        val blocks = ArrayList<Pair<Int, Int>>()
        var by = 0
        while (by + step <= h) { var bx = 0; while (bx + step <= w) { blocks.add(bx to by); bx += step }; by += step }
        val policeBlock = rnd.nextInt(blocks.size)
        for ((bi, b) in blocks.withIndex()) {
            val ox = b.first + 4; val oy = b.second + 4   // внутренняя область 20x20
            for (ly in 0..1) for (lx in 0..1) {
                val lotX = ox + lx * 10; val lotY = oy + ly * 10
                val roll = rnd.nextFloat()
                val kind = when {
                    bi == policeBlock && lx == 0 && ly == 0 -> 2
                    roll < 0.12f -> 1
                    roll < 0.85f -> 0
                    else -> -1
                }
                if (kind < 0) { scatterTrees(rnd, lotX, lotY, 10, 10); continue }
                buildHouse(rnd, lotX, lotY, ly == 0, kind)
            }
        }
        // брошенные машины на дорогах
        val nRoads = w / step
        for (k in 0..nRoads) {
            val r0 = k * step
            for (b in 0 until nRoads) {
                val s0 = b * step
                if (rnd.nextFloat() < 0.6f) {          // горизонтальная дорога
                    val x = s0 + 5 + rnd.nextInt(15); val y = r0 + if (rnd.nextBoolean()) 0 else 2
                    if (t(x, y) == T.ROAD && t(x + 1, y) == T.ROAD && y < h) { set(x, y, T.CAR); set(x + 1, y, T.CAR_TAIL) }
                }
                if (rnd.nextFloat() < 0.6f) {          // вертикальная
                    val y = s0 + 5 + rnd.nextInt(15); val x = r0 + if (rnd.nextBoolean()) 0 else 2
                    if (t(x, y) == T.ROAD && t(x, y + 1) == T.ROAD && x < w) { set(x, y, T.CAR_V); set(x, y + 1, T.CAR_TAIL) }
                }
            }
        }
        // пара деревьев на газонах
        repeat(w * h / 120) {
            val x = rnd.nextInt(w); val y = rnd.nextInt(h)
            if (t(x, y) == T.GRASS && t(x + 1, y) == T.GRASS && t(x, y + 1) == T.GRASS && t(x - 1, y) == T.GRASS && t(x, y - 1) == T.GRASS) set(x, y, T.TREE)
        }
        // старт — тротуар у центрального перекрёстка
        val c = (w / step / 2) * step
        return (c + 4.5f) to (c + 4.5f - 1f)
    }

    private fun scatterTrees(rnd: Random, x0: Int, y0: Int, ww: Int, hh: Int) {
        repeat(4 + rnd.nextInt(4)) {
            val x = x0 + 1 + rnd.nextInt(ww - 2); val y = y0 + 1 + rnd.nextInt(hh - 2)
            set(x, y, T.TREE)
        }
    }

    /** kind: 0 — дом, 1 — магазин, 2 — полиция */
    private fun buildHouse(rnd: Random, lotX: Int, lotY: Int, doorTop: Boolean, kind: Int) {
        val hw = if (kind == 0) 7 + rnd.nextInt(3) else 9
        val hh = if (kind == 0) 7 + rnd.nextInt(3) else 9
        val x0 = lotX + (10 - hw) / 2; val y0 = lotY + (if (doorTop) 0 else 10 - hh)
        val x1 = x0 + hw - 1; val y1 = y0 + hh - 1
        val floorT = if (kind == 0) T.WOOD else T.TILEFLOOR
        for (y in y0..y1) for (x in x0..x1) {
            set(x, y, if (x == x0 || x == x1 || y == y0 || y == y1) T.WALL else floorT)
        }
        // входная дверь к дороге
        val dx = x0 + hw / 2
        val dy = if (doorTop) y0 else y1
        set(dx, dy, T.DOOR); tileHp[idx(dx, dy)] = 100f
        if (kind == 1) { set(dx - 1, dy, T.DOOR); tileHp[idx(dx - 1, dy)] = 100f }
        // окна
        for (y in y0..y1) for (x in x0..x1) {
            val edge = x == x0 || x == x1 || y == y0 || y == y1
            val corner = (x == x0 || x == x1) && (y == y0 || y == y1)
            if (!edge || corner || t(x, y) != T.WALL) continue
            if (abs(x - dx) <= 1 && y == dy) continue
            if (rnd.nextFloat() < (if (kind == 1) 0.45f else 0.22f)) { set(x, y, T.WINDOW); tileHp[idx(x, y)] = 60f }
        }
        when (kind) {
            1 -> { // магазин: ряды стеллажей
                var sx = x0 + 2
                while (sx <= x1 - 2) {
                    for (y in (y0 + 2)..(y1 - 2)) if (y != (if (doorTop) y0 + 1 else y1 - 1)) set(sx, y, T.STORESHELF)
                    sx += 2
                }
                set(x1 - 1, if (doorTop) y1 - 1 else y0 + 1, T.FRIDGE)
            }
            2 -> { // полиция
                set(x0 + 1, if (doorTop) y1 - 1 else y0 + 1, T.LOCKER)
                set(x0 + 2, if (doorTop) y1 - 1 else y0 + 1, T.LOCKER)
                set(x1 - 1, if (doorTop) y1 - 1 else y0 + 1, T.MEDCAB)
                set(x1 - 1, if (doorTop) y0 + 1 else y1 - 1, T.COUNTER)
            }
            else -> houseRooms(rnd, x0, y0, x1, y1, doorTop, dx)
        }
    }

    private fun houseRooms(rnd: Random, x0: Int, y0: Int, x1: Int, y1: Int, doorTop: Boolean, doorX: Int) {
        // вертикальная стена делит дом на две части
        var mid = (x0 + x1) / 2 + if (rnd.nextBoolean()) 1 else 0
        if (mid == doorX) mid += if (mid + 1 < x1 - 1) 1 else -1
        for (y in (y0 + 1) until y1) set(mid, y, T.WALL)
        val hmid = (y0 + y1) / 2
        var gap = y0 + 2 + rnd.nextInt((y1 - y0 - 3).coerceAtLeast(1))
        if (gap == hmid) gap = if (hmid + 1 < y1) hmid + 1 else hmid - 1
        set(mid, gap, T.DOOR_OPEN); tileHp[idx(mid, gap)] = 80f
        // часть без входной двери делим ещё и горизонтально
        val entryLeft = doorX < mid
        val sx0 = if (entryLeft) mid + 1 else x0 + 1
        val sx1 = if (entryLeft) x1 - 1 else mid - 1
        var split = false
        if (sx1 - sx0 >= 1 && y1 - y0 >= 7) {
            for (x in sx0..sx1) set(x, hmid, T.WALL)
            val g = sx0 + rnd.nextInt(sx1 - sx0 + 1)
            if (abs(g - mid) > 0) { set(g, hmid, T.DOOR_OPEN); tileHp[idx(g, hmid)] = 80f; split = true }
            else { for (x in sx0..sx1) set(x, hmid, T.WOOD); }
        }
        // кухня — в части со входом
        val ex0 = if (entryLeft) x0 + 1 else mid + 1
        val ex1 = if (entryLeft) mid - 1 else x1 - 1
        val far = if (doorTop) y1 - 1 else y0 + 1
        for (y in (y0 + 1) until y1) for (x in ex0..ex1) set(x, y, T.TILEFLOOR)
        place(rnd, ex0, ex1, far, intArrayOf(T.FRIDGE, T.COUNTER, T.COUNTER))
        if (split) {
            // верхняя и нижняя комнаты
            val topY = y0 + 1; val botY = y1 - 1
            place(rnd, sx0, sx1, topY, intArrayOf(if (rnd.nextBoolean()) T.WARDROBE else T.SHELF))
            place(rnd, sx0, sx1, botY, intArrayOf(if (rnd.nextFloat() < 0.5f) T.MEDCAB else T.WARDROBE))
        } else {
            place(rnd, sx0, sx1, y0 + 1, intArrayOf(T.WARDROBE))
            place(rnd, sx0, sx1, y1 - 1, intArrayOf(T.SHELF))
        }
    }

    /** Ставит мебель вдоль ряда y, не загораживая двери */
    private fun place(rnd: Random, xa: Int, xb: Int, y: Int, items: IntArray) {
        if (xb < xa) return
        val cells = (xa..xb).shuffled(rnd)
        var k = 0
        for (x in cells) {
            if (k >= items.size) break
            if (!T.isFloor(t(x, y))) continue
            var nearDoor = false
            for (d in 0 until 4) if (T.isDoorLike(t(x + DX4[d], y + DY4[d]))) nearDoor = true
            if (nearDoor) continue
            set(x, y, items[k]); k++
        }
    }

    /** Голова машины для клетки-хвоста */
    fun carHead(i: Int): Int {
        val x = i % w; val y = i / w
        if (t(x - 1, y) == T.CAR) return idx(x - 1, y)
        if (t(x, y - 1) == T.CAR_V) return idx(x, y - 1)
        return -1
    }

    // ---------- здания (для крыш) ----------
    /** [x0, y0, x1, y1] каждого здания и номер здания для клетки */
    val buildings = ArrayList<IntArray>()
    val buildingOf = IntArray(w * h) { -1 }

    private fun isBuildingTile(t: Int) = t == T.WALL || T.isFloor(t) || T.isDoorLike(t) || T.isWindowLike(t) || T.isFurniture(t)

    fun computeBuildings() {
        buildings.clear(); java.util.Arrays.fill(buildingOf, -1)
        val q = IntArray(w * h)
        for (start in 0 until w * h) {
            if (buildingOf[start] >= 0 || !isBuildingTile(tAt(start))) continue
            val id = buildings.size
            var x0 = w; var y0 = h; var x1 = 0; var y1 = 0
            var head = 0; var tail = 0
            q[tail++] = start; buildingOf[start] = id
            while (head < tail) {
                val i = q[head++]; val x = i % w; val y = i / w
                x0 = minOf(x0, x); y0 = minOf(y0, y); x1 = maxOf(x1, x); y1 = maxOf(y1, y)
                for (k in 0 until 4) {
                    val nx = x + DX4[k]; val ny = y + DY4[k]
                    if (!inside(nx, ny)) continue
                    val ni = idx(nx, ny)
                    if (buildingOf[ni] < 0 && isBuildingTile(tAt(ni))) { buildingOf[ni] = id; q[tail++] = ni }
                }
            }
            buildings.add(intArrayOf(x0, y0, x1, y1))
        }
    }

    fun lootFor(t: Int): List<Items.Loot>? = when (t) {
        T.FRIDGE -> Items.lootFridge
        T.COUNTER -> Items.lootCounter
        T.WARDROBE -> Items.lootWardrobe
        T.SHELF -> Items.lootShelf
        T.MEDCAB -> Items.lootMed
        T.LOCKER -> Items.lootPolice
        T.STORESHELF -> Items.lootStore
        T.CAR, T.CAR_V -> Items.lootCar
        else -> null
    }

    /** Лут генерируется при первом открытии, как в Zomboid */
    fun openContainer(i: Int, rnd: Random): MutableList<Stack> {
        containers[i]?.let { return it }
        val list = ArrayList<Stack>()
        val table = lootFor(tAt(i))
        if (table != null) {
            val total = table.sumOf { it.w }
            val rolls = rnd.nextInt(0, 4)
            repeat(rolls) {
                var r = rnd.nextInt(total)
                for (l in table) {
                    r -= l.w
                    if (r < 0) { addTo(list, l.id, rnd.nextInt(l.min, l.max + 1)); break }
                }
            }
        }
        containers[i] = list
        dirtyCont.add(i)
        return list
    }

    companion object {
        val DX4 = intArrayOf(1, -1, 0, 0)
        val DY4 = intArrayOf(0, 0, 1, -1)
        fun addTo(list: MutableList<Stack>, id: String, n: Int) {
            val s = list.firstOrNull { it.id == id }
            if (s != null) s.n += n else list.add(Stack(id, n))
        }
    }
}
