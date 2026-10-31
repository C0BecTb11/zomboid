package com.c0bectb11.zomboid

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class Mode { SOLO, HOST, CLIENT }

@SuppressLint("ViewConstructor")
class GameView(
    ctx: Context,
    private val mode: Mode,
    private var game: Game?,
    private val net: Net?,
    private val onExit: (deleteSave: Boolean) -> Unit,
) : SurfaceView(ctx), SurfaceHolder.Callback, Runnable {

    private var thread: Thread? = null
    @Volatile private var running = false
    private var localPid = if (mode == Mode.CLIENT) 1 else 0
    private val dp = resources.displayMetrics.density
    private var W = 1f; private var H = 1f

    // ---------- ввод ----------
    private class Touch(val type: Int, val pid: Int, val x: Float, val y: Float)
    private val touches = ConcurrentLinkedQueue<Touch>()
    private var joyPid = -1; private var joyBX = 0f; private var joyBY = 0f; private var joyX = 0f; private var joyY = 0f
    private var runPid = -1
    private var attackPid = -1; private var attackRepeat = 0f
    private var runToggle = false

    private enum class Panel { NONE, INV, LOOT, PAUSE, BODY }
    private var selPart = BP.TORSO
    private val treatBtns = ArrayList<Pair<RectF, String>>()

    // анимации: угол дверей и частицы (стекло, щепки)
    private val doorAngle = HashMap<Int, Float>()
    private class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val max: Float, val color: Int, val size: Float, var rot: Float, val vr: Float)
    private val particles = ArrayList<Particle>()
    private val prnd = java.util.Random()
    private var panel = Panel.NONE
    private var lootCell = -1
    private var selected: String? = null
    private var scroll = floatArrayOf(0f, 0f)
    private var panelPid = -1; private var panelDownX = 0f; private var panelDownY = 0f; private var panelLastY = 0f; private var panelDrag = false

    // ---------- сеть/служебное ----------
    private var initSent = false
    private var snapT = 0f
    private var inputT = 0f
    private var autosaveT = 60f
    private var lost = false
    private val msgs = ArrayList<Pair<String, Float>>()
    private var exitRequested = false
    private val hostIp: String by lazy {
        val ips = Net.localIps()
        ips.firstOrNull { it.startsWith("192.168.") } ?: ips.firstOrNull { it.startsWith("10.") || it.startsWith("172.") } ?: ips.firstOrNull() ?: "?"
    }

    // ---------- краски ----------
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pTile = Paint()
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 14f * resources.displayMetrics.density }
    private val pDark = Paint()
    private val rect = RectF()
    private val grass = intArrayOf(0xFF3D5C2C.toInt(), 0xFF41622F.toInt(), 0xFF395629.toInt())
    private val spr = Sprites(ctx)
    private val pBlood = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = android.graphics.PorterDuffColorFilter(0xCC6A0D0D.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
    }
    private val pCorpse = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = android.graphics.LightingColorFilter(0xFF8A8A8A.toInt(), 0)
    }

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    private fun g(): Game? = game

    /** Для демо-скриншотов: сразу открыть панель */
    var demoPanel: String? = null
        set(v) {
            field = v
            when (v) {
                "body" -> { panel = Panel.BODY; selPart = BP.L_FORE }
                "inv" -> { panel = Panel.INV; selected = "bandage" }
                "loot" -> game?.let { gm -> val me = gm.players[0]; val c = gm.findInteract(me); if (c >= 0) { gm.world.openContainer(c, gm.rnd); lootCell = c; panel = Panel.LOOT } }
            }
        }

    // ================== ЖИЗНЕННЫЙ ЦИКЛ ==================
    override fun surfaceCreated(h: SurfaceHolder) { start() }
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) { W = w.toFloat(); H = hh.toFloat() }
    override fun surfaceDestroyed(h: SurfaceHolder) { stop() }

    fun start() {
        if (running) return
        running = true
        thread = Thread(this, "game").apply { start() }
    }

    fun stop() {
        running = false
        try { thread?.join(1500) } catch (_: InterruptedException) {}
        thread = null
        saveNow()
    }

    fun saveNow() {
        val gm = game ?: return
        if (mode == Mode.CLIENT || deleteOnExit || demoPanel != null) return
        if (gm.players.isNotEmpty() && gm.players.all { it.dead }) return
        synchronized(gm) { Saves.save(context, gm) }
    }

    override fun run() {
        var last = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            val dt = min((now - last) / 1e9f, 0.05f)
            last = now
            try {
                synchronized(this) { tick(dt) }
                draw()
            } catch (e: Exception) {
                android.util.Log.e("zomboid", "loop", e)
            }
            if (exitRequested) {
                exitRequested = false
                running = false
                post { finishGame() }
            }
            val spent = (System.nanoTime() - now) / 1_000_000
            if (spent < 16) try { Thread.sleep(16 - spent) } catch (_: InterruptedException) {}
        }
    }

    private var deleteOnExit = false
    private fun finishGame() {
        if (!deleteOnExit) saveNow()
        net?.close()
        onExit(deleteOnExit)
    }

    // ================== ЛОГИКА КАДРА ==================
    private fun tick(dt: Float) {
        while (true) { val t = touches.poll() ?: break; handleTouch(t) }
        handleNet()
        val gm = g() ?: return
        val me = gm.player(localPid)

        // ввод игрока
        var mx = 0f; var my = 0f
        if (joyPid >= 0) {
            val r = 60 * dp
            mx = ((joyX - joyBX) / r).coerceIn(-1f, 1f); my = ((joyY - joyBY) / r).coerceIn(-1f, 1f)
            if (hypot(mx, my) < 0.15f) { mx = 0f; my = 0f }
        }
        val run = runPid >= 0 || runToggle
        if (attackPid >= 0) {
            attackRepeat -= dt
            if (attackRepeat <= 0f) { attackRepeat = 0.12f; act(Act.Attack) }
        }
        if (mode == Mode.CLIENT) {
            inputT -= dt
            if (inputT <= 0f) {
                inputT = 0.05f
                net?.send(JSONObject().put("t", "in").put("mx", mx.toDouble()).put("my", my.toDouble()).put("run", run))
            }
            gm.tickReplica(dt)
            val k = min(1f, dt * 12f)
            for (z in gm.zombies) { z.rx += (z.x - z.rx) * k; z.ry += (z.y - z.ry) * k; z.anim += dt }
            for (p in gm.players) {
                if (hypot(p.x - p.rx, p.y - p.ry) > 3f) { p.rx = p.x; p.ry = p.y }
                p.rx += (p.x - p.rx) * k; p.ry += (p.y - p.ry) * k
            }
        } else {
            me?.let { it.mx = mx; it.my = my; it.run = run }
            val paused = mode == Mode.SOLO && panel == Panel.PAUSE
            if (!paused) synchronized(gm) { gm.update(dt) }
            for (z in gm.zombies) { z.rx = z.x; z.ry = z.y }
            for (p in gm.players) { p.rx = p.x; p.ry = p.y }
            gm.messages.remove(localPid)?.forEach { addMsg(it) }
            if (mode == Mode.HOST && net != null) {
                if (net.connected && !initSent) {
                    synchronized(gm) { gm.addPlayer(1) }
                    net.send(JSONObject().put("t", "init").put("pid", 1).put("seed", gm.seed).put("world", gm.toJson()))
                    gm.world.dirtyTiles.clear(); gm.world.dirtyCont.clear()
                    initSent = true
                    addMsg("Второй игрок подключился")
                }
                snapT -= dt
                if (initSent && snapT <= 0f) { snapT = 1f / 15f; net.send(gm.snapshot(1)) }
            } else {
                gm.world.dirtyTiles.clear(); gm.world.dirtyCont.clear()
            }
            if (!paused) {
                autosaveT -= dt
                if (autosaveT <= 0f) { autosaveT = 60f; saveNow() }
            }
        }
        // анимации дверей и частицы
        if (gm.world.events.isNotEmpty()) {
            for (ev in gm.world.events) onTileEvent(gm.world, ev[0], ev[1], ev[2])
            gm.world.events.clear()
        }
        if (doorAngle.isNotEmpty()) {
            val di = doorAngle.entries.iterator()
            while (di.hasNext()) {
                val e = di.next()
                val target = when (gm.world.tAt(e.key)) { T.DOOR_OPEN -> 90f; T.DOOR -> 0f; else -> { di.remove(); continue } }
                val v = e.value; val step = dt * 420f
                if (kotlin.math.abs(target - v) <= step) di.remove() else e.setValue(v + if (target > v) step else -step)
            }
        }
        val pi = particles.iterator()
        while (pi.hasNext()) {
            val pt = pi.next()
            pt.life -= dt
            if (pt.life <= 0f) { pi.remove(); continue }
            pt.x += pt.vx * dt; pt.y += pt.vy * dt
            val fr = 1f - min(1f, dt * 4f); pt.vx *= fr; pt.vy *= fr
            pt.rot += pt.vr * dt * (pt.vx * pt.vx + pt.vy * pt.vy).coerceAtMost(1f)
        }

        // закрыть лут, если отошёл
        if (panel == Panel.LOOT && me != null && (!gm.nearCell(me, lootCell, 2.2f) || me.dead)) panel = Panel.NONE
        val it = msgs.listIterator()
        while (it.hasNext()) { val m = it.next(); if (m.second - dt <= 0f) it.remove() else it.set(m.first to m.second - dt) }
    }

    private fun addMsg(s: String) { msgs.add(s to 4f); if (msgs.size > 4) msgs.removeAt(0) }

    private fun handleNet() {
        val n = net ?: return
        while (true) {
            val o = n.inbox.poll() ?: break
            when (o.optString("t")) {
                "init" -> {
                    val gm = Game(o.getLong("seed")); gm.isReplica = true
                    gm.loadJson(o.getJSONObject("world"))
                    localPid = o.getInt("pid")
                    game = gm
                }
                "snap" -> g()?.applySnapshot(o, localPid) { addMsg(it) }
                "in" -> g()?.player(1)?.let { p ->
                    p.mx = o.optDouble("mx", 0.0).toFloat(); p.my = o.optDouble("my", 0.0).toFloat(); p.run = o.optBoolean("run")
                }
                "act" -> g()?.let { gm -> parseAct(o)?.let { gm.actions.add(1 to it) } }
                "disconnect" -> {
                    if (mode == Mode.HOST) {
                        g()?.player(1)?.let { it.connected = false; it.mx = 0f; it.my = 0f }
                        initSent = false
                        addMsg("Второй игрок отключился")
                    } else lost = true
                }
            }
        }
    }

    private fun parseAct(o: JSONObject): Act? = when (o.optString("a")) {
        "attack" -> Act.Attack
        "interact" -> Act.Interact(o.getInt("c"))
        "barr" -> Act.Barricade(o.getInt("c"))
        "use" -> Act.Use(o.getString("id"))
        "drop" -> Act.Drop(o.getString("id"))
        "take" -> Act.Take(o.getInt("c"), o.getString("id"))
        "put" -> Act.Put(o.getInt("c"), o.getString("id"))
        "treat" -> Act.Treat(o.getInt("c"), o.getString("id"))
        else -> null
    }

    private fun act(a: Act) {
        if (mode == Mode.CLIENT) {
            val o = JSONObject().put("t", "act")
            when (a) {
                is Act.Attack -> o.put("a", "attack")
                is Act.Interact -> o.put("a", "interact").put("c", a.cell)
                is Act.Barricade -> o.put("a", "barr").put("c", a.cell)
                is Act.Use -> o.put("a", "use").put("id", a.id)
                is Act.Drop -> o.put("a", "drop").put("id", a.id)
                is Act.Take -> o.put("a", "take").put("c", a.cell).put("id", a.id)
                is Act.Put -> o.put("a", "put").put("c", a.cell).put("id", a.id)
                is Act.Treat -> o.put("a", "treat").put("c", a.part).put("id", a.what)
            }
            net?.send(o)
        } else g()?.actions?.add(localPid to a)
    }

    // ================== КАСАНИЯ ==================
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex; touches.add(Touch(0, e.getPointerId(i), e.getX(i), e.getY(i)))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) touches.add(Touch(1, e.getPointerId(i), e.getX(i), e.getY(i)))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex; touches.add(Touch(2, e.getPointerId(i), e.getX(i), e.getY(i)))
            }
            MotionEvent.ACTION_CANCEL -> touches.add(Touch(3, -1, 0f, 0f))
        }
        return true
    }

    private class Btn(var x: Float, var y: Float, var r: Float) {
        fun hit(px: Float, py: Float) = hypot(px - x, py - y) < r * 1.15f
    }
    private val bAttack = Btn(0f, 0f, 0f)
    private val bInteract = Btn(0f, 0f, 0f)
    private val bRun = Btn(0f, 0f, 0f)
    private val bBarr = Btn(0f, 0f, 0f)
    private val bInv = Btn(0f, 0f, 0f)
    private val bPause = Btn(0f, 0f, 0f)
    private val bBody = Btn(0f, 0f, 0f)

    private fun layoutButtons() {
        bAttack.x = W - 88 * dp; bAttack.y = H - 84 * dp; bAttack.r = 50 * dp
        bInteract.x = W - 196 * dp; bInteract.y = H - 54 * dp; bInteract.r = 36 * dp
        bRun.x = W - 186 * dp; bRun.y = H - 150 * dp; bRun.r = 32 * dp
        bBarr.x = W - 80 * dp; bBarr.y = H - 196 * dp; bBarr.r = 32 * dp
        bPause.x = W - 40 * dp; bPause.y = 40 * dp; bPause.r = 26 * dp
        bBody.x = W - 168 * dp; bBody.y = 40 * dp; bBody.r = 26 * dp
        bInv.x = W - 104 * dp; bInv.y = 40 * dp; bInv.r = 26 * dp
    }

    private fun releaseControls() { joyPid = -1; runPid = -1; attackPid = -1 }

    private fun handleTouch(t: Touch) {
        val gm = g()
        if (t.type == 3) { releaseControls(); panelPid = -1; return }
        if (lost || gm == null) { if (t.type == 2) { exitRequested = true }; return }
        val me = gm.player(localPid) ?: return
        if (me.dead) {
            if (t.type == 2 && deadBtn.contains(t.x, t.y)) {
                deleteOnExit = mode == Mode.SOLO || (mode == Mode.HOST && gm.players.all { it.dead })
                exitRequested = true
            }
            return
        }
        if (panel != Panel.NONE) { panelTouch(gm, me, t); return }
        layoutButtons()
        when (t.type) {
            0 -> when {
                bPause.hit(t.x, t.y) -> { panel = Panel.PAUSE; releaseControls() }
                bInv.hit(t.x, t.y) -> { panel = Panel.INV; scroll[0] = 0f; releaseControls() }
                bBody.hit(t.x, t.y) -> { panel = Panel.BODY; releaseControls(); selPart = mostHurt(me) }
                barrCell >= 0 && bBarr.hit(t.x, t.y) -> act(Act.Barricade(barrCell))
                bRun.hit(t.x, t.y) -> { runToggle = !runToggle }
                bInteract.hit(t.x, t.y) -> {
                    val c = gm.findInteract(me)
                    if (c >= 0) {
                        act(Act.Interact(c))
                        val tt = gm.world.tAt(c)
                        if (T.isFurniture(tt) || (gm.world.containers[c]?.isNotEmpty() == true && !T.isDoorLike(tt) && !T.isWindowLike(tt))) {
                            lootCell = c; panel = Panel.LOOT; scroll[0] = 0f; scroll[1] = 0f; releaseControls()
                        }
                    } else {
                        // пусто рядом — показать вещи под ногами
                        val here = gm.world.idx(floor(me.x).toInt(), floor(me.y).toInt())
                        lootCell = here; panel = Panel.LOOT; scroll[0] = 0f; scroll[1] = 0f; releaseControls()
                    }
                }
                bAttack.hit(t.x, t.y) -> { attackPid = t.pid; attackRepeat = 0f }
                t.x < W * 0.55f && joyPid < 0 -> { joyPid = t.pid; joyBX = t.x; joyBY = t.y; joyX = t.x; joyY = t.y }
            }
            1 -> if (t.pid == joyPid) { joyX = t.x; joyY = t.y }
            2 -> {
                if (t.pid == joyPid) joyPid = -1
                if (t.pid == attackPid) attackPid = -1
                if (t.pid == runPid) runPid = -1
            }
        }
    }

    // ================== ПАНЕЛИ ==================
    private val panelRect = RectF()
    private val btnA = RectF(); private val btnB = RectF(); private val btnClose = RectF()
    private val deadBtn = RectF()
    private val rowH get() = 40 * dp

    private fun layoutPanel() {
        val pw = min(W * 0.86f, 720 * dp); val ph = H * 0.86f
        panelRect.set((W - pw) / 2, (H - ph) / 2, (W + pw) / 2, (H + ph) / 2)
        btnClose.set(panelRect.right - 48 * dp, panelRect.top + 8 * dp, panelRect.right - 8 * dp, panelRect.top + 44 * dp)
    }

    private fun colRect(col: Int, cols: Int): RectF {
        val top = panelRect.top + 56 * dp
        val w = if (cols == 1) panelRect.width() * 0.55f else panelRect.width() / 2
        val l = panelRect.left + col * w
        return RectF(l + 10 * dp, top, l + w - 10 * dp, panelRect.bottom - 12 * dp)
    }

    private fun rowAt(col: RectF, scr: Float, y: Float, n: Int): Int {
        if (y < col.top) return -1
        val i = ((y - col.top + scr) / rowH).toInt()
        return if (i in 0 until n) i else -1
    }

    private fun panelTouch(gm: Game, me: Player, t: Touch) {
        layoutPanel()
        when (t.type) {
            0 -> { if (panelPid < 0) { panelPid = t.pid; panelDownX = t.x; panelDownY = t.y; panelLastY = t.y; panelDrag = false } }
            1 -> if (t.pid == panelPid) {
                if (hypot(t.x - panelDownX, t.y - panelDownY) > 10 * dp) panelDrag = true
                if (panelDrag) {
                    val col = if (panel == Panel.LOOT && t.x > panelRect.centerX()) 1 else 0
                    scroll[col] = max(0f, scroll[col] - (t.y - panelLastY))
                }
                panelLastY = t.y
            }
            2 -> if (t.pid == panelPid) {
                panelPid = -1
                if (!panelDrag) panelTap(gm, me, t.x, t.y)
            }
        }
    }

    private fun panelTap(gm: Game, me: Player, x: Float, y: Float) {
        if (btnClose.contains(x, y)) { panel = Panel.NONE; return }
        when (panel) {
            Panel.PAUSE -> {
                if (btnA.contains(x, y)) panel = Panel.NONE
                if (btnB.contains(x, y)) { deleteOnExit = false; exitRequested = true }
            }
            Panel.INV -> {
                val col = colRect(0, 1)
                if (col.contains(x, y)) {
                    val i = rowAt(col, scroll[0], y, me.inv.size)
                    if (i >= 0) selected = me.inv[i].id
                }
                val sel = selected
                if (sel != null && me.count(sel) > 0) {
                    if (btnA.contains(x, y)) act(Act.Use(sel))
                    if (btnB.contains(x, y)) { act(Act.Drop(sel)); selected = null }
                }
            }
            Panel.LOOT -> {
                val left = colRect(0, 2); val right = colRect(1, 2)
                val cont = gm.world.containers[lootCell] ?: emptyList<Stack>()
                if (left.contains(x, y)) {
                    val i = rowAt(left, scroll[0], y, cont.size)
                    if (i >= 0) act(Act.Take(lootCell, cont[i].id))
                } else if (right.contains(x, y)) {
                    val i = rowAt(right, scroll[1], y, me.inv.size)
                    if (i >= 0) act(Act.Put(lootCell, me.inv[i].id))
                }
            }
            Panel.BODY -> {
                for ((r, what) in treatBtns) if (r.contains(x, y)) { act(Act.Treat(selPart, what)); return }
                val box = bodyBox()
                for (i in 0 until BP.COUNT) { partRect(box, i); if (rectTmp.contains(x, y)) { selPart = i; return } }
            }
            Panel.NONE -> {}
        }
    }

    // ================== ОТРИСОВКА ==================
    private var barrCell = -1

    private fun draw() {
        val h = holder
        val c: Canvas = try {
            (if (Build.VERSION.SDK_INT >= 26) h.lockHardwareCanvas() else h.lockCanvas()) ?: return
        } catch (e: Exception) { return }
        try {
            synchronized(this) { render(c) }
        } finally {
            try { h.unlockCanvasAndPost(c) } catch (_: Exception) {}
        }
    }

    private fun render(c: Canvas) {
        W = c.width.toFloat(); H = c.height.toFloat()
        c.drawColor(Color.BLACK)
        val gm = g()
        if (gm == null) {
            pText.textSize = 18 * dp; pText.textAlign = Paint.Align.CENTER
            c.drawText(if (lost) "Соединение потеряно — коснись экрана" else (net?.status ?: "Загрузка…"), W / 2, H / 2, pText)
            pText.textAlign = Paint.Align.LEFT
            return
        }
        val me = gm.player(localPid) ?: return
        val ts = H / 12.5f
        val camX = me.rx; val camY = me.ry
        val ox = W / 2 - camX * ts; val oy = H / 2 - camY * ts
        val world = gm.world
        val x0 = max(0, floor(camX - W / 2 / ts).toInt() - 1); val x1 = min(world.w - 1, floor(camX + W / 2 / ts).toInt() + 1)
        val y0 = max(0, floor(camY - H / 2 / ts).toInt() - 1); val y1 = min(world.h - 1, floor(camY + H / 2 / ts).toInt() + 1)

        for (y in y0..y1) for (x in x0..x1) drawTile(c, world, x, y, ox + x * ts, oy + y * ts, ts)

        // трупы
        for (cp in gm.corpses) {
            if (cp.x < x0 - 1 || cp.x > x1 + 1 || cp.y < y0 - 1 || cp.y > y1 + 1) continue
            val sx = ox + cp.x * ts; val sy = oy + cp.y * ts
            val rot = ((cp.x * 97f + cp.y * 31f) % 360f)
            c.save(); c.translate(sx, sy); c.rotate(rot)
            if (!spr.pivot(c, "t320", 32f, 32f, ts / 64f * 0.9f, pBlood)) { pFill.color = 0xAA5A1010.toInt(); c.drawCircle(0f, 0f, ts * 0.38f, pFill) }
            spr.pivot(c, if (cp.zombie) "z_stand" else "p0_hold", 17f, 21.5f, ts * 0.68f / 43f, pCorpse)
            c.restore()
        }

        // зомби (видимые)
        val dark = gm.darkness()
        val view = 20f - 13f * dark
        for (z in gm.zombies) {
            val d = hypot(z.rx - me.rx, z.ry - me.ry)
            if (d > view && !(d < 2.5f)) continue
            if (z.rx < x0 - 1 || z.rx > x1 + 1 || z.ry < y0 - 1 || z.ry > y1 + 1) continue
            if (!world.los(me.rx, me.ry, z.rx, z.ry)) continue
            drawZombie(c, z, ox + z.rx * ts, oy + z.ry * ts, ts)
        }
        // игроки
        for (p in gm.players) {
            if (p.dead || !p.connected) continue
            drawPlayer(c, p, ox + p.rx * ts, oy + p.ry * ts, ts)
        }
        // частицы
        for (pt in particles) {
            val a = (pt.life / pt.max).coerceIn(0f, 1f)
            pFill.color = (pt.color and 0x00FFFFFF) or ((a * 255).toInt() shl 24)
            c.save(); c.translate(ox + pt.x * ts, oy + pt.y * ts); c.rotate(pt.rot)
            val sz = pt.size * ts
            c.drawRect(-sz, -sz * 0.45f, sz, sz * 0.45f, pFill)
            c.restore()
        }
        // выстрелы
        pLine.color = 0xFFFFE070.toInt(); pLine.strokeWidth = 2.5f * dp
        for (s in gm.shots) c.drawLine(ox + s[0] * ts, oy + s[1] * ts, ox + s[2] * ts, oy + s[3] * ts, pLine)
        // кроны деревьев поверх
        for (y in y0..y1) for (x in x0..x1) if (world.t(x, y) == T.TREE) {
            val sx = ox + (x + 0.5f) * ts; val sy = oy + (y + 0.5f) * ts
            val name = if ((hash(x, y) and 7) == 0) "t186" else "t183"
            c.save(); c.translate(sx, sy); c.rotate(((hash(y, x) and 0x7fffffff) % 360).toFloat())
            if (!spr.pivot(c, name, 32f, 32f, ts * 1.7f / 64f)) {
                pFill.color = 0xFF2C5222.toInt(); c.drawCircle(0f, 0f, ts * 0.85f, pFill)
            }
            c.restore()
        }

        // темнота
        if (dark > 0.02f) {
            val a = (dark * 235).toInt().coerceIn(0, 255)
            val cx = ox + me.rx * ts; val cy = oy + me.ry * ts
            pDark.shader = RadialGradient(cx, cy, ts * 7.5f,
                intArrayOf(Color.argb(0, 0, 0, 10), Color.argb(a / 3, 0, 0, 10), Color.argb(a, 0, 0, 10)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, W, H, pDark)
        }

        // подсветка цели действия
        val target = gm.findInteract(me)
        if (target >= 0) {
            pLine.color = 0x99FFFFFF.toInt(); pLine.strokeWidth = 2 * dp
            val tx = target % world.w; val ty = target / world.w
            rect.set(ox + tx * ts + 2, oy + ty * ts + 2, ox + (tx + 1) * ts - 2, oy + (ty + 1) * ts - 2)
            c.drawRoundRect(rect, 6 * dp, 6 * dp, pLine)
        }
        barrCell = gm.findBarricade(me)

        drawHud(c, gm, me, target)
        when (panel) {
            Panel.INV -> drawInv(c, me)
            Panel.LOOT -> drawLoot(c, gm, me)
            Panel.PAUSE -> drawPause(c)
            Panel.BODY -> drawBody(c, gm, me)
            Panel.NONE -> {}
        }
        if (me.dead) drawDead(c, gm, me)
        if (lost) {
            pFill.color = 0xCC000000.toInt(); c.drawRect(0f, 0f, W, H, pFill)
            pText.textSize = 20 * dp; pText.textAlign = Paint.Align.CENTER
            c.drawText("Соединение с хостом потеряно", W / 2, H / 2, pText)
            pText.textSize = 14 * dp; c.drawText("Коснись, чтобы выйти в меню", W / 2, H / 2 + 30 * dp, pText)
            pText.textAlign = Paint.Align.LEFT
        }
    }

    private fun hash(x: Int, y: Int): Int { var h = x * 374761393 + y * 668265263; h = (h xor (h ushr 13)) * 1274126177; return h xor (h ushr 16) }

    private fun drawTile(c: Canvas, w: World, x: Int, y: Int, sx: Float, sy: Float, ts: Float) {
        val t = w.t(x, y)
        val e = sx + ts + 0.5f; val b = sy + ts + 0.5f
        val hv = hash(x, y) and 0x7fffffff
        fun fill(col: Int) { pTile.color = col; c.drawRect(sx, sy, e, b, pTile) }
        fun floorUnder() {
            var f = T.WOOD
            for (k in 0 until 4) { val n = w.t(x + World.DX4[k], y + World.DY4[k]); if (T.isFloor(n)) { f = n; break } }
            drawFloor(c, f, x, y, sx, sy, e, b, ts)
        }
        fun wallBrick() {
            if (!spr.tile(c, arrayOf("t42", "t44", "t46")[hv % 3], sx, sy, ts)) fill(0xFFC8743A.toInt())
            pTile.color = 0x40000000; c.drawRect(sx, b - ts * 0.12f, e, b, pTile)
        }
        when (t) {
            T.GRASS, T.TREE -> if (!spr.tile(c, arrayOf("t01", "t02", "t03", "t04")[hv % 4], sx, sy, ts)) fill(grass[hv % 3])
            T.ROAD -> {
                val cy = y % 25 == 1; val cx = x % 25 == 1
                val vroad = w.t(x, y - 1) == T.ROAD && w.t(x, y + 1) == T.ROAD && (w.t(x - 1, y) != T.ROAD || w.t(x + 1, y) != T.ROAD)
                val name = when {
                    cy && !cx && x % 2 == 0 && !vroad -> "t56"
                    cx && !cy && y % 2 == 0 && vroad -> "t55"
                    else -> "t86"
                }
                if (!spr.tile(c, name, sx, sy, ts)) fill(0xFF38383B.toInt())
            }
            T.SIDEWALK -> if (!spr.tile(c, if (hv % 5 == 0) "t09" else if (hv % 2 == 0) "t07" else "t08", sx, sy, ts)) fill(0xFF85857F.toInt())
            T.WOOD, T.TILEFLOOR -> drawFloor(c, t, x, y, sx, sy, e, b, ts)
            T.WALL -> wallBrick()
            T.DOOR, T.DOOR_OPEN, T.DOOR_BROKEN -> {
                floorUnder()
                val horiz = w.t(x - 1, y) == T.WALL || w.t(x + 1, y) == T.WALL || T.isWindowLike(w.t(x - 1, y)) || T.isWindowLike(w.t(x + 1, y))
                c.save()
                if (!horiz) { c.translate(sx + ts / 2, sy + ts / 2); c.rotate(90f); c.translate(-(sx + ts / 2), -(sy + ts / 2)) }
                if (t == T.DOOR_BROKEN) {
                    pLine.color = 0xFF8A5E34.toInt(); pLine.strokeWidth = ts * 0.1f
                    c.drawLine(sx + ts * 0.05f, sy + ts * 0.45f, sx + ts * 0.35f, sy + ts * 0.58f, pLine)
                    c.drawLine(sx + ts * 0.65f, sy + ts * 0.42f, sx + ts * 0.95f, sy + ts * 0.55f, pLine)
                } else {
                    // дверь вращается на петле
                    val ang = doorAngle[w.idx(x, y)] ?: (if (t == T.DOOR_OPEN) 90f else 0f)
                    val hx = sx + ts * 0.06f; val hy = sy + ts * 0.5f
                    c.rotate(-ang, hx, hy)
                    pTile.color = 0xFF6E4A2A.toInt(); c.drawRect(hx, hy - ts * 0.09f, hx + ts * 0.9f, hy + ts * 0.09f, pTile)
                    pTile.color = 0xFFA97A48.toInt(); c.drawRect(hx + ts * 0.03f, hy - ts * 0.06f, hx + ts * 0.87f, hy + ts * 0.06f, pTile)
                    pFill.color = 0xFFE0C050.toInt(); c.drawCircle(hx + ts * 0.76f, hy, ts * 0.045f, pFill)
                    pFill.color = 0xFF3A2A1A.toInt(); c.drawCircle(hx, hy, ts * 0.05f, pFill)
                }
                c.restore()
            }
            T.WINDOW, T.WINDOW_BROKEN -> {
                wallBrick()
                val horiz = w.t(x - 1, y) == T.WALL || w.t(x + 1, y) == T.WALL || T.isWindowLike(w.t(x - 1, y)) || T.isWindowLike(w.t(x + 1, y))
                c.save()
                if (!horiz) { c.translate(sx + ts / 2, sy + ts / 2); c.rotate(90f); c.translate(-(sx + ts / 2), -(sy + ts / 2)) }
                pTile.color = 0xFFE8E8E8.toInt(); c.drawRect(sx, sy + ts * 0.3f, sx + ts, sy + ts * 0.7f, pTile)
                if (t == T.WINDOW) {
                    pTile.color = 0xFFA8DCF0.toInt(); c.drawRect(sx + ts * 0.04f, sy + ts * 0.38f, sx + ts * 0.96f, sy + ts * 0.62f, pTile)
                    pTile.color = 0xCCFFFFFF.toInt(); c.drawRect(sx + ts * 0.15f, sy + ts * 0.42f, sx + ts * 0.4f, sy + ts * 0.46f, pTile)
                } else {
                    pTile.color = 0xFF2A3036.toInt(); c.drawRect(sx + ts * 0.04f, sy + ts * 0.38f, sx + ts * 0.96f, sy + ts * 0.62f, pTile)
                    pLine.color = 0xFFA8DCF0.toInt(); pLine.strokeWidth = 1.5f * dp
                    c.drawLine(sx + ts * 0.06f, sy + ts * 0.4f, sx + ts * 0.3f, sy + ts * 0.58f, pLine)
                    c.drawLine(sx + ts * 0.94f, sy + ts * 0.4f, sx + ts * 0.72f, sy + ts * 0.6f, pLine)
                }
                c.restore()
            }
            else -> if (T.isFurniture(t)) {
                floorUnder()
                val name = when (t) {
                    T.FRIDGE -> "t322"
                    T.COUNTER -> if (hv % 3 == 0) "t297" else "t294"
                    T.WARDROBE -> "t453"
                    T.SHELF -> "t430"
                    T.MEDCAB -> "t269"
                    T.LOCKER -> "t536"
                    else -> if (hv % 2 == 0) "t431" else "t432"
                }
                if (!spr.tile(c, name, sx, sy, ts)) {
                    pFill.color = 0xFF8A6238.toInt(); rect.set(sx + ts * 0.08f, sy + ts * 0.08f, e - ts * 0.1f, b - ts * 0.1f); c.drawRoundRect(rect, ts * 0.08f, ts * 0.08f, pFill)
                }
                if (t == T.MEDCAB) {
                    pTile.color = 0xFFD03030.toInt()
                    c.drawRect(sx + ts * 0.45f, sy + ts * 0.3f, sx + ts * 0.55f, sy + ts * 0.7f, pTile)
                    c.drawRect(sx + ts * 0.3f, sy + ts * 0.45f, sx + ts * 0.7f, sy + ts * 0.55f, pTile)
                }
                if (w.containers[w.idx(x, y)]?.isEmpty() == true) { pFill.color = 0x50000000; c.drawRect(sx, sy, e, b, pFill) }
            }
        }
        // кучка вещей на полу — ящик
        if (!T.isFurniture(t)) {
            val pile = w.containers[w.idx(x, y)]
            if (pile != null && pile.isNotEmpty()) {
                c.save(); c.translate(sx + ts / 2, sy + ts / 2); c.rotate((hv % 40 - 20).toFloat())
                if (!spr.pivot(c, "t157", 32f, 32f, ts / 64f * 0.9f)) { pFill.color = 0xFFB08850.toInt(); c.drawCircle(0f, 0f, ts * 0.15f, pFill) }
                c.restore()
                Icons.draw(c, pile[0].id, sx + ts * 0.62f, sy + ts * 0.38f, ts * 0.42f)
            }
        }
        // баррикада — доски крест-накрест
        val bl = w.barrAt(w.idx(x, y))
        if (bl > 0) {
            for (k in 0 until bl) {
                c.save(); c.translate(sx + ts / 2, sy + ts / 2)
                if (k == 1) c.scale(-1f, 1f)
                if (k == 2) c.rotate(45f)
                if (!spr.pivot(c, "t265", 32f, 32f, ts / 64f * 1.05f)) {
                    pLine.color = 0xFFA07040.toInt(); pLine.strokeWidth = ts * 0.12f
                    c.drawLine(-ts * 0.45f, -ts * 0.3f, ts * 0.45f, ts * 0.3f, pLine)
                }
                c.restore()
            }
        }
    }

    private fun drawFloor(c: Canvas, t: Int, x: Int, y: Int, sx: Float, sy: Float, e: Float, b: Float, ts: Float) {
        val ok = if (t == T.TILEFLOOR) spr.tile(c, "t12", sx, sy, ts)
        else spr.tile(c, arrayOf("t43", "t45", "t47")[(hash(x, y) and 0x7fffffff) % 3], sx, sy, ts)
        if (!ok) { pTile.color = if (t == T.TILEFLOOR) 0xFFC8C2B4.toInt() else 0xFF8B6A43.toInt(); c.drawRect(sx, sy, e, b, pTile) }
    }

    private fun drawZombie(c: Canvas, z: Zombie, sx: Float, sy: Float, ts: Float) {
        val a = Math.toDegrees(atan2(z.fy, z.fx).toDouble()).toFloat()
        val sway = kotlin.math.sin(z.anim * 5f) * 7f
        c.save(); c.translate(sx, sy); c.rotate(a + sway)
        val s = ts * 0.68f / 43f
        pFill.color = 0x40000000; c.drawCircle(ts * 0.05f, ts * 0.07f, ts * 0.3f, pFill)
        if (!spr.pivot(c, "z_hold", 17f, 21.5f, s)) {
            pFill.color = 0xFF5A6A48.toInt(); c.drawCircle(0f, 0f, ts * 0.28f, pFill)
        }
        if (z.chase > 0f) {
            pFill.color = 0xFFFF2020.toInt()
            c.drawCircle(ts * 0.13f, -ts * 0.05f, ts * 0.025f, pFill); c.drawCircle(ts * 0.13f, ts * 0.05f, ts * 0.025f, pFill)
        }
        c.restore()
    }

    private fun drawPlayer(c: Canvas, p: Player, sx: Float, sy: Float, ts: Float) {
        val a = Math.toDegrees(atan2(p.fy, p.fx).toDouble()).toFloat()
        c.save(); c.translate(sx, sy); c.rotate(a)
        val s = ts * 0.68f / 43f
        pFill.color = 0x40000000; c.drawCircle(ts * 0.05f, ts * 0.07f, ts * 0.3f, pFill)
        val w = p.weapon()
        val who = if (p.id == 0) "p0" else "p1"
        if (w.kind == ItemKind.GUN) {
            if (!spr.pivot(c, "${who}_gun", 17f, 21.5f, s)) { pFill.color = 0xFF3A6EA5.toInt(); c.drawCircle(0f, 0f, ts * 0.28f, pFill) }
            if (p.attackAnim > 0.6f) { pFill.color = 0xCCFFE070.toInt(); c.drawCircle(37f * s, 14f * s, ts * 0.1f, pFill) }
        } else {
            // оружие ближнего боя в правой руке с замахом
            if (w.id != "fists") {
                c.save()
                c.translate(15f * s, 14f * s)
                c.rotate(-60f * p.attackAnim + 20f * (1f - p.attackAnim) - 10f)
                pLine.color = 0xFF3A2A1A.toInt(); pLine.strokeWidth = ts * (if (w.id == "knife") 0.07f else 0.11f)
                val len = ts * (0.2f + w.range * 0.28f)
                c.drawLine(0f, 0f, len, 0f, pLine)
                pLine.color = w.color; pLine.strokeWidth = ts * (if (w.id == "knife") 0.045f else 0.08f)
                c.drawLine(ts * 0.03f, 0f, len - ts * 0.02f, 0f, pLine)
                c.restore()
            }
            if (!spr.pivot(c, "${who}_hold", 17f, 21.5f, s)) { pFill.color = 0xFF3A6EA5.toInt(); c.drawCircle(0f, 0f, ts * 0.28f, pFill) }
        }
        c.restore()
    }

    private fun bar(c: Canvas, x: Float, y: Float, v: Float, col: Int, label: String) {
        val bw = 110 * dp; val bh = 9 * dp
        pFill.color = 0x88000000.toInt(); rect.set(x, y, x + bw, y + bh); c.drawRoundRect(rect, bh / 2, bh / 2, pFill)
        pFill.color = col; rect.set(x, y, x + bw * (v / 100f).coerceIn(0f, 1f), y + bh); c.drawRoundRect(rect, bh / 2, bh / 2, pFill)
        pText.textSize = 11 * dp; c.drawText(label, x + bw + 6 * dp, y + bh, pText)
    }

    private fun drawHud(c: Canvas, gm: Game, me: Player, target: Int) {
        layoutButtons()
        val x = 14 * dp; var y = 14 * dp
        bar(c, x, y, me.hp, 0xFFD04040.toInt(), "Здоровье"); y += 16 * dp
        bar(c, x, y, 100 - me.hunger, 0xFFE09030.toInt(), "Сытость"); y += 16 * dp
        bar(c, x, y, 100 - me.thirst, 0xFF4090E0.toInt(), "Вода"); y += 16 * dp
        bar(c, x, y, me.stamina, 0xFFE0D040.toInt(), "Силы"); y += 22 * dp
        pText.textSize = 13 * dp
        val status = ArrayList<Pair<String, Int>>()
        if (me.bleeding) status.add("Кровотечение — открой «Тело»" to 0xFFFF6060.toInt())
        if (me.infection > 420f) status.add("Лихорадка" to 0xFF90E060.toInt())
        if (me.weight() > Game.MAX_WEIGHT) status.add("Перегруз" to 0xFFFFC040.toInt())
        if (me.hunger > 70f) status.add("Голод" to 0xFFFFA040.toInt())
        if (me.thirst > 70f) status.add("Жажда" to 0xFF70B0FF.toInt())
        if (me.body.woundInfected) status.add("Рана воспалилась" to 0xFFC080FF.toInt())
        if (me.body.parts.any { it.hasGlass }) status.add("Стекло в ране" to 0xFF9FD8F0.toInt())
        if (me.body.parts.any { it.dirty && it.wounds.isNotEmpty() }) status.add("Грязная повязка" to 0xFFC0A060.toInt())
        for ((s, col) in status) { pText.color = col; c.drawText(s, x, y, pText); y += 17 * dp }
        pText.color = Color.WHITE

        // часы
        pText.textAlign = Paint.Align.CENTER; pText.textSize = 15 * dp
        c.drawText("День ${gm.day()}  ${gm.clock()}", W / 2, 24 * dp, pText)
        pText.textSize = 13 * dp
        var my = 48 * dp
        for ((m, ttl) in msgs) { pText.alpha = (min(1f, ttl) * 255).toInt(); c.drawText(m, W / 2, my, pText); my += 18 * dp }
        pText.alpha = 255
        if (mode == Mode.HOST && !initSent) {
            pText.textSize = 12 * dp; pText.color = 0xFFB0D0FF.toInt()
            c.drawText("Ждём друга. Твой IP: $hostIp", W / 2, H - 14 * dp, pText)
            pText.color = Color.WHITE
        }
        pText.textAlign = Paint.Align.LEFT

        // джойстик
        if (joyPid >= 0) {
            pFill.color = 0x33FFFFFF; c.drawCircle(joyBX, joyBY, 60 * dp, pFill)
            val dx = joyX - joyBX; val dy = joyY - joyBY; val l = hypot(dx, dy); val k = if (l > 60 * dp) 60 * dp / l else 1f
            pFill.color = 0x88FFFFFF.toInt(); c.drawCircle(joyBX + dx * k, joyBY + dy * k, 26 * dp, pFill)
        } else {
            pText.textSize = 12 * dp; pText.color = 0x66FFFFFF
            c.drawText("Веди пальцем слева, чтобы идти", 14 * dp, H - 16 * dp, pText); pText.color = Color.WHITE
        }
        val w = me.weapon()
        val wl = if (w.kind == ItemKind.GUN) "${w.name} (${me.count("ammo")})" else w.name
        roundBtn(c, bAttack, 0xAAB03030.toInt(), "Удар", wl)
        val hint = when {
            target < 0 -> "Под ногами"
            gm.world.barrAt(target) > 0 -> "Заколочено"
            gm.world.tAt(target) == T.DOOR -> "Открыть"
            gm.world.tAt(target) == T.DOOR_OPEN -> "Закрыть"
            gm.world.tAt(target) == T.WINDOW -> "Разбить окно"
            else -> "Обыскать"
        }
        roundBtn(c, bInteract, 0xAA306090.toInt(), "Действ.", hint)
        roundBtn(c, bRun, if (runToggle) 0xCCD0A020.toInt() else 0x77444444, "Бег", null)
        if (barrCell >= 0) roundBtn(c, bBarr, 0xAA8A6030.toInt(), "Забить", null)
        roundBtn(c, bInv, 0x99444444.toInt(), "Вещи", null)
        val hurt = me.body.parts.any { !it.ok }
        roundBtn(c, bBody, if (me.bleeding) 0xCCB02020.toInt() else if (hurt) 0xAA8A6A20.toInt() else 0x99444444.toInt(), "Тело", null)
        roundBtn(c, bPause, 0x99444444.toInt(), "II", null)
    }

    private fun roundBtn(c: Canvas, b: Btn, col: Int, label: String, sub: String?) {
        pFill.color = col; c.drawCircle(b.x, b.y, b.r, pFill)
        pLine.color = 0x66FFFFFF; pLine.strokeWidth = 1.5f * dp; c.drawCircle(b.x, b.y, b.r, pLine)
        pText.textAlign = Paint.Align.CENTER; pText.textSize = (if (b.r > 40 * dp) 16 else 12) * dp
        c.drawText(label, b.x, b.y + pText.textSize * 0.35f, pText)
        if (sub != null) { pText.textSize = 11 * dp; c.drawText(sub, b.x, b.y - b.r - 6 * dp, pText) }
        pText.textAlign = Paint.Align.LEFT
    }

    private fun panelBg(c: Canvas, title: String) {
        layoutPanel()
        pFill.color = 0x99000000.toInt(); c.drawRect(0f, 0f, W, H, pFill)
        pFill.color = 0xF0202326.toInt(); c.drawRoundRect(panelRect, 14 * dp, 14 * dp, pFill)
        pText.textSize = 17 * dp; c.drawText(title, panelRect.left + 16 * dp, panelRect.top + 32 * dp, pText)
        pFill.color = 0xFF505458.toInt(); c.drawRoundRect(btnClose, 8 * dp, 8 * dp, pFill)
        pText.textAlign = Paint.Align.CENTER; c.drawText("✕", btnClose.centerX(), btnClose.centerY() + 6 * dp, pText); pText.textAlign = Paint.Align.LEFT
    }

    private fun itemList(c: Canvas, col: RectF, items: List<Stack>, scr: Int, highlight: String?, emptyText: String) {
        c.save(); c.clipRect(col)
        pText.textSize = 14 * dp
        if (items.isEmpty()) { pText.color = 0xFF909090.toInt(); c.drawText(emptyText, col.left + 8 * dp, col.top + 24 * dp, pText); pText.color = Color.WHITE }
        for ((i, s) in items.withIndex()) {
            val top = col.top + i * rowH - scroll[scr]
            if (top > col.bottom || top + rowH < col.top) continue
            rect.set(col.left, top + 2 * dp, col.right, top + rowH - 2 * dp)
            pFill.color = if (s.id == highlight) 0xFF3C5A78.toInt() else 0xFF2E3236.toInt(); c.drawRoundRect(rect, 8 * dp, 8 * dp, pFill)
            Icons.draw(c, s.id, col.left + 18 * dp, top + rowH / 2, 32 * dp)
            c.drawText(s.def.name + if (s.n > 1) " ×${s.n}" else "", col.left + 36 * dp, top + rowH / 2 + 5 * dp, pText)
        }
        c.restore()
    }

    private fun onTileEvent(w: World, i: Int, from: Int, to: Int) {
        val cx = i % w.w + 0.5f; val cy = i / w.w + 0.5f
        if ((from == T.DOOR && to == T.DOOR_OPEN) || (from == T.DOOR_OPEN && to == T.DOOR)) {
            doorAngle[i] = doorAngle[i] ?: (if (from == T.DOOR_OPEN) 90f else 0f)
        }
        fun burst(n: Int, col: Int, size: Float, speed: Float, life: Float) {
            repeat(n) {
                val a = prnd.nextFloat() * 6.283f; val sp = speed * (0.3f + prnd.nextFloat())
                particles.add(Particle(cx + (prnd.nextFloat() - 0.5f) * 0.6f, cy + (prnd.nextFloat() - 0.5f) * 0.6f,
                    kotlin.math.cos(a) * sp, kotlin.math.sin(a) * sp, life * (0.6f + prnd.nextFloat() * 0.6f), life * 1.2f,
                    col, size * (0.5f + prnd.nextFloat()), prnd.nextFloat() * 360f, (prnd.nextFloat() - 0.5f) * 900f))
            }
        }
        if (from == T.WINDOW && to == T.WINDOW_BROKEN) {
            burst(22, 0xFFBFE8F8.toInt(), 0.07f, 3.2f, 1.6f)
            burst(8, 0xFFFFFFFF.toInt(), 0.04f, 4f, 0.9f)
        }
        if (T.isDoorLike(from) && to == T.DOOR_BROKEN) {
            burst(16, 0xFFA97A48.toInt(), 0.09f, 2.6f, 1.8f)
            burst(8, 0xFF6E4A2A.toInt(), 0.06f, 3f, 1.4f)
        }
        if (particles.size > 400) particles.subList(0, particles.size - 400).clear()
    }

    private fun mostHurt(me: Player): Int {
        var best = selPart; var score = -1
        for (i in 0 until BP.COUNT) {
            val p = me.body.parts[i]
            val sc = (if (p.bleeding) 100 else 0) + (if (p.hasGlass) 50 else 0) + (if (p.infected) 40 else 0) + p.wounds.size * 10 + (if (p.bruise > 0f) 1 else 0)
            if (sc > score && sc > 0) { score = sc; best = i }
        }
        return best
    }

    // ---------- экран «Тело» ----------
    private val rectTmp = RectF()
    private val partBoxes = arrayOf(
        floatArrayOf(84f, 6f, 116f, 44f),   // голова
        floatArrayOf(92f, 46f, 108f, 56f),  // шея
        floatArrayOf(68f, 58f, 132f, 128f), // торс
        floatArrayOf(72f, 130f, 128f, 154f),// пах
        floatArrayOf(46f, 60f, 66f, 104f),  // л плечо
        floatArrayOf(38f, 106f, 60f, 148f), // л предплечье
        floatArrayOf(32f, 150f, 56f, 174f), // л кисть
        floatArrayOf(134f, 60f, 154f, 104f),
        floatArrayOf(140f, 106f, 162f, 148f),
        floatArrayOf(144f, 150f, 168f, 174f),
        floatArrayOf(74f, 156f, 98f, 210f), // л бедро
        floatArrayOf(76f, 212f, 96f, 262f), // л голень
        floatArrayOf(70f, 264f, 98f, 282f), // л стопа
        floatArrayOf(102f, 156f, 126f, 210f),
        floatArrayOf(104f, 212f, 124f, 262f),
        floatArrayOf(102f, 264f, 130f, 282f),
    )

    private fun bodyBox(): RectF {
        layoutPanel()
        val top = panelRect.top + 52 * dp; val bottom = panelRect.bottom - 14 * dp
        val h = bottom - top; val w = h * 200f / 290f
        val l = panelRect.left + 14 * dp
        return RectF(l, top, l + w, bottom)
    }

    private fun partRect(box: RectF, i: Int) {
        val k = box.height() / 290f; val b = partBoxes[i]
        rectTmp.set(box.left + b[0] * k, box.top + b[1] * k, box.left + b[2] * k, box.top + b[3] * k)
    }

    private fun partColor(p: BodyPart, pulse: Float): Int = when {
        p.bleeding -> if (pulse > 0.5f) 0xFFE02828.toInt() else 0xFFA01818.toInt()
        p.infected -> 0xFF9A50C8.toInt()
        p.hasGlass -> 0xFF5AB8D8.toInt()
        p.wounds.isNotEmpty() && p.bandage != 0 -> if (p.dirty) 0xFFB0985A.toInt() else 0xFFE8E8E8.toInt()
        p.wounds.isNotEmpty() -> 0xFFE08A3A.toInt()
        p.bruise > 0f -> 0xFFC8B040.toInt()
        else -> 0xFF4E8A4E.toInt()
    }

    private fun drawBody(c: Canvas, gm: Game, me: Player) {
        panelBg(c, "Тело  —  здоровье ${me.hp.toInt()}%")
        val box = bodyBox()
        val pulse = ((System.currentTimeMillis() % 800) / 800f)
        for (i in 0 until BP.COUNT) {
            partRect(box, i)
            val p = me.body.parts[i]
            pFill.color = partColor(p, pulse)
            val rad = if (i == BP.HEAD) rectTmp.width() / 2 else 6 * dp
            c.drawRoundRect(rectTmp, rad, rad, pFill)
            if (p.bandage != 0) {
                pLine.color = if (p.dirty) 0xFF8A7040.toInt() else 0xFFFFFFFF.toInt(); pLine.strokeWidth = 2 * dp
                for (k in 1..2) { val yy = rectTmp.top + rectTmp.height() * k / 3f; c.drawLine(rectTmp.left, yy, rectTmp.right, yy, pLine) }
            }
            if (i == selPart) { pLine.color = 0xFFFFFFFF.toInt(); pLine.strokeWidth = 2.5f * dp; c.drawRoundRect(rectTmp, rad, rad, pLine) }
        }
        // легенда
        val lx = box.right + 6 * dp
        var ly = box.bottom - 6 * dp
        pText.textSize = 10 * dp
        for ((col, txt) in listOf(0xFF4E8A4E.toInt() to "цело", 0xFFE08A3A.toInt() to "рана", 0xFFE02828.toInt() to "кровь",
            0xFF5AB8D8.toInt() to "стекло", 0xFF9A50C8.toInt() to "воспал.", 0xFFE8E8E8.toInt() to "повязка").reversed()) {
            pFill.color = col; c.drawCircle(lx + 5 * dp, ly - 4 * dp, 4 * dp, pFill)
            c.drawText(txt, lx + 13 * dp, ly, pText); ly -= 14 * dp
        }

        // правая колонка — выбранная часть
        val rx = box.right + 70 * dp; var ry = panelRect.top + 70 * dp
        val rw = panelRect.right - rx - 16 * dp
        val p = me.body.parts[selPart]
        pText.textSize = 17 * dp; c.drawText(BP.names[selPart], rx, ry, pText); ry += 24 * dp
        pText.textSize = 13 * dp
        if (p.ok) { pText.color = 0xFF90C090.toInt(); c.drawText("Без повреждений", rx, ry, pText); ry += 20 * dp }
        if (p.bruise > 0f) { pText.color = 0xFFD8C060.toInt(); c.drawText("Ушиб — пройдёт сам", rx, ry, pText); ry += 18 * dp }
        for (wd in p.wounds) {
            val heal = (100 * (1f - wd.heal / WT.healTime[wd.type])).toInt().coerceIn(0, 100)
            val parts = ArrayList<String>()
            if (wd.glass) parts.add("стекло внутри")
            if (wd.bleeding && p.bandage == 0) parts.add("кровоточит")
            if (wd.infected) parts.add("воспаление") else if (wd.disinfected) parts.add("обработана")
            parts.add("заживает $heal%")
            pText.color = when { wd.bleeding && p.bandage == 0 -> 0xFFFF7070.toInt(); wd.infected -> 0xFFC090F0.toInt(); else -> 0xFFE0E0E0.toInt() }
            c.drawText("${WT.names[wd.type]}: ${parts.joinToString(", ")}", rx, ry, pText); ry += 18 * dp
        }
        if (p.bandage != 0) {
            pText.color = if (p.dirty) 0xFFC0A060.toInt() else 0xFFE0E0E0.toInt()
            c.drawText("Повязка: ${if (p.bandage == 1) "бинт" else "тряпка"}${if (p.dirty) ", грязная — смени" else ""}", rx, ry, pText); ry += 18 * dp
        }
        pText.color = Color.WHITE
        // общее состояние
        val gen = ArrayList<String>()
        if (me.infection > 420f) gen.add("Лихорадка, слабость")
        if (me.body.legPenalty() < 0.95f) gen.add("Хромаешь (−${(100 * (1 - me.body.legPenalty())).toInt()}% скорости)")
        if (me.body.armPenalty() < 0.95f) gen.add("Слабый удар (−${(100 * (1 - me.body.armPenalty())).toInt()}% урона)")
        pText.textSize = 12 * dp; pText.color = 0xFFB0B0B0.toInt()
        if (gen.isNotEmpty()) c.drawText(gen.joinToString("  ·  "), rx, panelRect.top + 48 * dp, pText)
        pText.color = Color.WHITE

        // кнопки лечения
        treatBtns.clear()
        ry += 10 * dp
        val opts = ArrayList<Pair<String, String>>()
        if (p.hasGlass) opts.add((if (me.count("tweezers") > 0) "Достать стекло пинцетом" else "Нужен пинцет") to "glass")
        if (p.wounds.isNotEmpty() && (p.bandage == 0 || p.dirty)) {
            if (me.count("bandage") > 0) opts.add((if (p.bandage != 0) "Сменить на бинт" else "Перевязать бинтом") + " (${me.count("bandage")})" to "bandage")
            if (me.count("rag") > 0) opts.add((if (p.bandage != 0) "Сменить на тряпку" else "Перевязать тряпкой") + " (${me.count("rag")})" to "rag")
            if (me.count("bandage") == 0 && me.count("rag") == 0) opts.add("Нечем перевязать — найди бинт или тряпку" to "")
        }
        if (p.wounds.any { !it.disinfected }) opts.add((if (me.count("disinfectant") > 0) "Продезинфицировать" else "Нет дезинфицирующего") to (if (me.count("disinfectant") > 0) "disinfect" else ""))
        if (p.bandage != 0) opts.add("Снять повязку" to "unbandage")
        for ((label, what) in opts) {
            val r = RectF(rx, ry, rx + rw, ry + 38 * dp)
            pFill.color = if (what.isEmpty() || (what == "glass" && me.count("tweezers") == 0)) 0xFF3A3A3A.toInt() else 0xFF3C5A6A.toInt()
            c.drawRoundRect(r, 8 * dp, 8 * dp, pFill)
            pText.textSize = 13 * dp; c.drawText(label, r.left + 12 * dp, r.centerY() + 5 * dp, pText)
            if (what.isNotEmpty()) treatBtns.add(r to what)
            ry += 44 * dp
        }
    }

    private fun drawInv(c: Canvas, me: Player) {
        panelBg(c, "Вещи  —  вес ${"%.1f".format(me.weight())} / ${Game.MAX_WEIGHT.toInt()}")
        val col = colRect(0, 1)
        val maxScroll = max(0f, me.inv.size * rowH - col.height()); scroll[0] = min(scroll[0], maxScroll)
        itemList(c, col, me.inv, 0, selected, "Пусто")
        val sel = selected?.takeIf { me.count(it) > 0 }
        val rx = col.right + 20 * dp; var ry = col.top + 20 * dp
        val bw = panelRect.right - rx - 16 * dp
        if (sel == null) { pText.textSize = 13 * dp; pText.color = 0xFF909090.toInt(); c.drawText("Выбери предмет", rx, ry, pText); pText.color = Color.WHITE; btnA.setEmpty(); btnB.setEmpty(); return }
        val d = Items.get(sel)
        pText.textSize = 16 * dp; c.drawText(d.name, rx, ry, pText); ry += 24 * dp
        pText.textSize = 13 * dp; pText.color = 0xFFB0B0B0.toInt()
        val info = when (d.kind) {
            ItemKind.FOOD, ItemKind.DRINK -> "Голод −${d.hunger.toInt()}  Жажда −${d.thirst.toInt()}"
            ItemKind.MED -> when (d.id) {
                "bandage" -> "Перевязка ран. Лучше — через «Тело»"
                "rag" -> "Перевязка похуже, быстро пачкается"
                "disinfectant" -> "Защищает рану от воспаления"
                "tweezers" -> "Достать стекло из раны (многоразовый)"
                else -> "+${d.heal.toInt()} HP"
            }
            ItemKind.MELEE -> "Урон ${d.dmg.toInt()}, дальность ${"%.1f".format(d.range)}"
            ItemKind.GUN -> "Урон ${d.dmg.toInt()}, громко! Нужны патроны"
            ItemKind.AMMO -> "Для пистолета"
            ItemKind.MATERIAL -> "Нужен молоток, чтобы заколотить окно или дверь"
        }
        c.drawText(info, rx, ry, pText); ry += 18 * dp
        c.drawText("Вес ${d.weight}", rx, ry, pText); ry += 26 * dp
        pText.color = Color.WHITE
        val useLabel = when (d.kind) {
            ItemKind.FOOD -> "Съесть"; ItemKind.DRINK -> "Выпить"; ItemKind.MED -> "Применить"
            ItemKind.MELEE, ItemKind.GUN -> if (me.eq == sel) "Убрать из рук" else "Взять в руки"
            else -> null
        }
        if (useLabel != null) {
            btnA.set(rx, ry, rx + bw, ry + 44 * dp); pFill.color = 0xFF3C6A3C.toInt(); c.drawRoundRect(btnA, 10 * dp, 10 * dp, pFill)
            pText.textAlign = Paint.Align.CENTER; pText.textSize = 15 * dp; c.drawText(useLabel, btnA.centerX(), btnA.centerY() + 5 * dp, pText); pText.textAlign = Paint.Align.LEFT
            ry += 54 * dp
        } else btnA.setEmpty()
        btnB.set(rx, ry, rx + bw, ry + 44 * dp); pFill.color = 0xFF6A3C3C.toInt(); c.drawRoundRect(btnB, 10 * dp, 10 * dp, pFill)
        pText.textAlign = Paint.Align.CENTER; pText.textSize = 15 * dp; c.drawText("Выбросить", btnB.centerX(), btnB.centerY() + 5 * dp, pText); pText.textAlign = Paint.Align.LEFT
    }

    private fun drawLoot(c: Canvas, gm: Game, me: Player) {
        val t = gm.world.tAt(lootCell)
        val name = when (t) {
            T.FRIDGE -> "Холодильник"; T.COUNTER -> "Кухонный шкаф"; T.WARDROBE -> "Гардероб"; T.SHELF -> "Полка"
            T.MEDCAB -> "Аптечка"; T.LOCKER -> "Оружейный шкаф"; T.STORESHELF -> "Стеллаж"; else -> "На земле"
        }
        panelBg(c, "$name  ⇄  Инвентарь")
        val left = colRect(0, 2); val right = colRect(1, 2)
        val cont = gm.world.containers[lootCell]
        pText.textSize = 11 * dp; pText.color = 0xFF909090.toInt()
        c.drawText("Нажми — взять", left.left, left.top - 6 * dp, pText)
        c.drawText("Нажми — положить сюда", right.left, right.top - 6 * dp, pText)
        pText.color = Color.WHITE
        val list = cont ?: emptyList()
        scroll[0] = min(scroll[0], max(0f, list.size * rowH - left.height()))
        scroll[1] = min(scroll[1], max(0f, me.inv.size * rowH - right.height()))
        itemList(c, left, list, 0, null, if (cont == null && T.isFurniture(t)) "Обыскиваю…" else "Пусто")
        itemList(c, right, me.inv, 1, null, "Пусто")
    }

    private fun drawPause(c: Canvas) {
        panelBg(c, if (mode == Mode.SOLO) "Пауза" else "Меню")
        val bw = min(panelRect.width() * 0.6f, 360 * dp)
        val cx = panelRect.centerX(); var y = panelRect.top + 80 * dp
        btnA.set(cx - bw / 2, y, cx + bw / 2, y + 50 * dp); y += 66 * dp
        btnB.set(cx - bw / 2, y, cx + bw / 2, y + 50 * dp)
        pFill.color = 0xFF3C6A3C.toInt(); c.drawRoundRect(btnA, 10 * dp, 10 * dp, pFill)
        pFill.color = 0xFF6A3C3C.toInt(); c.drawRoundRect(btnB, 10 * dp, 10 * dp, pFill)
        pText.textAlign = Paint.Align.CENTER; pText.textSize = 16 * dp
        c.drawText("Продолжить", btnA.centerX(), btnA.centerY() + 6 * dp, pText)
        c.drawText(if (mode == Mode.CLIENT) "Отключиться" else "Сохранить и выйти", btnB.centerX(), btnB.centerY() + 6 * dp, pText)
        pText.textSize = 12 * dp; pText.color = 0xFF909090.toInt()
        c.drawText("Игра сохраняется сама каждую минуту", cx, y + 80 * dp, pText)
        pText.color = Color.WHITE; pText.textAlign = Paint.Align.LEFT
    }

    private fun drawDead(c: Canvas, gm: Game, me: Player) {
        pFill.color = 0xBB300000.toInt(); c.drawRect(0f, 0f, W, H, pFill)
        pText.textAlign = Paint.Align.CENTER
        pText.textSize = 34 * dp; c.drawText("Вот так ты и умер", W / 2, H * 0.36f, pText)
        pText.textSize = 15 * dp
        c.drawText("Дней прожито: ${gm.day() - 1}   Убито зомби: ${me.kills}", W / 2, H * 0.36f + 36 * dp, pText)
        val others = gm.players.any { !it.dead && it.id != me.id && it.connected }
        if (others) c.drawText("Напарник ещё жив — можешь наблюдать", W / 2, H * 0.36f + 60 * dp, pText)
        deadBtn.set(W / 2 - 110 * dp, H * 0.62f, W / 2 + 110 * dp, H * 0.62f + 50 * dp)
        pFill.color = 0xFF505458.toInt(); c.drawRoundRect(deadBtn, 10 * dp, 10 * dp, pFill)
        c.drawText("В меню", deadBtn.centerX(), deadBtn.centerY() + 6 * dp, pText)
        pText.textAlign = Paint.Align.LEFT
    }
}
