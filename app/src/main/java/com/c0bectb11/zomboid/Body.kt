package com.c0bectb11.zomboid

import org.json.JSONArray
import kotlin.random.Random

/** Части тела и раны в духе Project Zomboid. */
object BP {
    const val HEAD = 0; const val NECK = 1; const val TORSO = 2; const val GROIN = 3
    const val L_UPPER = 4; const val L_FORE = 5; const val L_HAND = 6
    const val R_UPPER = 7; const val R_FORE = 8; const val R_HAND = 9
    const val L_THIGH = 10; const val L_SHIN = 11; const val L_FOOT = 12
    const val R_THIGH = 13; const val R_SHIN = 14; const val R_FOOT = 15
    const val COUNT = 16

    val names = arrayOf(
        "Голова", "Шея", "Торс", "Пах",
        "Левое плечо", "Левое предплечье", "Левая кисть",
        "Правое плечо", "Правое предплечье", "Правая кисть",
        "Левое бедро", "Левая голень", "Левая стопа",
        "Правое бедро", "Правая голень", "Правая стопа",
    )
    val arms = intArrayOf(L_UPPER, L_FORE, L_HAND, R_UPPER, R_FORE, R_HAND)
    val legs = intArrayOf(L_THIGH, L_SHIN, L_FOOT, R_THIGH, R_SHIN, R_FOOT)

    /** Куда чаще всего попадает зомби: руки (защищаешься), торс, реже шея и голова */
    private val zombieWeights = intArrayOf(3, 2, 10, 2, 8, 12, 10, 8, 12, 10, 4, 4, 1, 4, 4, 1)

    fun randomZombieTarget(rnd: Random): Int {
        var r = rnd.nextInt(zombieWeights.sum())
        for (i in zombieWeights.indices) { r -= zombieWeights[i]; if (r < 0) return i }
        return TORSO
    }
}

object WT {
    const val SCRATCH = 0
    const val LACERATION = 1
    const val BITE = 2
    const val GLASS = 3       // порез с осколком стекла внутри
    val names = arrayOf("Царапина", "Рваная рана", "Укус", "Порез со стеклом")
    /** полное заживление, сек */
    val healTime = floatArrayOf(240f, 600f, 800f, 500f)
    /** сколько кровоточит без перевязки, сек */
    val bleedTime = floatArrayOf(45f, 160f, 240f, 200f)
    /** потеря здоровья в секунду от кровотечения */
    val bleedRate = floatArrayOf(0.12f, 0.3f, 0.45f, 0.3f)
    /** шанс подхватить заражение зомби */
    val zombieInfect = floatArrayOf(0.05f, 0.15f, 0.6f, 0f)
}

class Wound(var type: Int) {
    var heal = WT.healTime[type]
    var bleed = WT.bleedTime[type]
    var glass = type == WT.GLASS
    var infection = 0f          // 0..1 — риск нагноения, 1 = рана воспалилась
    var disinfected = false
    val infected get() = infection >= 1f
    val bleeding get() = bleed > 0f
}

class BodyPart {
    val wounds = ArrayList<Wound>()
    var bandage = 0             // 0 — нет, 1 — бинт, 2 — тряпка
    var bandageAge = 0f         // сколько носится повязка; грязная после порога
    var bruise = 0f             // ушиб, сек до прохождения

    val dirty get() = bandage != 0 && bandageAge > (if (bandage == 1) 300f else 160f)
    val bleeding get() = bandage == 0 && wounds.any { it.bleeding }
    val hasGlass get() = wounds.any { it.glass }
    val infected get() = wounds.any { it.infected }
    val ok get() = wounds.isEmpty() && bruise <= 0f
}

class Body {
    val parts = Array(BP.COUNT) { BodyPart() }

    val bleeding get() = parts.any { it.bleeding }
    val woundInfected get() = parts.any { it.infected }

    fun armPenalty(): Float {
        var n = 0f
        for (i in BP.arms) { val p = parts[i]; n += p.wounds.size * 0.08f + (if (p.hasGlass) 0.1f else 0f) }
        return (1f - n).coerceIn(0.5f, 1f)
    }

    fun legPenalty(): Float {
        var n = 0f
        for (i in BP.legs) { val p = parts[i]; n += p.wounds.size * 0.07f + (if (p.hasGlass) 0.12f else 0f) + (if (p.bruise > 0f) 0.03f else 0f) }
        return (1f - n).coerceIn(0.55f, 1f)
    }

    /** Обновление ран. Возвращает потерю здоровья за кадр */
    fun update(dt: Float): Float {
        var loss = 0f
        for (p in parts) {
            if (p.bruise > 0f) p.bruise -= dt
            var bleedingHere = 0
            val it = p.wounds.iterator()
            while (it.hasNext()) {
                val w = it.next()
                if (w.bleed > 0f) {
                    w.bleed -= dt * (if (p.bandage != 0) 3f else 1f)
                    if (p.bandage == 0) loss += WT.bleedRate[w.type] * dt else bleedingHere++
                }
                // риск нагноения: открытая или под грязной повязкой рана
                if (!w.disinfected && !w.infected && (p.bandage == 0 || p.dirty)) {
                    w.infection += dt * (if (p.dirty) 0.004f else 0.0025f)
                }
                if (w.infected) loss += 0.04f * dt
                // заживление
                var rate = if (p.bandage != 0 && !p.dirty) 1f else 0.45f
                if (w.infected) rate *= 0.15f
                if (w.glass) rate = 0f
                w.heal -= dt * rate
                if (w.heal <= 0f && w.bleed <= 0f) it.remove()
            }
            if (p.bandage != 0) {
                p.bandageAge += dt * (1f + bleedingHere * 2f)
                if (p.wounds.isEmpty() && p.bandageAge > 400f) { p.bandage = 0; p.bandageAge = 0f }
            }
        }
        return loss
    }

    fun toJson(): JSONArray {
        val a = JSONArray()
        for (p in parts) {
            val ws = JSONArray()
            for (w in p.wounds) ws.put(JSONArray().put(w.type).put(r1(w.heal)).put(r1(w.bleed)).put(w.glass).put(r2(w.infection)).put(w.disinfected))
            a.put(JSONArray().put(p.bandage).put(r1(p.bandageAge)).put(r1(p.bruise)).put(ws))
        }
        return a
    }

    fun fromJson(a: JSONArray?) {
        for (p in parts) { p.wounds.clear(); p.bandage = 0; p.bandageAge = 0f; p.bruise = 0f }
        if (a == null) return
        for (i in 0 until minOf(a.length(), parts.size)) {
            val e = a.getJSONArray(i); val p = parts[i]
            p.bandage = e.getInt(0); p.bandageAge = e.getDouble(1).toFloat(); p.bruise = e.getDouble(2).toFloat()
            val ws = e.getJSONArray(3)
            for (k in 0 until ws.length()) {
                val we = ws.getJSONArray(k); val w = Wound(we.getInt(0))
                w.heal = we.getDouble(1).toFloat(); w.bleed = we.getDouble(2).toFloat(); w.glass = we.getBoolean(3)
                w.infection = we.getDouble(4).toFloat(); w.disinfected = we.getBoolean(5)
                p.wounds.add(w)
            }
        }
    }

    private fun r1(f: Float) = Math.round(f * 10.0) / 10.0
    private fun r2(f: Float) = Math.round(f * 100.0) / 100.0
}
