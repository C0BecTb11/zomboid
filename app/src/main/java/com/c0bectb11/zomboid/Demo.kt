package com.c0bectb11.zomboid

import kotlin.math.hypot

/** Демо-сцены для автоматических скриншотов (запуск с extra "demo"). */
object Demo {
    fun create(kind: String): Game {
        val g = Game.newGame(12345L)
        val p = g.players[0]
        g.time = 11 * 60f
        p.inv.add(Stack("bat", 1)); p.eq = "bat"
        when (kind) {
            "inside", "loot", "body", "inv" -> moveIntoHouse(g, p)
            "night" -> g.time = 23 * 60f
            "evening" -> g.time = 19 * 60f + 50f
            "climb" -> {
                // найти разбитое окно рядом и поставить игрока и зомби у него
                val w = g.world
                var best = -1; var bd = 1e9f
                for (i in w.tiles.indices) if (w.tAt(i) == T.WINDOW) { val d = kotlin.math.hypot(i % w.w - p.x, i / w.w - p.y); if (d < bd) { bd = d; best = i } }
                if (best >= 0) {
                    w.setIdx(best, T.WINDOW_BROKEN); w.events.clear()
                    val bx = best % w.w; val by = best / w.w
                    val out = if (w.windowAlongY(bx, by)) (if (T.isFloor(w.t(bx, by + 1))) floatArrayOf(bx + 0.5f, by - 0.5f) else floatArrayOf(bx + 0.5f, by + 1.5f))
                        else (if (T.isFloor(w.t(bx + 1, by))) floatArrayOf(bx - 0.5f, by + 0.5f) else floatArrayOf(bx + 1.5f, by + 0.5f))
                    p.x = out[0] + 1.2f; p.y = out[1] + 0.3f; p.rx = p.x; p.ry = p.y
                    g.zombies.removeAll { kotlin.math.hypot(it.x - p.x, it.y - p.y) < 12f }
                    val z = Zombie(9100); z.x = out[0]; z.y = out[1]; z.rx = z.x; z.ry = z.y
                    val tg = w.climbTarget(best, z.x, z.y)
                    z.climb = 0.45f; z.clCell = best; z.clFx = z.x; z.clFy = z.y; z.clTx = tg[0]; z.clTy = tg[1]
                    z.fx = tg[0] - z.x; z.fy = tg[1] - z.y; w.climbOcc[best] = z.id
                    z.x = z.clFx + (z.clTx - z.clFx) * 0.45f; z.y = z.clFy + (z.clTy - z.clFy) * 0.45f; z.rx = z.x; z.ry = z.y
                    g.zombies.add(z)
                    // игрок в процессе перелезания через соседнее окно не нужен — снимаем зомби в позе
                    g.freeze = true
                }
            }
        }
        if (kind == "zombies" || kind == "night") {
            for (k in 0 until 7) {
                val a = k * 0.9f
                val z = Zombie(9000 + k); z.x = p.x + kotlin.math.cos(a) * (2.5f + k * 0.5f); z.y = p.y + kotlin.math.sin(a) * (2.5f + k * 0.5f)
                if (!g.world.circleBlocked(z.x, z.y, Game.ZOMBIE_R)) { z.rx = z.x; z.ry = z.y; z.fx = p.x - z.x; z.fy = p.y - z.y; z.chase = 5f; g.zombies.add(z) }
            }
        }
        if (kind == "body" || kind == "inv") {
            g.wound(p, BP.L_FORE, WT.BITE); g.wound(p, BP.R_HAND, WT.GLASS); g.wound(p, BP.TORSO, WT.SCRATCH)
            p.body.parts[BP.L_SHIN].bruise = 100f
            g.wound(p, BP.R_THIGH, WT.LACERATION); p.body.parts[BP.R_THIGH].bandage = 1
            p.infection = 0f
            for (id in listOf("bandage", "rag", "disinfectant", "tweezers", "canned", "water", "pistol", "ammo", "planks", "hammer", "apple", "chips")) World.addTo(p.inv, id, if (id == "ammo") 12 else 2)
        }
        return g
    }

    private fun moveIntoHouse(g: Game, p: Player) {
        val w = g.world
        var best = -1; var bd = 1e9f
        for (y in 1 until w.h - 1) for (x in 1 until w.w - 1) {
            if (w.t(x, y) != T.TILEFLOOR) continue
            var furn = false
            for (k in 0 until 4) if (T.isFurniture(w.t(x + World.DX4[k], y + World.DY4[k]))) furn = true
            if (!furn) continue
            val d = hypot(x - p.x, y - p.y)
            if (d < bd) { bd = d; best = w.idx(x, y) }
        }
        if (best >= 0) { p.x = best % w.w + 0.5f; p.y = best / w.w + 0.5f; p.rx = p.x; p.ry = p.y }
        g.zombies.removeAll { hypot(it.x - p.x, it.y - p.y) < 10f }
    }
}
