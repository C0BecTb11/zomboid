package com.c0bectb11.zomboid

import android.opengl.GLES30
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** 3D-сцена: камера сверху под углом, солнце с тенями, фонарик, фонари, поле зрения, анимации. */
class Scene3D {
    // ---------- GL ----------
    private var prog = 0; private var depthProg = 0
    private val loc = HashMap<String, Int>()
    private var depthLocVP = 0
    private var shadowFbo = 0; private var shadowTex = 0
    private val SHADOW = 2048
    private var visTex = 0
    private val visBuf = ByteBuffer.allocateDirect(100 * 100).order(ByteOrder.nativeOrder())
    private var glReady = false
    private var vw = 1; private var vh = 1

    // ---------- мир ----------
    private var world: World? = null
    private var w3: World3D? = null
    private var chunks = arrayOf<Mesh>()
    private var dirty = BooleanArray(0)
    private var roofs = ArrayList<Mesh>()
    private val sb = MeshBuilder(60000)
    private val dyn = Mesh()
    private val db = MeshBuilder(40000)

    // ---------- состояние ----------
    private val doorAngle = HashMap<Int, Float>()
    private class Particle(var x: Float, var y: Float, var z: Float, var vx: Float, var vy: Float, var vz: Float, var life: Float, val max: Float, val color: Int, val size: Float)
    private val particles = ArrayList<Particle>()
    private class Decal(val x: Float, val z: Float, val rot: Float, val size: Float, val y: Float)
    private val blood = ArrayList<Decal>()
    private class Flash(val x: Float, val z: Float, var life: Float)
    private val flashes = ArrayList<Flash>()
    private val rnd = java.util.Random()
    private val walk = HashMap<Int, FloatArray>()
    private val zwalk = HashMap<Int, FloatArray>()
    private var vis = FloatArray(0)
    private var visTarget = ByteArray(0)
    private var visT = 0f
    private var insideBuilding = -1

    @Volatile var camYaw = 35f
    private var camX = Float.NaN; private var camZ = 0f
    private var shake = 0f
    var hurtFlash = 0f
    var vibrate = 0
    var target = -1

    private val proj = FloatArray(16); private val view = FloatArray(16); private val vp = FloatArray(16)
    private val lview = FloatArray(16); private val lproj = FloatArray(16); private val lvp = FloatArray(16)
    private val hudVP = FloatArray(16)
    @Volatile private var hudW = 1f; @Volatile private var hudH = 1f
    private val eye = FloatArray(3)

    // ================== GL ЖИЗНЕННЫЙ ЦИКЛ ==================
    fun onCreated() {
        prog = Shaders.program(Shaders.MAIN_VS, Shaders.MAIN_FS)
        depthProg = Shaders.program(Shaders.DEPTH_VS, Shaders.DEPTH_FS)
        for (n in listOf("uVP", "uLightVP", "uShadow", "uVis", "uSunDir", "uSunCol", "uAmb", "uNight", "uPlayer", "uCamBack", "uCut",
            "uFlashPos", "uFlashDir", "uFlash", "uNumLights", "uLights", "uLightCols", "uFogCol", "uCamPos", "uShadowTexel"))
            loc[n] = GLES30.glGetUniformLocation(prog, n)
        depthLocVP = GLES30.glGetUniformLocation(depthProg, "uLightVP")
        // карта теней
        val t = IntArray(1); GLES30.glGenTextures(1, t, 0); shadowTex = t[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_DEPTH_COMPONENT24, SHADOW, SHADOW, 0, GLES30.GL_DEPTH_COMPONENT, GLES30.GL_UNSIGNED_INT, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_MODE, GLES30.GL_COMPARE_REF_TO_TEXTURE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_FUNC, GLES30.GL_LEQUAL)
        val f = IntArray(1); GLES30.glGenFramebuffers(1, f, 0); shadowFbo = f[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_TEXTURE_2D, shadowTex, 0)
        GLES30.glDrawBuffers(1, intArrayOf(GLES30.GL_NONE), 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        // текстура видимости
        GLES30.glGenTextures(1, t, 0); visTex = t[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, visTex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, 100, 100, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        // все буферы потеряны — перестроить
        world = null
        glReady = true
    }

    fun onSize(w: Int, h: Int) { vw = w; vh = h; hudW = w.toFloat(); hudH = h.toFloat() }

    private fun attach(w: World) {
        for (c in chunks) c.release(); for (r in roofs) r.release()
        world = w
        val m = World3D(w); w3 = m
        chunks = Array(m.chunksX * m.chunksY) { Mesh() }
        dirty = BooleanArray(chunks.size) { true }
        roofs = ArrayList()
        for (bi in w.buildings.indices) { m.buildRoof(sb, bi); val mesh = Mesh(); mesh.upload(sb); roofs.add(mesh) }
        vis = FloatArray(w.w * w.h); visTarget = ByteArray(w.w * w.h)
        doorAngle.clear(); particles.clear(); blood.clear()
    }

    // ================== ОБНОВЛЕНИЕ (CPU) ==================
    fun update(dt: Float, gm: Game, me: Player, localPid: Int) {
        val w = gm.world
        if (w.events.isNotEmpty()) {
            for (ev in w.events) onTileEvent(w, ev[0], ev[1], ev[2])
            w.events.clear()
        }
        if (gm.fx.isNotEmpty()) { for (f in gm.fx) onFx(f, localPid, me, w); gm.fx.clear() }
        if (doorAngle.isNotEmpty()) {
            val di = doorAngle.entries.iterator()
            while (di.hasNext()) {
                val e = di.next()
                val tgt = when (w.tAt(e.key)) { T.DOOR_OPEN -> 90f; T.DOOR -> 0f; else -> { di.remove(); continue } }
                val v = e.value; val step = dt * 400f
                if (abs(tgt - v) <= step) di.remove() else e.setValue(v + if (tgt > v) step else -step)
            }
        }
        val pi = particles.iterator()
        while (pi.hasNext()) {
            val p = pi.next()
            p.life -= dt
            if (p.life <= 0f) { pi.remove(); continue }
            p.x += p.vx * dt; p.y += p.vy * dt; p.z += p.vz * dt
            p.vy -= 9f * dt
            if (p.y < 0.12f) { p.y = 0.12f; p.vy = 0f; p.vx *= 0.6f; p.vz *= 0.6f }
        }
        flashes.removeAll { it.life -= dt; it.life <= 0f }
        if (vis.size == w.w * w.h) {
            visT -= dt
            if (visT <= 0f) { visT = 0.1f; computeVis(gm, me) }
            val k = min(1f, dt * 7f); val r = 26
            val px = floor(me.rx).toInt(); val py = floor(me.ry).toInt()
            for (y in max(0, py - r)..min(w.h - 1, py + r)) for (x in max(0, px - r)..min(w.w - 1, px + r)) {
                val i = y * w.w + x; vis[i] += (visTarget[i] - vis[i]) * k
            }
        }
        val px = floor(me.rx).toInt().coerceIn(0, w.w - 1); val py = floor(me.ry).toInt().coerceIn(0, w.h - 1)
        val b = w.buildingOf.getOrElse(w.idx(px, py)) { -1 }
        insideBuilding = if (b >= 0 && w.t(px, py) != T.WALL) b else -1
        val tx = me.rx + me.fx * 0.6f; val tz = me.ry + me.fy * 0.6f
        if (camX.isNaN() || hypot(tx - camX, tz - camZ) > 6f) { camX = tx; camZ = tz }
        val ck = min(1f, dt * 5f)
        camX += (tx - camX) * ck; camZ += (tz - camZ) * ck
        shake = max(0f, shake - dt * 2.5f)
        hurtFlash = max(0f, hurtFlash - dt * 1.4f)
        for (p in gm.players) {
            val st = walk.getOrPut(p.id) { floatArrayOf(0f, p.rx, p.ry, 0f) }
            val d = hypot(p.rx - st[1], p.ry - st[2])
            if (d < 1f) st[0] += d * 6.5f
            st[3] = st[3] * 0.85f + (if (dt > 0f) d / dt else 0f) * 0.15f
            st[1] = p.rx; st[2] = p.ry
        }
        if (zwalk.size > 400) zwalk.clear()
        for (z in gm.zombies) {
            val st = zwalk.getOrPut(z.id) { floatArrayOf(rnd.nextFloat() * 6f, z.rx, z.ry, 0f) }
            val d = hypot(z.rx - st[1], z.ry - st[2])
            if (d < 1f) st[0] += d * 5.5f
            st[3] = st[3] * 0.85f + (if (dt > 0f) d / dt else 0f) * 0.15f
            st[1] = z.rx; st[2] = z.ry
        }
    }

    private fun computeVis(gm: Game, me: Player) {
        val w = gm.world
        java.util.Arrays.fill(visTarget, 0)
        val px = floor(me.rx).toInt(); val py = floor(me.ry).toInt()
        if (me.dead) {
            for (y in max(0, py - 20)..min(w.h - 1, py + 20)) for (x in max(0, px - 20)..min(w.w - 1, px + 20)) visTarget[y * w.w + x] = 1
            return
        }
        val R = 24f - 8f * gm.darkness(); val ri = R.toInt() + 1
        for (y in max(0, py - ri)..min(w.h - 1, py + ri)) for (x in max(0, px - ri)..min(w.w - 1, px + ri)) {
            val cx = x + 0.5f; val cy = y + 0.5f
            val dx = cx - me.rx; val dy = cy - me.ry
            val d = hypot(dx, dy)
            if (d > R) continue
            if (d > 2.2f && (dx * me.fx + dy * me.fy) / d < -0.3f) continue
            if (d < 1.2f || w.los(me.rx, me.ry, cx, cy)) visTarget[y * w.w + x] = 1
        }
    }

    private fun groundAt(w: World, x: Float, z: Float): Float {
        val t = w.t(floor(x).toInt(), floor(z).toInt())
        return when { t == T.SIDEWALK -> 0.12f; T.isFloor(t) || T.isDoorLike(t) || T.isWindowLike(t) -> 0.1f; else -> 0f }
    }

    private fun onTileEvent(w: World, i: Int, from: Int, to: Int) {
        val cx = i % w.w + 0.5f; val cz = i / w.w + 0.5f
        if ((from == T.DOOR && to == T.DOOR_OPEN) || (from == T.DOOR_OPEN && to == T.DOOR)) doorAngle[i] = doorAngle[i] ?: (if (from == T.DOOR_OPEN) 90f else 0f)
        if (from == T.WINDOW && to == T.WINDOW_BROKEN) {
            burst(cx, 1.4f, cz, 26, 0xFFBFE8F8.toInt(), 0.05f, 3.4f, 1.6f)
            markDirty(w, i)
        }
        if (T.isDoorLike(from) && to == T.DOOR_BROKEN) burst(cx, 1.0f, cz, 22, 0xFFA97A48.toInt(), 0.07f, 2.8f, 1.8f)
    }

    private fun markDirty(w: World, i: Int) {
        val m = w3 ?: return
        val c = (i / w.w / World3D.CHUNK) * m.chunksX + (i % w.w / World3D.CHUNK)
        if (c in dirty.indices) dirty[c] = true
    }

    private fun burst(x: Float, y: Float, z: Float, n: Int, col: Int, size: Float, speed: Float, life: Float, dir: Float? = null, spread: Float = 0.9f) {
        repeat(n) {
            val a = if (dir == null) rnd.nextFloat() * 6.283f else dir + (rnd.nextFloat() - 0.5f) * 2f * spread
            val sp = speed * (0.3f + rnd.nextFloat())
            particles.add(Particle(x, y, z, cos(a) * sp, 1f + rnd.nextFloat() * 2.5f, sin(a) * sp, life * (0.6f + rnd.nextFloat() * 0.6f), life * 1.2f, col, size * (0.6f + rnd.nextFloat())))
        }
        if (particles.size > 500) particles.subList(0, particles.size - 500).clear()
    }

    private fun addBlood(w: World, x: Float, z: Float, size: Float) {
        val bx = x + (rnd.nextFloat() - 0.5f) * 0.3f; val bz = z + (rnd.nextFloat() - 0.5f) * 0.3f
        blood.add(Decal(bx, bz, rnd.nextFloat() * 90f, size, groundAt(w, bx, bz) + 0.008f + rnd.nextFloat() * 0.003f))
        if (blood.size > 220) blood.removeAt(0)
    }

    private fun onFx(f: FloatArray, localPid: Int, me: Player, w: World) {
        val x = f[1]; val z = f[2]
        when (f[0].toInt()) {
            1 -> { burst(x, 1.2f, z, 14, 0xFF8A0E0E.toInt(), 0.04f, 2.6f, 0.7f, f[3], 0.6f); if (rnd.nextFloat() < 0.6f) addBlood(w, x + cos(f[3]) * 0.4f, z + sin(f[3]) * 0.4f, 0.35f) }
            2 -> { burst(x, 0.8f, z, 26, 0xFF7A0C0C.toInt(), 0.05f, 2.2f, 0.9f); addBlood(w, x, z, 0.8f) }
            3 -> {
                burst(x, 1.2f, z, 10, 0xFFA01414.toInt(), 0.04f, 2f, 0.6f)
                if (rnd.nextFloat() < 0.6f) addBlood(w, x, z, 0.4f)
                if (f[3].toInt() == localPid) { hurtFlash = 1f; shake = max(shake, 0.5f); vibrate = 60 }
            }
            4 -> {
                flashes.add(Flash(x, z, 0.1f))
                burst(x, 1.2f, z, 5, 0xFFFFE070.toInt(), 0.025f, 4f, 0.2f, atan2(me.fy, me.fx), 0.3f)
                if (f[3].toInt() == localPid) { shake = max(shake, 0.35f); vibrate = 25 }
            }
        }
    }

    // ================== ОТРИСОВКА ==================
    fun render(gm: Game, me: Player) {
        if (!glReady) return
        val w = gm.world
        if (world !== w || chunks.isEmpty()) attach(w)
        val m = w3 ?: return
        // перестроить грязные чанки
        for (i in dirty.indices) if (dirty[i]) { m.buildChunk(sb, i % m.chunksX, i / m.chunksX); chunks[i].upload(sb); dirty[i] = false }
        // видимость в текстуру
        visBuf.position(0)
        for (i in 0 until 100 * 100) visBuf.put(if (i < vis.size) (vis[i] * 255).toInt().coerceIn(0, 255).toByte() else 0)
        visBuf.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, visTex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, 100, 100, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, visBuf)

        // ---------- камера ----------
        val aspect = vw.toFloat() / max(1, vh)
        Matrix.perspectiveM(proj, 0, 32f, aspect, 1f, 140f)
        val pitch = Math.toRadians(54.0); val yaw = Math.toRadians(camYaw.toDouble())
        val D = 15.5f
        val sh = shake * shake * 0.25f
        val tx = camX + (rnd.nextFloat() - 0.5f) * sh; val tz = camZ + (rnd.nextFloat() - 0.5f) * sh; val ty = 0.6f
        eye[0] = tx + (sin(yaw) * cos(pitch) * D).toFloat(); eye[1] = ty + (sin(pitch) * D).toFloat(); eye[2] = tz + (cos(yaw) * cos(pitch) * D).toFloat()
        Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], tx, ty, tz, 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        synchronized(hudVP) { System.arraycopy(vp, 0, hudVP, 0, 16) }

        // ---------- солнце / луна ----------
        val dark = gm.darkness(); val h = gm.hourF()
        val sun = FloatArray(3); val sunCol = FloatArray(3); val amb = FloatArray(3); val fog = FloatArray(3)
        if (h in 5.5f..20.5f) {
            val az = Math.PI * (h - 6.0) / 14.0
            val el = max(0.0, sin(az)).toFloat()
            sun[0] = -(cos(az) * 0.85).toFloat(); sun[1] = -(0.3f + 0.7f * el); sun[2] = -0.45f
        } else { sun[0] = 0.35f; sun[1] = -0.85f; sun[2] = -0.4f }
        norm(sun)
        val dayC = floatArrayOf(1.0f, 0.95f, 0.86f); val duskC = floatArrayOf(1.0f, 0.6f, 0.36f); val nightC = floatArrayOf(0.42f, 0.52f, 0.85f)
        val dayA = floatArrayOf(0.56f, 0.59f, 0.66f); val duskA = floatArrayOf(0.34f, 0.28f, 0.33f); val nightA = floatArrayOf(0.05f, 0.065f, 0.12f)
        val warm = if (h in 5.5f..20.5f) { val el = sin(Math.PI * (h - 6.0) / 14.0).toFloat().coerceAtLeast(0f); ((1f - el) * (1f - el) * (1f - el)).coerceIn(0f, 1f) * 0.6f } else 0f
        for (i in 0..2) {
            if (dark < 0.5f) {
                val k = max(dark * 2f, warm)
                sunCol[i] = mix(dayC[i] * 1.0f, duskC[i] * 0.75f, k); amb[i] = mix(dayA[i], duskA[i], k)
            } else {
                val k = (dark - 0.5f) * 2f
                sunCol[i] = mix(duskC[i] * 0.7f, nightC[i] * 0.17f, k); amb[i] = mix(duskA[i], nightA[i], k)
            }
            fog[i] = mix(floatArrayOf(0.6f, 0.64f, 0.66f)[i], floatArrayOf(0.015f, 0.02f, 0.035f)[i], dark)
        }
        // матрица света: ортопроекция вокруг игрока
        val lx = tx - sun[0] * 40f; val ly = ty - sun[1] * 40f; val lz = tz - sun[2] * 40f
        Matrix.setLookAtM(lview, 0, lx, ly, lz, tx, ty, tz, 0f, 1f, 0f)
        Matrix.orthoM(lproj, 0, -26f, 26f, -26f, 26f, 1f, 100f)
        Matrix.multiplyMM(lvp, 0, lproj, 0, lview, 0)

        // ---------- динамика ----------
        buildDynamic(gm, me, dark)
        dyn.upload(db, true)

        // ---------- проход теней ----------
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFbo)
        GLES30.glViewport(0, 0, SHADOW, SHADOW)
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL); GLES30.glPolygonOffset(2f, 4f)
        GLES30.glUseProgram(depthProg)
        GLES30.glUniformMatrix4fv(depthLocVP, 1, false, lvp, 0)
        drawWorld(m, tx, tz, 30f)
        dyn.draw()
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        // ---------- основной проход ----------
        GLES30.glViewport(0, 0, vw, vh)
        GLES30.glClearColor(fog[0], fog[1], fog[2], 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glUseProgram(prog)
        GLES30.glUniformMatrix4fv(loc["uVP"]!!, 1, false, vp, 0)
        GLES30.glUniformMatrix4fv(loc["uLightVP"]!!, 1, false, lvp, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTex); GLES30.glUniform1i(loc["uShadow"]!!, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, visTex); GLES30.glUniform1i(loc["uVis"]!!, 1)
        GLES30.glUniform3f(loc["uSunDir"]!!, sun[0], sun[1], sun[2])
        GLES30.glUniform3f(loc["uSunCol"]!!, sunCol[0], sunCol[1], sunCol[2])
        GLES30.glUniform3f(loc["uAmb"]!!, amb[0], amb[1], amb[2])
        GLES30.glUniform1f(loc["uNight"]!!, dark)
        GLES30.glUniform3f(loc["uPlayer"]!!, me.rx, 1f, me.ry)
        val cb = floatArrayOf(sin(yaw).toFloat(), cos(yaw).toFloat())
        GLES30.glUniform2f(loc["uCamBack"]!!, cb[0], cb[1])
        GLES30.glUniform1f(loc["uCut"]!!, if (me.dead) 0f else 1f)
        GLES30.glUniform3f(loc["uFogCol"]!!, fog[0], fog[1], fog[2])
        GLES30.glUniform3f(loc["uCamPos"]!!, eye[0], eye[1], eye[2])
        GLES30.glUniform1f(loc["uShadowTexel"]!!, 1f / SHADOW)
        // фонарик
        val flash = if (me.dead) 0f else smooth(0.2f, 0.6f, dark)
        val fl = sqrt(me.fx * me.fx + me.fy * me.fy + 0.09f)
        GLES30.glUniform3f(loc["uFlashPos"]!!, me.rx + me.fx * 0.25f, 1.35f, me.ry + me.fy * 0.25f)
        GLES30.glUniform3f(loc["uFlashDir"]!!, me.fx / fl, -0.3f / fl, me.fy / fl)
        GLES30.glUniform1f(loc["uFlash"]!!, flash)
        setLights(gm, m, tx, tz, dark)
        drawWorld(m, tx, tz, 34f)
        dyn.draw()
    }

    private fun drawWorld(m: World3D, tx: Float, tz: Float, range: Float) {
        for (i in chunks.indices) {
            val cx = (i % m.chunksX) * World3D.CHUNK + World3D.CHUNK / 2f; val cz = (i / m.chunksX) * World3D.CHUNK + World3D.CHUNK / 2f
            if (abs(cx - tx) > range + World3D.CHUNK / 2f || abs(cz - tz) > range + World3D.CHUNK / 2f) continue
            chunks[i].draw()
        }
        val w = world ?: return
        for ((bi, r) in roofs.withIndex()) {
            if (bi == insideBuilding) continue
            val b = w.buildings[bi]
            if (abs((b[0] + b[2]) / 2f - tx) > range + 8 || abs((b[1] + b[3]) / 2f - tz) > range + 8) continue
            r.draw()
        }
    }

    private val lights = FloatArray(48); private val lightCols = FloatArray(36)

    private fun setLights(gm: Game, m: World3D, tx: Float, tz: Float, dark: Float) {
        var n = 0
        fun add(x: Float, y: Float, z: Float, r: Float, cr: Float, cg: Float, cb: Float) {
            if (n >= 12) return
            lights[n * 4] = x; lights[n * 4 + 1] = y; lights[n * 4 + 2] = z; lights[n * 4 + 3] = r
            lightCols[n * 3] = cr; lightCols[n * 3 + 1] = cg; lightCols[n * 3 + 2] = cb
            n++
        }
        for (f in flashes) add(f.x, 1.3f, f.z, 7f, 1.6f, 1.25f, 0.7f)
        if (dark > 0.15f) {
            for (p in gm.players) if (!p.dead && p.connected) add(p.rx, 1.6f, p.ry, 3.2f, 0.35f * dark, 0.33f * dark, 0.3f * dark)
            val near = m.lamps.filter { abs(it[2] - tx) < 20f && abs(it[3] - tz) < 20f }.sortedBy { hypot(it[2] - tx, it[3] - tz) }
            val k = smooth(0.15f, 0.5f, dark)
            for (l in near) add(l[2], 2.7f, l[3], 7.5f, 1.25f * k, 0.88f * k, 0.5f * k)
        }
        GLES30.glUniform1i(loc["uNumLights"]!!, n)
        if (n > 0) {
            GLES30.glUniform4fv(loc["uLights"]!!, n, lights, 0)
            GLES30.glUniform3fv(loc["uLightCols"]!!, n, lightCols, 0)
        }
    }

    // ================== ДИНАМИЧЕСКАЯ ГЕОМЕТРИЯ ==================
    private val shirts = intArrayOf(0xFF6A5A48.toInt(), 0xFFB0B0A6.toInt(), 0xFF4A7A4A.toInt(), 0xFF2A2A2E.toInt(), 0xFF5A6A3A.toInt(), 0xFF2E4A7A.toInt())
    private val pants = intArrayOf(0xFF3A3A44.toInt(), 0xFF5A5040.toInt(), 0xFF2E3A2E.toInt(), 0xFF1E1E22.toInt(), 0xFF4A5230.toInt(), 0xFF1E2A44.toInt())
    private val zskin = intArrayOf(0xFF8E9E7A.toInt(), 0xFF9AA088.toInt(), 0xFF86967A.toInt(), 0xFFA0A68E.toInt())
    private val hairs = intArrayOf(0xFF3A2A1A.toInt(), 0xFF8A8A84.toInt(), 0xFF1A1A1A.toInt(), 0xFF6A4A2A.toInt())

    private fun buildDynamic(gm: Game, me: Player, dark: Float) {
        val w = gm.world
        db.reset()
        val cx = camX; val cz = camZ; val R = 26f
        fun near(x: Float, z: Float) = abs(x - cx) < R && abs(z - cz) < R
        fun visAt(x: Float, z: Float): Float { val ix = floor(x).toInt(); val iz = floor(z).toInt(); return if (w.inside(ix, iz) && vis.size == w.w * w.h) vis[w.idx(ix, iz)] else 0f }

        // кровь на земле
        for (d in blood) {
            if (!near(d.x, d.z)) continue
            db.identity(); db.translate(d.x, d.y, d.z); db.rotY(d.rot)
            db.box(0f, 0f, 0f, d.size, 0.002f, d.size * 0.75f, 0xFF4A0808.toInt())
            db.box(d.size * 0.3f, 0.0005f, d.size * 0.2f, d.size * 0.4f, 0.002f, d.size * 0.3f, 0xFF3A0606.toInt())
        }
        // трупы
        for (c in gm.corpses) {
            if (!near(c.x, c.y)) continue
            val seed = ((c.x * 13 + c.y * 7).toInt() and 0x7fffffff)
            val gy = groundAt(w, c.x, c.y)
            db.identity(); db.translate(c.x, gy + 0.004f, c.y); db.rotY((seed % 360).toFloat())
            db.box(0f, 0f, -0.4f, 1.1f, 0.003f, 0.9f, 0xFF3E0707.toInt())
            val cols = if (c.zombie) intArrayOf(db.darken(shirts[seed % 6], 0.7f), db.darken(pants[seed % 6], 0.7f), db.darken(zskin[seed % 4], 0.7f), hairs[seed % 4])
                else intArrayOf(0xFF2A4A75.toInt(), 0xFF22262E.toInt(), 0xFF9A8060.toInt(), 0xFF3A2414.toInt())
            person(c.x, gy, c.y, (seed % 360).toFloat(), 0f, 0f, true, 0f, null, true, cols, false, false, 1f)
        }
        // двери, баррикады, кучи вещей
        val x0 = max(0, (cx - R).toInt()); val x1 = min(w.w - 1, (cx + R).toInt())
        val z0 = max(0, (cz - R).toInt()); val z1 = min(w.h - 1, (cz + R).toInt())
        for (y in z0..z1) for (x in x0..x1) {
            val i = w.idx(x, y); val t = w.tAt(i)
            if (t == T.DOOR || t == T.DOOR_OPEN) door(w, x, y, t, doorAngle[i] ?: (if (t == T.DOOR_OPEN) 90f else 0f))
            else if (t == T.DOOR_BROKEN) {
                db.identity(); db.translate(x + 0.5f, 0.12f, y + 0.5f); db.rotY((World3D.hash(x, y) % 180).toFloat())
                db.box(-0.15f, 0f, 0f, 0.8f, 0.04f, 0.18f, 0xFF8A5E34.toInt()); db.rotY(40f); db.box(0.2f, 0.03f, 0.1f, 0.6f, 0.04f, 0.14f, 0xFF6E4A2A.toInt())
            }
            val bl = w.barrAt(i)
            if (bl > 0) barricade(w, x, y, bl)
            if (!T.isContainer(t) && t != T.CAR_TAIL) {
                val pile = w.containers[i]
                if (pile != null && pile.isNotEmpty()) {
                    val gy = groundAt(w, x + 0.5f, y + 0.5f)
                    db.identity(); db.translate(x + 0.5f, gy, y + 0.5f); db.rotY((World3D.hash(x, y) % 60).toFloat())
                    db.box(0f, 0.15f, 0f, 0.42f, 0.3f, 0.36f, 0xFF9A7448.toInt(), 0f)
                    db.box(0f, 0.15f, 0f, 0.44f, 0.04f, 0.38f, 0xFF6A4A2A.toInt())
                    db.box(0.05f, 0.36f, 0.02f, 0.18f, 0.12f, 0.14f, Items.get(pile[0].id).color)
                }
            }
        }
        // зомби
        for (z in gm.zombies) {
            if (!near(z.rx, z.ry)) continue
            val v = visAt(z.rx, z.ry)
            if (v < 0.15f) continue
            val st = zwalk[z.id]
            val sk = z.skin
            val cols = intArrayOf(shirts[sk], pants[sk], zskin[z.id % 4], hairs[z.id % 4])
            val yaw = Math.toDegrees(atan2(z.fx.toDouble(), z.fy.toDouble())).toFloat()
            val gy = groundAt(w, z.rx, z.ry)
            person(z.rx, gy, z.ry, yaw, st?.get(0) ?: 0f, if ((st?.get(3) ?: 0f) > 0.15f) 1f else 0.25f, true, 0f, null, z.down > 0f, cols, false, z.chase > 0f, 0.95f + (z.id % 5) * 0.025f, stain = z.id % 2 == 0)
        }
        // игроки
        for (p in gm.players) {
            if (p.dead || !p.connected) continue
            val st = walk[p.id]
            val cols = if (p.id == 0) intArrayOf(0xFF3A6EA5.toInt(), 0xFF2E3440.toInt(), 0xFFE0B090.toInt(), 0xFF4A3020.toInt())
                else intArrayOf(0xFF4A9A5A.toInt(), 0xFF3A3A44.toInt(), 0xFFD8A47E.toInt(), 0xFF2A2018.toInt())
            val yaw = Math.toDegrees(atan2(p.fx.toDouble(), p.fy.toDouble())).toFloat()
            val gy = groundAt(w, p.rx, p.ry)
            val moving = if ((st?.get(3) ?: 0f) > 0.3f) 1f else 0f
            person(p.rx, gy, p.ry, yaw, st?.get(0) ?: 0f, moving, false, p.attackAnim, p.weapon(), false, cols, p.sneaking, false, 1f, longHair = p.id == 1)
        }
        // частицы
        for (p in particles) {
            if (!near(p.x, p.z)) continue
            val k = (p.life / p.max).coerceIn(0.3f, 1f)
            db.identity(); db.box(p.x, p.y, p.z, p.size * k, p.size * k, p.size * k, p.color)
        }
        // выстрелы
        for (s in gm.shots) {
            val dx = s[2] - s[0]; val dz = s[3] - s[1]; val len = hypot(dx, dz)
            db.identity(); db.translate((s[0] + s[2]) / 2, 1.2f, (s[1] + s[3]) / 2); db.rotY(Math.toDegrees(atan2(dx.toDouble(), dz.toDouble())).toFloat())
            db.box(0f, 0f, 0f, 0.03f, 0.03f, len, 0xFFFFE890.toInt(), 1f)
        }
        // цель действия — светящаяся рамка
        if (target >= 0 && target < w.tiles.size) {
            val tx = target % w.w; val tz = target / w.w
            var rw = 1f; var rh = 1f
            when (w.tAt(target)) { T.CAR -> rw = 2f; T.CAR_V -> rh = 2f }
            val gy = groundAt(w, tx + 0.5f, tz + 0.5f) + 0.02f
            val pulse = 0.5f + 0.5f * sin(System.currentTimeMillis() / 180f)
            val col = mixColor(0xFFB89A40.toInt(), 0xFFFFF0B0.toInt(), pulse)
            val th = 0.05f
            db.identity()
            db.aabb(tx + 0.05f, gy, tz + 0.05f, tx + rw - 0.05f, gy + 0.03f, tz + 0.05f + th, col, 1f)
            db.aabb(tx + 0.05f, gy, tz + rh - 0.05f - th, tx + rw - 0.05f, gy + 0.03f, tz + rh - 0.05f, col, 1f)
            db.aabb(tx + 0.05f, gy, tz + 0.05f, tx + 0.05f + th, gy + 0.03f, tz + rh - 0.05f, col, 1f)
            db.aabb(tx + rw - 0.05f - th, gy, tz + 0.05f, tx + rw - 0.05f, gy + 0.03f, tz + rh - 0.05f, col, 1f)
        }
        db.identity()
    }

    private fun door(w: World, x: Int, y: Int, t: Int, ang: Float) {
        val horiz = w3!!.wallLike(w.t(x - 1, y)) || w3!!.wallLike(w.t(x + 1, y))
        db.identity()
        if (horiz) { db.translate(x + 0.08f, 0.1f, y + 0.5f) } else { db.translate(x + 0.5f, 0.1f, y + 0.08f); db.rotY(-90f) }
        db.rotY(ang)
        val wood = 0xFFA0703E.toInt(); val dk = 0xFF6E4A2A.toInt()
        db.box(0.42f, 0.97f, 0f, 0.84f, 1.94f, 0.06f, wood, 2f)
        db.box(0.42f, 1.42f, 0.032f, 0.6f, 0.62f, 0.01f, dk, 2f); db.box(0.42f, 0.52f, 0.032f, 0.6f, 0.62f, 0.01f, dk, 2f)
        db.box(0.42f, 1.42f, -0.032f, 0.6f, 0.62f, 0.01f, dk, 2f); db.box(0.42f, 0.52f, -0.032f, 0.6f, 0.62f, 0.01f, dk, 2f)
        db.box(0.72f, 1.0f, 0.05f, 0.05f, 0.05f, 0.06f, 0xFFE0C050.toInt()); db.box(0.72f, 1.0f, -0.05f, 0.05f, 0.05f, 0.06f, 0xFFE0C050.toInt())
    }

    private fun barricade(w: World, x: Int, y: Int, level: Int) {
        val horiz = w3!!.wallLike(w.t(x - 1, y)) || w3!!.wallLike(w.t(x + 1, y))
        val window = T.isWindowLike(w.t(x, y))
        db.identity()
        db.translate(x + 0.5f, 0f, y + 0.5f)
        if (!horiz) db.rotY(90f)
        for (k in 0 until level) {
            val yy = (if (window) 1.15f else 0.6f) + k * 0.35f
            for (side in floatArrayOf(-0.16f, 0.16f)) {
                db.push(); db.translate(0f, yy, side); db.rotZ(if (k % 2 == 0) 9f else -8f)
                db.box(0f, 0f, 0f, 1.12f, 0.16f, 0.05f, if (k % 2 == 0) 0xFFB07A44.toInt() else 0xFF9A6A3A.toInt(), 2f)
                db.box(-0.45f, 0f, 0.03f, 0.03f, 0.03f, 0.02f, 0xFF606060.toInt()); db.box(0.45f, 0f, 0.03f, 0.03f, 0.03f, 0.02f, 0xFF606060.toInt())
                db.pop()
            }
        }
    }

    /** Персонаж из частей. Лицом вдоль +Z локально. */
    private fun person(x: Float, gy: Float, z: Float, yaw: Float, phase: Float, moving: Float, zombie: Boolean, attack: Float, weapon: ItemDef?,
                       lying: Boolean, cols: IntArray, sneak: Boolean, angry: Boolean, scale: Float, stain: Boolean = false, longHair: Boolean = false) {
        val shirt = cols[0]; val pant = cols[1]; val skin = cols[2]; val hair = cols[3]
        db.identity(); db.translate(x, gy, z); db.rotY(yaw)
        if (lying) { db.translate(0f, 0.14f, 0.7f); db.rotX(-90f) }
        db.cur.let { Matrix.scaleM(it, 0, scale, scale, scale) }
        if (sneak && !lying) { db.translate(0f, -0.12f, 0f); db.rotX(10f) }
        val sw = (sin(phase) * 32f) * moving
        // ноги
        for (s in intArrayOf(-1, 1)) {
            db.push(); db.translate(s * 0.1f, 0.74f, 0f)
            db.rotX(if (lying) s * 6f else sw * s * (if (sneak) 0.6f else 1f))
            db.box(0f, -0.33f, 0f, 0.15f, 0.66f, 0.17f, pant)
            db.box(0f, -0.69f, 0.05f, 0.16f, 0.08f, 0.26f, 0xFF1E1A16.toInt())
            db.pop()
        }
        // туловище
        db.push()
        if (zombie && !lying) db.rotZ(sin(phase * 0.5f) * 5f)
        db.box(0f, 1.0f, 0f, 0.44f, 0.56f, 0.25f, shirt)
        db.box(0f, 0.75f, 0f, 0.45f, 0.06f, 0.26f, db.darken(pant, 0.6f))
        if (stain) { db.box(0.08f, 1.06f, 0.128f, 0.2f, 0.24f, 0.01f, 0xFF5A0E0E.toInt()); db.box(-0.1f, 0.88f, 0.128f, 0.12f, 0.12f, 0.01f, 0xFF4A0A0A.toInt()) }
        // голова
        db.push(); db.translate(0f, 1.44f, 0f)
        if (zombie) db.rotZ(sin(phase * 0.7f) * 9f + 6f)
        db.box(0f, 0f, 0f, 0.26f, 0.28f, 0.26f, skin)
        db.box(0f, 0.12f, -0.015f, 0.28f, 0.07f, 0.29f, hair)
        db.box(0f, 0.0f, -0.12f, 0.28f, 0.24f, 0.05f, hair)
        if (longHair) db.box(0f, -0.14f, -0.13f, 0.26f, 0.3f, 0.06f, hair)
        if (zombie) {
            val eyeC = if (angry) 0xFFFF3020.toInt() else 0xFF301010.toInt()
            db.box(-0.06f, 0.03f, 0.131f, 0.05f, 0.035f, 0.01f, eyeC, if (angry) 1f else 0f); db.box(0.06f, 0.03f, 0.131f, 0.05f, 0.035f, 0.01f, eyeC, if (angry) 1f else 0f)
            db.box(0f, -0.08f, 0.131f, 0.1f, 0.03f, 0.01f, 0xFF3A1010.toInt())
        } else {
            db.box(-0.06f, 0.03f, 0.131f, 0.04f, 0.04f, 0.01f, 0xFF1A1A1A.toInt()); db.box(0.06f, 0.03f, 0.131f, 0.04f, 0.04f, 0.01f, 0xFF1A1A1A.toInt())
        }
        db.pop()
        // руки: s = +1 левая, -1 правая
        val gun = weapon?.kind == ItemKind.GUN
        for (s in intArrayOf(1, -1)) {
            db.push(); db.translate(s * 0.29f, 1.24f, 0f)
            val ang = when {
                lying -> -170f + s * 10f
                zombie -> -82f + sin(phase + s) * 9f
                gun -> -84f + (if (s == -1) -attack * 25f else 0f)
                s == -1 && attack > 0.01f -> -20f - 140f * attack
                s == -1 && weapon != null && weapon.id != "fists" -> -22f + sw * 0.3f
                else -> -sw * s * 0.9f
            }
            db.rotX(ang)
            if (gun && s == 1) db.rotZ(-18f)
            db.box(0f, -0.26f, 0f, 0.12f, 0.52f, 0.13f, if (zombie) db.darken(shirt, 0.9f) else shirt)
            db.box(0f, -0.56f, 0f, 0.11f, 0.11f, 0.11f, skin)
            if (s == -1 && weapon != null && weapon.id != "fists") {
                if (gun) {
                    db.box(0f, -0.66f, 0.02f, 0.07f, 0.18f, 0.08f, 0xFF26262A.toInt())
                    db.box(0f, -0.6f, 0.08f, 0.06f, 0.06f, 0.1f, 0xFF26262A.toInt())
                } else {
                    val len = 0.25f + weapon.range * 0.45f
                    val thick = if (weapon.id == "knife") 0.03f else if (weapon.id == "bat") 0.07f else 0.05f
                    db.box(0f, -0.58f, len / 2f - 0.05f, thick, thick, len, weapon.color)
                    if (weapon.id == "hammer") db.box(0f, -0.58f, len - 0.08f, 0.08f, 0.2f, 0.08f, 0xFF7A7E86.toInt())
                    if (weapon.id == "crowbar") db.box(0f, -0.52f, len - 0.08f, 0.04f, 0.14f, 0.04f, weapon.color)
                }
            }
            db.pop()
        }
        db.pop()
        db.identity()
    }

    // ================== ПОМОЩНИКИ ==================
    private fun mix(a: Float, b: Float, k: Float) = a + (b - a) * k.coerceIn(0f, 1f)
    private fun smooth(e0: Float, e1: Float, x: Float): Float { val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
    private fun norm(v: FloatArray) { val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); v[0] /= l; v[1] /= l; v[2] /= l }
    private fun mixColor(a: Int, b: Int, k: Float): Int {
        val r = mix(((a shr 16) and 255).toFloat(), ((b shr 16) and 255).toFloat(), k).toInt()
        val g = mix(((a shr 8) and 255).toFloat(), ((b shr 8) and 255).toFloat(), k).toInt()
        val bb = mix((a and 255).toFloat(), (b and 255).toFloat(), k).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bb
    }

    /** Мировая точка → экран (для интерфейса). null, если за камерой */
    fun project(x: Float, y: Float, z: Float): FloatArray? {
        val v = floatArrayOf(x, y, z, 1f); val r = FloatArray(4)
        synchronized(hudVP) { Matrix.multiplyMV(r, 0, hudVP, 0, v, 0) }
        if (r[3] <= 0.01f) return null
        return floatArrayOf((r[0] / r[3] * 0.5f + 0.5f) * hudW, (1f - (r[1] / r[3] * 0.5f + 0.5f)) * hudH)
    }

    /** Перевод экранного направления джойстика в мировое с учётом поворота камеры */
    fun screenToWorld(mx: Float, my: Float): FloatArray {
        val a = Math.toRadians(camYaw.toDouble())
        val c = cos(a).toFloat(); val s = sin(a).toFloat()
        return floatArrayOf(c * mx + s * my, -s * mx + c * my)
    }
}
