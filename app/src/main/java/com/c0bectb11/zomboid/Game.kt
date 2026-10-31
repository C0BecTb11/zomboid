package com.c0bectb11.zomboid

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class Player(var id: Int) {
    var x = 0f; var y = 0f
    var fx = 1f; var fy = 0f          // направление взгляда
    var hp = 100f
    var hunger = 10f                   // 0 — сыт, 100 — смерть от голода
    var thirst = 10f
    var stamina = 100f
    val body = Body()
    val bleeding get() = body.bleeding
    var lastCell = -1
    var sneaking = false
    // действие со временем (обыск, перевязка, баррикада…)
    var taAct: Act? = null
    var taName = ""
    var taTime = 0f
    var taDur = 0f
    // перелезание через окно
    var climb = -1f                   // прогресс 0..1, -1 — не лезет
    var clFx = 0f; var clFy = 0f; var clTx = 0f; var clTy = 0f
    val taProgress get() = if (taDur > 0f) (taTime / taDur).coerceIn(0f, 1f) else 0f
    var infection = 0f                 // секунды с момента укуса, 0 — здоров
    var dead = false
    var kills = 0
    var eq: String? = null
    val inv = ArrayList<Stack>()
    // ввод
    var mx = 0f; var my = 0f; var run = false
    var cooldown = 0f
    var connected = true
    // отрисовка у клиента
    var rx = 0f; var ry = 0f
    var attackAnim = 0f

    fun weapon(): ItemDef = eq?.let { Items.get(it) } ?: Items.FISTS
    fun weight(): Float = inv.sumOf { (it.def.weight * it.n).toDouble() }.toFloat()
    fun count(id: String) = inv.firstOrNull { it.id == id }?.n ?: 0
    fun take(id: String, n: Int = 1): Boolean {
        val s = inv.firstOrNull { it.id == id } ?: return false
        if (s.n < n) return false
        s.n -= n
        if (s.n <= 0) { inv.remove(s); if (eq == id) eq = null }
        return true
    }
}

class Zombie(val id: Int) {
    var x = 0f; var y = 0f
    var fx = 0f; var fy = 1f
    var hp = 100f
    var chase = 0f                    // таймер преследования
    var tx = Float.NaN; var ty = Float.NaN   // точка, куда идти на шум
    var wanderT = 0f; var wx = 0f; var wy = 0f
    var senseT = 0f
    var attackCd = 0f
    var stagger = 0f
    var bashT = 0f
    var speed = 1f
    var down = 0f                     // сбит с ног
    var climb = -1f; var clCell = -1
    var clFx = 0f; var clFy = 0f; var clTx = 0f; var clTy = 0f
    val skin get() = id % 6
    // отрисовка
    var rx = 0f; var ry = 0f
    var anim = Random.nextFloat() * 10f
    var seen = false
}

class Corpse(val x: Float, val y: Float, val zombie: Boolean)

sealed class Act {
    object Attack : Act()
    class Interact(val cell: Int) : Act()
    class Barricade(val cell: Int) : Act()
    class Use(val id: String) : Act()
    class Drop(val id: String) : Act()
    class Take(val cell: Int, val id: String) : Act()
    class Put(val cell: Int, val id: String) : Act()
    class Treat(val part: Int, val what: String) : Act()
}

class Game(val seed: Long) {
    val world = World(100, 100)
    val players = ArrayList<Player>()
    val zombies = ArrayList<Zombie>()
    val corpses = ArrayList<Corpse>()
    var time = 8 * 60f                // игровые минуты с начала (день 1, 08:00)
    val rnd = Random(seed xor System.nanoTime())
    private var nextZid = 1
    private var flowT = 0f
    var isReplica = false             // клиент: не симулирует, только принимает снимки
    val actions = java.util.concurrent.ConcurrentLinkedQueue<Pair<Int, Act>>()
    val messages = HashMap<Int, MutableList<String>>()
    val shots = ArrayList<FloatArray>()   // вспышки выстрелов для отрисовки [x1,y1,x2,y2,t]
    /** Эффекты для отрисовки: [тип, x, y, параметр]. 1 — кровь зомби, 2 — смерть зомби, 3 — ранен игрок (параметр = id), 4 — выстрел */
    val fx = ArrayList<FloatArray>()
    private val fxNet = ArrayList<FloatArray>()
    fun addFx(type: Int, x: Float, y: Float, a: Float) { val f = floatArrayOf(type.toFloat(), x, y, a); fx.add(f); fxNet.add(f); if (fxNet.size > 60) fxNet.removeAt(0) }
    fun clearNetFx() = fxNet.clear()

    companion object {
        const val PLAYER_R = 0.3f
        const val ZOMBIE_R = 0.3f
        const val MAX_WEIGHT = 15f
        const val MINUTES_PER_SEC = 1f   // 1 сек = 1 игровая минута, сутки = 24 мин

        fun newGame(seed: Long): Game {
            val g = Game(seed)
            val (sx, sy) = g.world.generate(seed)
            val p = Player(0); p.x = sx; p.y = sy
            p.inv.add(Stack("water", 1)); p.inv.add(Stack("chips", 1)); p.inv.add(Stack("bandage", 1))
            g.players.add(p)
            g.spawnZombies(65, sx, sy)
            g.world.computeBuildings()
            return g
        }
    }

    fun day(): Int = (time / 1440f).toInt() + 1
    fun hourF(): Float = (time % 1440f) / 60f
    fun clock(): String { val m = (time % 1440f).toInt(); return "%02d:%02d".format(m / 60, m % 60) }

    /** 0 — светло, 1 — глубокая ночь */
    fun darkness(): Float {
        val h = hourF()
        return when {
            h < 5f -> 1f
            h < 7f -> 1f - (h - 5f) / 2f
            h < 19f -> 0f
            h < 21.5f -> (h - 19f) / 2.5f
            else -> 1f
        }
    }

    fun player(id: Int) = players.firstOrNull { it.id == id }

    fun msg(pid: Int, text: String) { messages.getOrPut(pid) { ArrayList() }.add(text) }

    fun spawnZombies(n: Int, avoidX: Float, avoidY: Float) {
        var tries = 0; var made = 0
        while (made < n && tries < n * 50) {
            tries++
            val x = rnd.nextInt(world.w) + 0.5f; val y = rnd.nextInt(world.h) + 0.5f
            if (hypot(x - avoidX, y - avoidY) < 16f) continue
            if (world.circleBlocked(x, y, ZOMBIE_R)) continue
            val z = Zombie(nextZid++); z.x = x; z.y = y; z.rx = x; z.ry = y
            z.speed = 0.85f + rnd.nextFloat() * 0.45f
            z.hp = 80f + rnd.nextFloat() * 50f
            zombies.add(z); made++
        }
    }

    fun addPlayer(id: Int): Player {
        player(id)?.let { it.connected = true; return it }
        val host = players.firstOrNull()
        val p = Player(id)
        p.x = (host?.x ?: 50f) + 0.6f; p.y = host?.y ?: 50f
        if (world.circleBlocked(p.x, p.y, PLAYER_R)) { p.x = host?.x ?: 50f; p.y = host?.y ?: 50f }
        p.inv.add(Stack("water", 1)); p.inv.add(Stack("bandage", 1))
        players.add(p)
        return p
    }

    // ================== СИМУЛЯЦИЯ ==================
    fun update(dt: Float) {
        if (isReplica) return
        while (true) {
            val (pid, a) = actions.poll() ?: break
            val p = player(pid) ?: continue
            if (!p.dead) doAction(p, a)
        }
        time += dt * MINUTES_PER_SEC
        for (p in players) updatePlayer(p, dt)
        flowT -= dt
        if (flowT <= 0f) {
            flowT = 0.5f
            world.computeFlow(players.filter { !it.dead && it.connected }.map { floor(it.x).toInt() to floor(it.y).toInt() })
        }
        for (z in zombies) updateZombie(z, dt)
        separate()
        val it = zombies.iterator()
        while (it.hasNext()) {
            val z = it.next()
            if (z.hp <= 0f) { if (z.climb >= 0f && world.climbOcc[z.clCell] == z.id) world.climbOcc.remove(z.clCell); it.remove(); corpses.add(Corpse(z.x, z.y, true)); if (corpses.size > 80) corpses.removeAt(0) }
        }
        shots.removeAll { it[4] <= 0f }
        for (s in shots) s[4] -= dt
        // медленный прирост зомби, чтобы город не пустел
        val p0 = players.firstOrNull()
        if (p0 != null && zombies.size < 60 && rnd.nextFloat() < dt * 0.025f) spawnZombies(1, p0.x, p0.y)
    }

    private fun updatePlayer(p: Player, dt: Float) {
        if (p.dead || !p.connected) return
        p.cooldown -= dt
        p.attackAnim = (p.attackAnim - dt * 4f).coerceAtLeast(0f)
        val mag = hypot(p.mx, p.my)
        val heavy = p.weight() > MAX_WEIGHT
        var running = p.run && p.stamina > 1f && mag > 0.1f
        p.sneaking = !running && mag in 0.1f..0.6f
        var speed = if (p.sneaking) 1.25f else 2.3f
        if (running) speed = 3.9f
        if (heavy) { speed *= 0.6f; running = false }
        if (p.stamina < 10f) speed *= 0.8f
        val cell = world.tAt(world.idx(floor(p.x).toInt().coerceIn(0, world.w - 1), floor(p.y).toInt().coerceIn(0, world.h - 1)))
        if (cell == T.WINDOW_BROKEN) speed *= 0.4f
        speed *= p.body.legPenalty()
        val ci = world.idx(floor(p.x).toInt().coerceIn(0, world.w - 1), floor(p.y).toInt().coerceIn(0, world.h - 1))
        p.lastCell = ci
        if (p.climb >= 0f) {
            // перелезает через окно
            p.climb += dt / 1.3f
            val k = p.climb.coerceAtMost(1f)
            p.x = p.clFx + (p.clTx - p.clFx) * k; p.y = p.clFy + (p.clTy - p.clFy) * k
            if (p.climb >= 1f) { p.climb = -1f; world.climbOcc.values.remove(-1 - p.id) }
            return finishPlayer(p, dt, false, 0f)
        }
        if (mag > 0.1f) {
            val nx = p.mx / maxOf(1f, mag); val ny = p.my / maxOf(1f, mag)
            p.fx = p.mx / mag; p.fy = p.my / mag
            val bx = p.x; val by = p.y
            moveCircle(p.x, p.y, nx * speed * dt, ny * speed * dt, PLAYER_R) { x, y -> p.x = x; p.y = y }
            // упёрся в разбитое окно — перелезть
            if (hypot(p.x - bx, p.y - by) < speed * dt * 0.5f) {
                val wx = floor(p.x + nx * 0.6f).toInt(); val wy = floor(p.y + ny * 0.6f).toInt()
                if (world.inside(wx, wy)) {
                    val wc = world.idx(wx, wy)
                    if (world.tAt(wc) == T.WINDOW_BROKEN && world.barrAt(wc) == 0 && !world.climbOcc.containsKey(wc) && hypot(wx + 0.5f - p.x, wy + 0.5f - p.y) < 1.05f) {
                        val tg = world.climbTarget(wc, p.x, p.y)
                        if (!world.circleBlocked(tg[0], tg[1], PLAYER_R)) {
                            p.climb = 0f; p.clFx = p.x; p.clFy = p.y; p.clTx = tg[0]; p.clTy = tg[1]
                            p.fx = tg[0] - p.x; p.fy = tg[1] - p.y; val l = hypot(p.fx, p.fy); p.fx /= l; p.fy /= l
                            world.climbOcc[wc] = -1 - p.id; p.taAct = null
                            if (rnd.nextFloat() < 0.25f) {
                                val part = intArrayOf(BP.L_HAND, BP.R_HAND, BP.L_FORE, BP.R_FORE, BP.L_SHIN, BP.R_SHIN, BP.L_THIGH, BP.R_THIGH)[rnd.nextInt(8)]
                                wound(p, part, WT.GLASS); msg(p.id, "Порезался о стекло: ${BP.names[part].lowercase()}")
                            }
                            noise(p.x, p.y, 4f, p)
                        }
                    }
                }
            }
            if (running) {
                p.stamina -= dt * 12f
                if (rnd.nextFloat() < dt * 1.2f) noise(p.x, p.y, 6f, p)
            } else if (!p.sneaking && rnd.nextFloat() < dt * 0.5f) noise(p.x, p.y, 2.5f, p)
            if (p.taAct != null) { p.taAct = null; msg(p.id, "Прервано") }
        }
        // действие со временем
        p.taAct?.let { a ->
            p.taTime += dt
            if (p.taTime >= p.taDur) { p.taAct = null; perform(p, a) }
        }
        finishPlayer(p, dt, running, mag)
    }

    private fun finishPlayer(p: Player, dt: Float, running: Boolean, mag: Float) {
        if (!running) p.stamina = (p.stamina + dt * (if (mag > 0.1f) 6f else 12f)).coerceAtMost(100f)
        // голод и жажда (полная шкала за ~2 и ~1.3 игровых дня)
        p.hunger = (p.hunger + dt * 0.035f).coerceAtMost(100f)
        p.thirst = (p.thirst + dt * 0.052f).coerceAtMost(100f)
        if (p.hunger >= 90f || p.thirst >= 90f) p.hp -= dt * 0.4f
        else if (p.hunger < 60f && p.thirst < 60f && !p.bleeding && p.infection <= 0f && !p.body.woundInfected) p.hp = (p.hp + dt * 0.08f).coerceAtMost(100f)
        p.hp -= p.body.update(dt)
        if (p.infection > 0f) {
            p.infection += dt
            if (p.infection > 900f) p.hp -= dt * 0.25f
            if (p.infection.toInt() == 420 && p.infection - dt < 420f) msg(p.id, "Тебя знобит. Укус даёт о себе знать…")
        }
        if (p.hp <= 0f) {
            p.hp = 0f; p.dead = true
            corpses.add(Corpse(p.x, p.y, false))
            msg(p.id, "Ты погиб. Прожито дней: ${day() - 1}")
        }
    }

    inline fun moveCircle(x: Float, y: Float, dx: Float, dy: Float, r: Float, set: (Float, Float) -> Unit) {
        var nx = x; var ny = y
        val stuck = world.circleBlocked(x, y, r)
        if (stuck || !world.circleBlocked(nx + dx, ny, r)) nx += dx
        if (stuck || !world.circleBlocked(nx, ny + dy, r)) ny += dy
        set(nx, ny)
    }

    fun noise(x: Float, y: Float, radius: Float, src: Player?) {
        for (z in zombies) {
            val d = hypot(z.x - x, z.y - y)
            if (d > radius) continue
            if (src != null && d < radius * 0.6f) z.chase = maxOf(z.chase, 6f)
            z.tx = x + (rnd.nextFloat() - 0.5f) * 2f; z.ty = y + (rnd.nextFloat() - 0.5f) * 2f
        }
    }

    private fun updateZombie(z: Zombie, dt: Float) {
        z.attackCd -= dt; z.anim += dt
        if (z.climb >= 0f) {
            // лезет туловищем через окно, потом падает
            z.climb += dt / 2.4f
            val k = z.climb.coerceAtMost(1f)
            z.x = z.clFx + (z.clTx - z.clFx) * k; z.y = z.clFy + (z.clTy - z.clFy) * k
            if (z.climb >= 1f) { z.climb = -1f; if (world.climbOcc[z.clCell] == z.id) world.climbOcc.remove(z.clCell); z.down = 1.3f + rnd.nextFloat() }
            return
        }
        if (z.down > 0f) { z.down -= dt; return }
        if (z.stagger > 0f) { z.stagger -= dt; return }
        // активна только рядом с игроками — дальние бродят реже
        val near = players.filter { !it.dead && it.connected }.minByOrNull { hypot(it.x - z.x, it.y - z.y) }
        val nd = if (near != null) hypot(near.x - z.x, near.y - z.y) else 999f
        // чувства
        z.senseT -= dt
        if (z.senseT <= 0f && near != null && nd < 22f) {
            z.senseT = 0.3f
            val dark = darkness()
            val sight = (11f - 6f * dark) * (if (near.sneaking) 0.5f else 1f)
            if (nd < sight && world.los(z.x, z.y, near.x, near.y)) {
                // смотрит ли зомби в сторону игрока (или игрок очень близко)
                val dot = ((near.x - z.x) * z.fx + (near.y - z.y) * z.fy) / maxOf(nd, 0.01f)
                if (dot > -0.2f || nd < 3f || z.chase > 0f) z.chase = 10f
            }
        }
        var speed = 0.9f * z.speed
        var gx = 0f; var gy = 0f
        if (z.chase > 0f && near != null) {
            z.chase -= dt
            speed = 1.25f * z.speed
            if (nd < 1.6f && world.los(z.x, z.y, near.x, near.y)) {
                gx = near.x - z.x; gy = near.y - z.y
            } else {
                val cx = floor(z.x).toInt(); val cy = floor(z.y).toInt()
                var best = world.flow.getOrElse(world.idx(cx.coerceIn(0, world.w - 1), cy.coerceIn(0, world.h - 1))) { Int.MAX_VALUE }
                var bi = -1
                for (k in 0 until 4) {
                    val nx = cx + World.DX4[k]; val ny = cy + World.DY4[k]
                    if (!world.inside(nx, ny)) continue
                    val f = world.flow[world.idx(nx, ny)]
                    if (f < best) { best = f; bi = world.idx(nx, ny) }
                }
                if (bi >= 0) {
                    val bx = bi % world.w + 0.5f; val by = bi / world.w + 0.5f
                    if (world.tAt(bi) == T.WINDOW_BROKEN && world.barrAt(bi) == 0) {
                        if (hypot(bx - z.x, by - z.y) < 1.05f) {
                            z.fx = bx - z.x; z.fy = by - z.y; normFacing(z)
                            if (!world.climbOcc.containsKey(bi)) {
                                val tg = world.climbTarget(bi, z.x, z.y)
                                world.climbOcc[bi] = z.id; z.clCell = bi
                                z.climb = 0f; z.clFx = z.x; z.clFy = z.y; z.clTx = tg[0]; z.clTy = tg[1]
                                z.fx = tg[0] - z.x; z.fy = tg[1] - z.y; normFacing(z)
                            }
                            return    // ждёт своей очереди у окна
                        }
                    } else if (world.bashable(bi) && hypot(bx - z.x, by - z.y) < 1.05f) {
                        bash(z, bi, dt); z.fx = bx - z.x; z.fy = by - z.y; normFacing(z); return
                    }
                    gx = bx - z.x; gy = by - z.y
                } else { gx = near.x - z.x; gy = near.y - z.y }
            }
            if (nd < 0.8f && z.attackCd <= 0f) { attackPlayer(z, near); near.taAct = null }
        } else if (!z.tx.isNaN()) {
            gx = z.tx - z.x; gy = z.ty - z.y
            if (hypot(gx, gy) < 0.6f) { z.tx = Float.NaN; z.ty = Float.NaN }
        } else {
            if (nd > 40f && rnd.nextFloat() > 0.3f) return   // далёкие экономят процессор
            z.wanderT -= dt
            if (z.wanderT <= 0f) {
                z.wanderT = 2f + rnd.nextFloat() * 5f
                if (rnd.nextFloat() < 0.35f) { z.wx = 0f; z.wy = 0f } else {
                    val a = rnd.nextFloat() * 6.283f; z.wx = cos(a); z.wy = sin(a)
                }
            }
            gx = z.wx; gy = z.wy; speed = 0.35f * z.speed
        }
        val gl = hypot(gx, gy)
        if (gl > 0.01f) {
            z.fx = gx / gl; z.fy = gy / gl
            val before = z.x + z.y
            moveCircle(z.x, z.y, z.fx * speed * dt, z.fy * speed * dt, ZOMBIE_R) { x, y -> z.x = x; z.y = y }
            if (z.x + z.y == before && z.chase <= 0f) z.wanderT = 0f
        }
    }

    private fun normFacing(z: Zombie) { val l = hypot(z.fx, z.fy); if (l > 0f) { z.fx /= l; z.fy /= l } }

    private fun bash(z: Zombie, cell: Int, dt: Float) {
        z.bashT -= dt
        if (z.bashT > 0f) return
        z.bashT = 1.4f
        val hp = (world.tileHp[cell] ?: 60f) - (6f + rnd.nextFloat() * 6f)
        noise(cell % world.w + 0.5f, cell / world.w + 0.5f, 7f, null)
        if (hp > 0f) { world.tileHp[cell] = hp; return }
        val b = world.barrAt(cell)
        if (b > 0) {
            if (b == 1) world.barr.remove(cell) else world.barr[cell] = b - 1
            world.tileHp[cell] = 80f
        } else {
            when (world.tAt(cell)) {
                T.DOOR -> world.setIdx(cell, T.DOOR_BROKEN)
                T.WINDOW -> world.setIdx(cell, T.WINDOW_BROKEN)
            }
            world.tileHp.remove(cell)
        }
        world.dirtyTiles.add(cell)
    }

    private fun attackPlayer(z: Zombie, p: Player) {
        z.attackCd = 1.3f
        if (rnd.nextFloat() > 0.55f) return
        val part = BP.randomZombieTarget(rnd)
        addFx(3, p.x, p.y, p.id.toFloat())
        val pname = BP.names[part].lowercase()
        val r = rnd.nextFloat()
        when {
            r < 0.45f -> { p.hp -= 3f + rnd.nextFloat() * 4f; p.body.parts[part].bruise = 120f; msg(p.id, "Ушиб: $pname") }
            r < 0.75f -> { p.hp -= 5f; wound(p, part, WT.SCRATCH); msg(p.id, "Царапина: $pname") }
            r < 0.90f -> { p.hp -= 8f; wound(p, part, WT.LACERATION); msg(p.id, "Рваная рана: $pname") }
            else -> { p.hp -= 12f; wound(p, part, WT.BITE); msg(p.id, "УКУС: $pname!") }
        }
    }

    fun wound(p: Player, part: Int, type: Int) {
        val w = Wound(type)
        p.body.parts[part].wounds.add(w)
        // кровотечение сквозь повязку пачкает её
        p.body.parts[part].bandageAge += 60f
        if (p.infection <= 0f && rnd.nextFloat() < WT.zombieInfect[type]) p.infection = 0.01f
    }

    private fun separate() {
        val n = zombies.size
        for (i in 0 until n) {
            val a = zombies[i]
            for (j in i + 1 until n) {
                val b = zombies[j]
                val dx = b.x - a.x; val dy = b.y - a.y
                if (dx > 0.6f || dx < -0.6f || dy > 0.6f || dy < -0.6f) continue
                val d = sqrt(dx * dx + dy * dy)
                if (d < 0.55f && d > 0.0001f) {
                    val push = (0.55f - d) * 0.5f
                    val ux = dx / d * push; val uy = dy / d * push
                    if (!world.circleBlocked(a.x - ux, a.y - uy, ZOMBIE_R)) { a.x -= ux; a.y -= uy }
                    if (!world.circleBlocked(b.x + ux, b.y + uy, ZOMBIE_R)) { b.x += ux; b.y += uy }
                }
            }
        }
    }

    // ================== ДЕЙСТВИЯ ==================
    /** Сколько времени занимает действие (0 — мгновенно) и как оно называется */
    private fun timing(p: Player, a: Act): Pair<Float, String> = when (a) {
        is Act.Interact -> {
            val t = if (a.cell in world.tiles.indices) world.tAt(a.cell) else -1
            if (T.isContainer(t) && !world.containers.containsKey(a.cell)) 0.9f to "Обыск" else 0f to ""
        }
        is Act.Barricade -> 2.6f to "Заколачиваю"
        is Act.Treat -> when (a.what) { "bandage", "rag" -> 2.2f to "Перевязка"; "disinfect" -> 1.4f to "Обработка"; "glass" -> 3f to "Достаю стекло"; else -> 0f to "" }
        is Act.Use -> when (Items.get(a.id).kind) {
            ItemKind.FOOD -> 1.6f to "Ем"; ItemKind.DRINK -> 1.2f to "Пью"
            ItemKind.MED -> if (a.id == "pills") 0.8f to "Таблетки" else 2.2f to "Лечение"
            else -> 0f to ""
        }
        else -> 0f to ""
    }

    private fun doAction(p: Player, a: Act) {
        if (a is Act.Attack) p.taAct = null
        val (dur, name) = timing(p, a)
        if (dur > 0f) {
            if (p.taAct != null) return
            p.taAct = a; p.taDur = dur; p.taTime = 0f; p.taName = name
            return
        }
        perform(p, a)
    }

    private fun perform(p: Player, a: Act) {
        when (a) {
            is Act.Attack -> attack(p)
            is Act.Interact -> interact(p, a.cell)
            is Act.Barricade -> barricade(p, a.cell)
            is Act.Use -> use(p, a.id)
            is Act.Treat -> treat(p, a.part, a.what)
            is Act.Drop -> {
                val s = p.inv.firstOrNull { it.id == a.id } ?: return
                p.inv.remove(s); if (p.eq == s.id) p.eq = null
                val cell = world.idx(floor(p.x).toInt(), floor(p.y).toInt())
                val list = world.containers.getOrPut(cell) { ArrayList() }
                World.addTo(list, s.id, s.n); world.dirtyCont.add(cell)
            }
            is Act.Take -> {
                if (!nearCell(p, a.cell, 1.8f)) return
                val list = world.containers[a.cell] ?: return
                val s = list.firstOrNull { it.id == a.id } ?: return
                list.remove(s); World.addTo(p.inv, s.id, s.n); world.dirtyCont.add(a.cell)
                if (p.weight() > MAX_WEIGHT) msg(p.id, "Слишком тяжело — ты замедлился")
            }
            is Act.Put -> {
                if (!nearCell(p, a.cell, 1.8f)) return
                val s = p.inv.firstOrNull { it.id == a.id } ?: return
                p.inv.remove(s); if (p.eq == s.id) p.eq = null
                World.addTo(world.containers.getOrPut(a.cell) { ArrayList() }, s.id, s.n); world.dirtyCont.add(a.cell)
            }
        }
    }

    fun nearCell(p: Player, cell: Int, dist: Float): Boolean {
        if (cell !in world.tiles.indices) return false
        var cx = cell % world.w + 0.5f; var cy = cell / world.w + 0.5f
        when (world.tAt(cell)) { T.CAR -> cx += 0.5f; T.CAR_V -> cy += 0.5f }
        if (T.isCar(world.tAt(cell))) return hypot(cx - p.x, cy - p.y) < dist + 0.9f
        return hypot(cx - p.x, cy - p.y) < dist
    }

    private fun attack(p: Player) {
        if (p.cooldown > 0f) return
        val w = p.weapon()
        p.cooldown = w.cooldown
        p.attackAnim = 1f
        if (w.kind == ItemKind.GUN) {
            if (!p.take("ammo", 1)) { msg(p.id, "Нет патронов"); p.cooldown = 0.3f; return }
            val target = zombies.filter { hypot(it.x - p.x, it.y - p.y) < w.range && world.los(p.x, p.y, it.x, it.y) }
                .minByOrNull { val d = hypot(it.x - p.x, it.y - p.y); d - 3f * (((it.x - p.x) * p.fx + (it.y - p.y) * p.fy) / maxOf(d, 0.01f)) }
            var ex = p.x + p.fx * w.range; var ey = p.y + p.fy * w.range
            if (target != null) {
                val d = hypot(target.x - p.x, target.y - p.y)
                p.fx = (target.x - p.x) / d; p.fy = (target.y - p.y) / d
                ex = target.x; ey = target.y
                if (rnd.nextFloat() < 0.85f - d * 0.02f) hitZombie(p, target, w.dmg * (0.8f + rnd.nextFloat() * 0.4f))
            }
            shots.add(floatArrayOf(p.x + p.fx * 0.4f, p.y + p.fy * 0.4f, ex, ey, 0.08f))
            addFx(4, p.x + p.fx * 0.55f, p.y + p.fy * 0.55f, p.id.toFloat())
            noise(p.x, p.y, w.noise, p)
            return
        }
        p.stamina = (p.stamina - 4f).coerceAtLeast(0f)
        val reach = w.range + ZOMBIE_R
        fun inFront(z: Zombie): Boolean { val d = hypot(z.x - p.x, z.y - p.y); return ((z.x - p.x) * p.fx + (z.y - p.y) * p.fy) / maxOf(d, 0.01f) > 0.35f }
        // добить лежачего
        val downed = zombies.filter { it.down > 0f && hypot(it.x - p.x, it.y - p.y) < 1.2f }.minByOrNull { hypot(it.x - p.x, it.y - p.y) }
        if (downed != null && (zombies.none { it.down <= 0f && hypot(it.x - p.x, it.y - p.y) < reach && inFront(it) })) {
            val d = maxOf(hypot(downed.x - p.x, downed.y - p.y), 0.01f)
            p.fx = (downed.x - p.x) / d; p.fy = (downed.y - p.y) / d
            hitZombie(p, downed, maxOf(28f, w.dmg * 1.6f) * p.body.armPenalty())
            noise(p.x, p.y, w.noise, p)
            return
        }
        val cands = zombies.filter { it.down <= 0f && hypot(it.x - p.x, it.y - p.y) < reach }
        var target = cands.filter { inFront(it) }.minByOrNull { hypot(it.x - p.x, it.y - p.y) }
        if (target == null) target = cands.minByOrNull { hypot(it.x - p.x, it.y - p.y) }
        val tz = target
        if (tz != null) {
            val d = maxOf(hypot(tz.x - p.x, tz.y - p.y), 0.01f)
            p.fx = (tz.x - p.x) / d; p.fy = (tz.y - p.y) / d
            val weak = if (p.stamina < 10f) 0.6f else 1f
            hitZombie(p, tz, w.dmg * weak * p.body.armPenalty() * (0.8f + rnd.nextFloat() * 0.4f))
            moveCircle(tz.x, tz.y, p.fx * 0.35f, p.fy * 0.35f, ZOMBIE_R) { x, y -> tz.x = x; tz.y = y }
            tz.stagger = 0.45f
            // сбить с ног: тяжёлое оружие или толчок руками
            val kd = when { w.id == "fists" -> 0.3f; w.dmg >= 35f -> 0.35f; else -> 0.12f }
            if (tz.hp > 0f && rnd.nextFloat() < kd * weak) { tz.down = 2.2f + rnd.nextFloat() * 1.5f; tz.stagger = 0f }
        }
        noise(p.x, p.y, w.noise, p)
    }

    private fun hitZombie(p: Player, z: Zombie, dmg: Float) {
        z.hp -= dmg
        z.chase = 10f
        addFx(1, z.x, z.y, kotlin.math.atan2(z.y - p.y, z.x - p.x))
        if (z.hp <= 0f) { p.kills++; addFx(2, z.x, z.y, 0f) }
    }

    private fun interact(p: Player, cell: Int) {
        if (cell < 0 || cell >= world.tiles.size || !nearCell(p, cell, 1.8f)) return
        val t = world.tAt(cell)
        when {
            world.barrAt(cell) > 0 -> msg(p.id, "Заколочено")
            t == T.DOOR -> { world.setIdx(cell, T.DOOR_OPEN); noise(p.x, p.y, 3f, p) }
            t == T.DOOR_OPEN -> {
                val cx = cell % world.w + 0.5f; val cy = cell / world.w + 0.5f
                // если сам стоишь в проёме — шагнуть назад на свою сторону
                val inCell = abs(p.x - cx) < 0.5f + PLAYER_R && abs(p.y - cy) < 0.5f + PLAYER_R
                if (inCell) {
                    val alongY = world.windowAlongY(cell % world.w, cell / world.w)
                    val nx = if (alongY) p.x.coerceIn(cx - 0.5f + PLAYER_R + 0.02f, cx + 0.5f - PLAYER_R - 0.02f) else cx + (if (p.x < cx) -0.82f else 0.82f)
                    val ny = if (alongY) cy + (if (p.y < cy) -0.82f else 0.82f) else p.y.coerceIn(cy - 0.5f + PLAYER_R + 0.02f, cy + 0.5f - PLAYER_R - 0.02f)
                    if (!world.circleBlocked(nx, ny, PLAYER_R)) { p.x = nx; p.y = ny }
                }
                val blocked = players.any { !it.dead && abs(it.x - cx) < 0.5f + 0.1f && abs(it.y - cy) < 0.5f + 0.1f } ||
                    zombies.any { abs(it.x - cx) < 0.55f && abs(it.y - cy) < 0.55f }
                if (blocked) msg(p.id, "Что-то мешает закрыть") else { world.setIdx(cell, T.DOOR); noise(p.x, p.y, 3f, p) }
            }
            t == T.WINDOW -> {
                world.setIdx(cell, T.WINDOW_BROKEN); world.tileHp.remove(cell); noise(p.x, p.y, 12f, p)
                if (p.eq == null && rnd.nextFloat() < 0.5f) {
                    val hand = if (rnd.nextBoolean()) BP.R_HAND else BP.R_FORE
                    wound(p, hand, WT.GLASS); msg(p.id, "Разбил окно рукой — в ране стекло")
                } else msg(p.id, "Ты разбил окно")
            }
            T.isContainer(t) || world.containers.containsKey(cell) -> world.openContainer(cell, rnd)
        }
    }

    fun canBarricade(p: Player, cell: Int): Boolean {
        if (cell < 0) return false
        val t = world.tAt(cell)
        val okTile = t == T.DOOR || t == T.DOOR_BROKEN || T.isWindowLike(t)
        return okTile && world.barrAt(cell) < 3 && p.count("hammer") > 0 && p.count("planks") > 0
    }

    private fun barricade(p: Player, cell: Int) {
        if (!nearCell(p, cell, 1.8f) || !canBarricade(p, cell)) return
        val cx = cell % world.w + 0.5f; val cy = cell / world.w + 0.5f
        if (players.any { !it.dead && hypot(it.x - cx, it.y - cy) < 0.75f }) { msg(p.id, "Отойди от проёма"); return }
        p.take("planks", 1)
        world.barr[cell] = world.barrAt(cell) + 1
        world.tileHp[cell] = 80f
        world.dirtyTiles.add(cell)
        noise(p.x, p.y, 9f, p)
        msg(p.id, "Баррикада: ${world.barrAt(cell)}/3")
    }

    private fun use(p: Player, id: String) {
        val d = Items.get(id)
        if (p.count(id) <= 0) return
        when (d.kind) {
            ItemKind.FOOD, ItemKind.DRINK -> {
                p.take(id)
                p.hunger = (p.hunger - d.hunger).coerceIn(0f, 100f)
                p.thirst = (p.thirst - d.thirst).coerceIn(0f, 100f)
            }
            ItemKind.MED -> when (id) {
                "bandage", "rag" -> {
                    val part = (0 until BP.COUNT).sortedByDescending { needBandage(p.body.parts[it]) }.first()
                    if (needBandage(p.body.parts[part]) <= 0) msg(p.id, "Перевязывать нечего") else treat(p, part, id)
                }
                "disinfectant" -> {
                    val part = (0 until BP.COUNT).firstOrNull { k -> p.body.parts[k].wounds.any { !it.disinfected } }
                    if (part == null) msg(p.id, "Нет ран для обработки") else treat(p, part, "disinfect")
                }
                "tweezers" -> {
                    val part = (0 until BP.COUNT).firstOrNull { p.body.parts[it].hasGlass }
                    if (part == null) msg(p.id, "Осколков нет") else treat(p, part, "glass")
                }
                else -> { p.take(id); p.hp = (p.hp + d.heal).coerceAtMost(100f); msg(p.id, "Стало легче") }
            }
            ItemKind.MELEE, ItemKind.GUN -> p.eq = if (p.eq == id) null else id
            else -> {}
        }
    }

    private fun needBandage(bp: BodyPart): Int = when {
        bp.bleeding -> 3
        bp.wounds.isNotEmpty() && bp.bandage == 0 -> 2
        bp.dirty && bp.wounds.isNotEmpty() -> 1
        else -> 0
    }

    /** Лечение конкретной части тела: bandage, rag, unbandage, disinfect, glass */
    fun treat(p: Player, part: Int, what: String) {
        if (part !in 0 until BP.COUNT) return
        val bp = p.body.parts[part]
        val name = BP.names[part].lowercase()
        when (what) {
            "bandage", "rag" -> {
                if (!p.take(what)) { msg(p.id, "Нет: ${Items.get(what).name}"); return }
                if (bp.hasGlass) msg(p.id, "Сначала стоило достать стекло…")
                bp.bandage = if (what == "bandage") 1 else 2; bp.bandageAge = 0f
                msg(p.id, "Перевязано: $name")
            }
            "unbandage" -> { bp.bandage = 0; bp.bandageAge = 0f }
            "disinfect" -> {
                if (!p.take("disinfectant")) { msg(p.id, "Нужно дезинфицирующее средство"); return }
                for (w in bp.wounds) { w.disinfected = true; w.infection = 0f }
                p.hp = (p.hp - 1f).coerceAtLeast(1f)
                msg(p.id, "Рана обработана: $name. Щиплет!")
            }
            "glass" -> {
                if (p.count("tweezers") <= 0) { msg(p.id, "Нужен пинцет"); return }
                val w = bp.wounds.firstOrNull { it.glass } ?: return
                w.glass = false; w.bleed = maxOf(w.bleed, 30f)
                msg(p.id, "Осколок извлечён: $name")
            }
        }
    }

    // ================== ПОИСК ЦЕЛИ ДЛЯ КНОПКИ «ДЕЙСТВИЕ» ==================
    fun findInteract(p: Player): Int {
        var best = -1; var bestD = 9f
        val px = floor(p.x).toInt(); val py = floor(p.y).toInt()
        for (y in py - 1..py + 1) for (x in px - 1..px + 1) {
            if (!world.inside(x, y)) continue
            var i = world.idx(x, y)
            var t = world.tAt(i)
            if (t == T.CAR_TAIL) { i = world.carHead(i); if (i < 0) continue; t = world.tAt(i) }
            val useful = T.isContainer(t) || t == T.DOOR || t == T.DOOR_OPEN || t == T.WINDOW ||
                (world.containers[i]?.isNotEmpty() == true)
            if (!useful) continue
            val cx = x + 0.5f; val cy = y + 0.5f
            var d = hypot(cx - p.x, cy - p.y)
            if (d > 1.6f) continue
            d -= 0.4f * ((cx - p.x) * p.fx + (cy - p.y) * p.fy) / maxOf(d, 0.01f)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    fun findBarricade(p: Player): Int {
        var best = -1; var bestD = 9f
        val px = floor(p.x).toInt(); val py = floor(p.y).toInt()
        for (y in py - 1..py + 1) for (x in px - 1..px + 1) {
            if (!world.inside(x, y)) continue
            val i = world.idx(x, y)
            if (!canBarricade(p, i)) continue
            val d = hypot(x + 0.5f - p.x, y + 0.5f - p.y)
            if (d < 1.6f && d < bestD) { bestD = d; best = i }
        }
        return best
    }

    // ================== СОХРАНЕНИЕ ==================
    fun invJson(list: List<Stack>): JSONArray { val a = JSONArray(); for (s in list) a.put(JSONArray().put(s.id).put(s.n)); return a }
    fun invFrom(a: JSONArray?, to: MutableList<Stack>) { to.clear(); if (a == null) return; for (i in 0 until a.length()) { val e = a.getJSONArray(i); to.add(Stack(e.getString(0), e.getInt(1))) } }

    fun playerJson(p: Player): JSONObject = JSONObject().apply {
        put("id", p.id); put("x", r2(p.x)); put("y", r2(p.y)); put("fx", r2(p.fx)); put("fy", r2(p.fy))
        put("hp", r2(p.hp)); put("hu", r2(p.hunger)); put("th", r2(p.thirst)); put("st", r2(p.stamina))
        put("body", p.body.toJson()); put("inf", r2(p.infection)); put("dead", p.dead); put("k", p.kills)
        put("eq", p.eq ?: ""); put("inv", invJson(p.inv)); put("aa", r2(p.attackAnim)); put("con", p.connected)
        put("sn", p.sneaking); put("cl", r2(p.climb)); put("tn", if (p.taAct != null) p.taName else ""); put("tp", r2(p.taProgress))
    }

    fun playerFrom(o: JSONObject, p: Player) {
        p.x = o.getDouble("x").toFloat(); p.y = o.getDouble("y").toFloat()
        p.fx = o.getDouble("fx").toFloat(); p.fy = o.getDouble("fy").toFloat()
        p.hp = o.getDouble("hp").toFloat(); p.hunger = o.getDouble("hu").toFloat(); p.thirst = o.getDouble("th").toFloat()
        p.stamina = o.getDouble("st").toFloat(); p.body.fromJson(o.optJSONArray("body")); p.infection = o.getDouble("inf").toFloat()
        p.dead = o.getBoolean("dead"); p.kills = o.optInt("k"); p.eq = o.optString("eq").ifEmpty { null }
        invFrom(o.optJSONArray("inv"), p.inv); p.attackAnim = o.optDouble("aa", 0.0).toFloat()
        p.connected = o.optBoolean("con", true)
        p.sneaking = o.optBoolean("sn")
        p.climb = o.optDouble("cl", -1.0).toFloat()
        val tn = o.optString("tn")
        if (tn.isNotEmpty()) { p.taName = tn; p.taDur = 1f; p.taTime = o.optDouble("tp", 0.0).toFloat(); if (p.taAct == null) p.taAct = Act.Attack } else p.taAct = null
    }

    private fun r2(f: Float): Double = Math.round(f * 100.0) / 100.0

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("v", 1); o.put("seed", seed); o.put("time", time.toDouble())
        o.put("tiles", Base64.encodeToString(world.tiles, Base64.NO_WRAP))
        val hp = JSONArray(); for ((k, v) in world.tileHp) hp.put(JSONArray().put(k).put(v.toDouble())); o.put("thp", hp)
        val ba = JSONArray(); for ((k, v) in world.barr) ba.put(JSONArray().put(k).put(v)); o.put("barr", ba)
        val co = JSONObject(); for ((k, v) in world.containers) co.put(k.toString(), invJson(v)); o.put("cont", co)
        val zs = JSONArray(); for (z in zombies) zs.put(JSONArray().put(z.id).put(r2(z.x)).put(r2(z.y)).put(r2(z.hp))); o.put("z", zs)
        val ps = JSONArray(); for (p in players) ps.put(playerJson(p)); o.put("p", ps)
        val cs = JSONArray(); for (c in corpses) cs.put(JSONArray().put(r2(c.x)).put(r2(c.y)).put(c.zombie)); o.put("corpses", cs)
        return o
    }

    fun loadJson(o: JSONObject) {
        time = o.getDouble("time").toFloat()
        val t = Base64.decode(o.getString("tiles"), Base64.NO_WRAP)
        System.arraycopy(t, 0, world.tiles, 0, minOf(t.size, world.tiles.size))
        world.computeBuildings()
        world.tileHp.clear(); o.optJSONArray("thp")?.let { for (i in 0 until it.length()) { val e = it.getJSONArray(i); world.tileHp[e.getInt(0)] = e.getDouble(1).toFloat() } }
        world.barr.clear(); o.optJSONArray("barr")?.let { for (i in 0 until it.length()) { val e = it.getJSONArray(i); world.barr[e.getInt(0)] = e.getInt(1) } }
        world.containers.clear()
        o.optJSONObject("cont")?.let { co -> for (k in co.keys()) { val l = ArrayList<Stack>(); invFrom(co.getJSONArray(k), l); world.containers[k.toInt()] = l } }
        zombies.clear()
        o.optJSONArray("z")?.let { for (i in 0 until it.length()) {
            val e = it.getJSONArray(i); val z = Zombie(e.getInt(0))
            z.x = e.getDouble(1).toFloat(); z.y = e.getDouble(2).toFloat(); z.hp = e.getDouble(3).toFloat()
            z.rx = z.x; z.ry = z.y; z.speed = 0.85f + rnd.nextFloat() * 0.45f
            zombies.add(z); nextZid = maxOf(nextZid, z.id + 1)
        } }
        players.clear()
        o.optJSONArray("p")?.let { for (i in 0 until it.length()) {
            val e = it.getJSONObject(i); val p = Player(e.getInt("id")); playerFrom(e, p); p.rx = p.x; p.ry = p.y; players.add(p)
        } }
        corpses.clear()
        o.optJSONArray("corpses")?.let { for (i in 0 until it.length()) { val e = it.getJSONArray(i); corpses.add(Corpse(e.getDouble(0).toFloat(), e.getDouble(1).toFloat(), e.getBoolean(2))) } }
    }

    // ================== СЕТЕВЫЕ СНИМКИ ==================
    /** Снимок для клиента: позиции, изменения клеток и контейнеров */
    fun snapshot(forPid: Int): JSONObject {
        val o = JSONObject()
        o.put("t", "snap"); o.put("time", time.toDouble())
        val ps = JSONArray(); for (p in players) ps.put(playerJson(p)); o.put("p", ps)
        if (fxNet.isNotEmpty()) { val a = JSONArray(); for (f in fxNet) a.put(JSONArray().put(f[0].toDouble()).put(r2(f[1])).put(r2(f[2])).put(r2(f[3]))); o.put("fx", a); fxNet.clear() }
        val zs = JSONArray()
        for (z in zombies) zs.put(JSONArray().put(z.id).put(r2(z.x)).put(r2(z.y)).put(r2(z.fx)).put(r2(z.fy)).put(if (z.chase > 0f) 1 else 0).put(if (z.down > 0f) 1 else 0).put(z.hp <= 0f).put(r2(z.climb)))
        o.put("z", zs)
        if (world.dirtyTiles.isNotEmpty()) {
            val a = JSONArray(); for (i in world.dirtyTiles) a.put(JSONArray().put(i).put(world.tAt(i)).put(world.barrAt(i))); o.put("tc", a)
            world.dirtyTiles.clear()
        }
        if (world.dirtyCont.isNotEmpty()) {
            val co = JSONObject(); for (i in world.dirtyCont) co.put(i.toString(), invJson(world.containers[i] ?: emptyList())); o.put("cc", co)
            world.dirtyCont.clear()
        }
        messages.remove(forPid)?.let { m -> val a = JSONArray(); m.forEach { a.put(it) }; o.put("m", a) }
        if (shots.isNotEmpty()) { val a = JSONArray(); for (s in shots) a.put(JSONArray().put(r2(s[0])).put(r2(s[1])).put(r2(s[2])).put(r2(s[3]))); o.put("sh", a) }
        val newCorpses = JSONArray(); for (c in corpses.takeLast(80)) newCorpses.put(JSONArray().put(r2(c.x)).put(r2(c.y)).put(c.zombie)); o.put("cp", newCorpses)
        return o
    }

    fun applySnapshot(o: JSONObject, localPid: Int, onMsg: (String) -> Unit) {
        time = o.getDouble("time").toFloat()
        val ps = o.getJSONArray("p")
        for (i in 0 until ps.length()) {
            val e = ps.getJSONObject(i)
            val id = e.getInt("id")
            val p = player(id) ?: Player(id).also { pl -> pl.x = e.getDouble("x").toFloat(); pl.y = e.getDouble("y").toFloat(); pl.rx = pl.x; pl.ry = pl.y; players.add(pl) }
            playerFrom(e, p)
        }
        val zs = o.getJSONArray("z")
        val byId = HashMap<Int, Zombie>(zombies.size * 2); for (z in zombies) byId[z.id] = z
        val next = ArrayList<Zombie>(zs.length())
        for (i in 0 until zs.length()) {
            val e = zs.getJSONArray(i); val id = e.getInt(0)
            val z = byId[id] ?: Zombie(id).also { it.rx = e.getDouble(1).toFloat(); it.ry = e.getDouble(2).toFloat() }
            z.x = e.getDouble(1).toFloat(); z.y = e.getDouble(2).toFloat()
            z.fx = e.getDouble(3).toFloat(); z.fy = e.getDouble(4).toFloat(); z.chase = e.getInt(5).toFloat(); z.down = e.optInt(6).toFloat(); z.climb = e.optDouble(8, -1.0).toFloat()
            next.add(z)
        }
        zombies.clear(); zombies.addAll(next)
        o.optJSONArray("tc")?.let { for (i in 0 until it.length()) {
            val e = it.getJSONArray(i); val idx = e.getInt(0)
            val nt = e.getInt(1)
            if (world.tAt(idx) != nt) world.events.add(intArrayOf(idx, world.tAt(idx), nt))
            world.tiles[idx] = nt.toByte()
            val b = e.getInt(2); if (b > 0) world.barr[idx] = b else world.barr.remove(idx)
        } }
        o.optJSONObject("cc")?.let { co -> for (k in co.keys()) { val l = ArrayList<Stack>(); invFrom(co.getJSONArray(k), l); world.containers[k.toInt()] = l } }
        o.optJSONArray("m")?.let { for (i in 0 until it.length()) onMsg(it.getString(i)) }
        o.optJSONArray("fx")?.let { for (i in 0 until it.length()) { val e = it.getJSONArray(i); fx.add(floatArrayOf(e.getDouble(0).toFloat(), e.getDouble(1).toFloat(), e.getDouble(2).toFloat(), e.getDouble(3).toFloat())) } }
        o.optJSONArray("sh")?.let { for (i in 0 until it.length()) { val e = it.getJSONArray(i); shots.add(floatArrayOf(e.getDouble(0).toFloat(), e.getDouble(1).toFloat(), e.getDouble(2).toFloat(), e.getDouble(3).toFloat(), 0.08f)) } }
        o.optJSONArray("cp")?.let { corpses.clear(); for (i in 0 until it.length()) { val e = it.getJSONArray(i); corpses.add(Corpse(e.getDouble(0).toFloat(), e.getDouble(1).toFloat(), e.getBoolean(2))) } }
    }

    /** Клиент: старение вспышек выстрелов без симуляции */
    fun tickReplica(dt: Float) {
        shots.removeAll { it[4] <= 0f }
        for (s in shots) s[4] -= dt
    }
}
