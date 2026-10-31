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
import kotlin.math.cos
import kotlin.math.sin
import android.graphics.Path
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
    private val wr = WorldRenderer(ctx, resources.displayMetrics.density)
    private val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
    private var lootWait = 0f
    private var insetR = 0f

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
        // анимации, свет, камера
        if (me != null) wr.update(dt, gm, me, localPid)
        if (wr.vibrate > 0) {
            val ms = wr.vibrate.toLong(); wr.vibrate = 0
            try {
                if (Build.VERSION.SDK_INT >= 26) vib?.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                else @Suppress("DEPRECATION") vib?.vibrate(ms)
            } catch (_: Exception) {}
        }
        // обыск прерван — закрыть пустую панель
        if (panel == Panel.LOOT && me != null && gm.world.containers[lootCell] == null && T.isContainer(gm.world.tAt(lootCell))) {
            if (me.taAct == null) { lootWait += dt; if (lootWait > 0.4f) panel = Panel.NONE } else lootWait = 0f
        } else lootWait = 0f

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
        val W = this.W - insetR
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
        if (Build.VERSION.SDK_INT >= 28) {
            val cut = rootWindowInsets?.displayCutout
            hudL = 14 * dp + (cut?.safeInsetLeft ?: 0)
            insetR = (cut?.safeInsetRight ?: 0).toFloat()
        } else hudL = 14 * dp
        val target = gm.findInteract(me)
        wr.draw(c, gm, me, W, H, target)
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

    // ================== ИНТЕРФЕЙС ==================
    private val glyph = Path()

    /** Простые белые пиктограммы для мудлов и кнопок */
    private fun drawGlyph(c: Canvas, kind: String, cx: Float, cy: Float, r: Float, col: Int = Color.WHITE) {
        pLine.color = col; pLine.strokeWidth = r * 0.16f; pLine.style = Paint.Style.STROKE
        pFill.color = col
        glyph.reset()
        when (kind) {
            "hunger" -> { // вилка и нож
                c.drawLine(cx - r * 0.35f, cy - r * 0.6f, cx - r * 0.35f, cy + r * 0.6f, pLine)
                c.drawLine(cx - r * 0.55f, cy - r * 0.6f, cx - r * 0.55f, cy - r * 0.15f, pLine)
                c.drawLine(cx - r * 0.15f, cy - r * 0.6f, cx - r * 0.15f, cy - r * 0.15f, pLine)
                glyph.moveTo(cx + r * 0.35f, cy + r * 0.6f); glyph.lineTo(cx + r * 0.35f, cy - r * 0.6f); glyph.quadTo(cx + r * 0.7f, cy - r * 0.2f, cx + r * 0.35f, cy + r * 0.05f)
                c.drawPath(glyph, pLine)
            }
            "drop", "bleed" -> {
                glyph.moveTo(cx, cy - r * 0.7f); glyph.cubicTo(cx + r * 0.6f, cy, cx + r * 0.55f, cy + r * 0.65f, cx, cy + r * 0.65f)
                glyph.cubicTo(cx - r * 0.55f, cy + r * 0.65f, cx - r * 0.6f, cy, cx, cy - r * 0.7f); glyph.close()
                c.drawPath(glyph, pFill)
                if (kind == "bleed") { c.drawCircle(cx + r * 0.55f, cy + r * 0.5f, r * 0.12f, pFill); c.drawCircle(cx - r * 0.6f, cy + r * 0.35f, r * 0.09f, pFill) }
            }
            "fever" -> {
                c.drawLine(cx, cy - r * 0.6f, cx, cy + r * 0.2f, pLine)
                c.drawCircle(cx, cy + r * 0.38f, r * 0.24f, pFill)
                c.drawLine(cx + r * 0.25f, cy - r * 0.4f, cx + r * 0.45f, cy - r * 0.4f, pLine)
                c.drawLine(cx + r * 0.25f, cy - r * 0.1f, cx + r * 0.45f, cy - r * 0.1f, pLine)
            }
            "wound" -> {
                pLine.strokeWidth = r * 0.3f; c.drawLine(cx - r * 0.5f, cy + r * 0.5f, cx + r * 0.5f, cy - r * 0.5f, pLine)
                pFill.color = 0xFFD04040.toInt(); c.drawCircle(cx, cy, r * 0.14f, pFill)
            }
            "glass" -> { glyph.moveTo(cx - r * 0.2f, cy - r * 0.65f); glyph.lineTo(cx + r * 0.45f, cy + r * 0.5f); glyph.lineTo(cx - r * 0.45f, cy + r * 0.35f); glyph.close(); c.drawPath(glyph, pFill) }
            "weight" -> {
                c.drawCircle(cx, cy - r * 0.42f, r * 0.2f, pLine)
                glyph.moveTo(cx - r * 0.35f, cy - r * 0.2f); glyph.lineTo(cx + r * 0.35f, cy - r * 0.2f); glyph.lineTo(cx + r * 0.55f, cy + r * 0.6f); glyph.lineTo(cx - r * 0.55f, cy + r * 0.6f); glyph.close()
                c.drawPath(glyph, pFill)
            }
            "tired" -> { glyph.moveTo(cx + r * 0.15f, cy - r * 0.7f); glyph.lineTo(cx - r * 0.4f, cy + r * 0.1f); glyph.lineTo(cx, cy + r * 0.1f); glyph.lineTo(cx - r * 0.15f, cy + r * 0.7f); glyph.lineTo(cx + r * 0.4f, cy - r * 0.1f); glyph.lineTo(cx, cy - r * 0.1f); glyph.close(); c.drawPath(glyph, pFill) }
            "eye" -> {
                glyph.moveTo(cx - r * 0.65f, cy); glyph.quadTo(cx, cy - r * 0.6f, cx + r * 0.65f, cy); glyph.quadTo(cx, cy + r * 0.6f, cx - r * 0.65f, cy); glyph.close()
                c.drawPath(glyph, pLine); c.drawCircle(cx, cy, r * 0.18f, pFill)
            }
            "panic" -> { pLine.strokeWidth = r * 0.24f; c.drawLine(cx, cy - r * 0.6f, cx, cy + r * 0.15f, pLine); c.drawCircle(cx, cy + r * 0.5f, r * 0.13f, pFill) }
            "hand" -> {
                rect.set(cx - r * 0.42f, cy - r * 0.1f, cx + r * 0.42f, cy + r * 0.65f); c.drawRoundRect(rect, r * 0.2f, r * 0.2f, pFill)
                for (k in 0..3) { val fx = cx - r * 0.32f + k * r * 0.21f; rect.set(fx - r * 0.08f, cy - r * 0.6f + (if (k == 0 || k == 3) r * 0.15f else 0f), fx + r * 0.08f, cy); c.drawRoundRect(rect, r * 0.08f, r * 0.08f, pFill) }
            }
            "run" -> {
                c.drawCircle(cx + r * 0.15f, cy - r * 0.55f, r * 0.15f, pFill)
                glyph.moveTo(cx - r * 0.45f, cy - r * 0.15f); glyph.lineTo(cx - r * 0.05f, cy - r * 0.3f); glyph.lineTo(cx + r * 0.35f, cy - r * 0.05f)
                c.drawPath(glyph, pLine); glyph.reset()
                glyph.moveTo(cx - r * 0.5f, cy + r * 0.65f); glyph.lineTo(cx - r * 0.15f, cy + r * 0.25f); glyph.lineTo(cx - r * 0.05f, cy - r * 0.3f)
                glyph.moveTo(cx - r * 0.15f, cy + r * 0.25f); glyph.lineTo(cx + r * 0.25f, cy + r * 0.3f); glyph.lineTo(cx + r * 0.35f, cy + r * 0.7f)
                c.drawPath(glyph, pLine)
            }
            "bag" -> {
                rect.set(cx - r * 0.5f, cy - r * 0.3f, cx + r * 0.5f, cy + r * 0.65f); c.drawRoundRect(rect, r * 0.2f, r * 0.2f, pFill)
                rect.set(cx - r * 0.25f, cy - r * 0.65f, cx + r * 0.25f, cy - r * 0.15f); c.drawRoundRect(rect, r * 0.2f, r * 0.2f, pLine)
            }
            "body" -> {
                c.drawCircle(cx, cy - r * 0.5f, r * 0.2f, pFill)
                rect.set(cx - r * 0.25f, cy - r * 0.25f, cx + r * 0.25f, cy + r * 0.25f); c.drawRoundRect(rect, r * 0.1f, r * 0.1f, pFill)
                c.drawLine(cx - r * 0.38f, cy - r * 0.2f, cx - r * 0.45f, cy + r * 0.2f, pLine); c.drawLine(cx + r * 0.38f, cy - r * 0.2f, cx + r * 0.45f, cy + r * 0.2f, pLine)
                c.drawLine(cx - r * 0.13f, cy + r * 0.3f, cx - r * 0.15f, cy + r * 0.7f, pLine); c.drawLine(cx + r * 0.13f, cy + r * 0.3f, cx + r * 0.15f, cy + r * 0.7f, pLine)
            }
            "pause" -> { c.drawRect(cx - r * 0.32f, cy - r * 0.42f, cx - r * 0.1f, cy + r * 0.42f, pFill); c.drawRect(cx + r * 0.1f, cy - r * 0.42f, cx + r * 0.32f, cy + r * 0.42f, pFill) }
            "fist" -> {
                rect.set(cx - r * 0.45f, cy - r * 0.35f, cx + r * 0.45f, cy + r * 0.45f); c.drawRoundRect(rect, r * 0.25f, r * 0.25f, pFill)
                pLine.color = 0x66000000; pLine.strokeWidth = r * 0.06f
                for (k in 1..3) { val fx = cx - r * 0.45f + k * r * 0.225f; c.drawLine(fx, cy - r * 0.35f, fx, cy, pLine) }
            }
            "door" -> { rect.set(cx - r * 0.38f, cy - r * 0.65f, cx + r * 0.38f, cy + r * 0.65f); c.drawRect(rect, pLine); c.drawCircle(cx + r * 0.18f, cy, r * 0.08f, pFill) }
            "search" -> { c.drawCircle(cx - r * 0.12f, cy - r * 0.12f, r * 0.38f, pLine); pLine.strokeWidth = r * 0.22f; c.drawLine(cx + r * 0.16f, cy + r * 0.16f, cx + r * 0.55f, cy + r * 0.55f, pLine) }
            "sun" -> { c.drawCircle(cx, cy, r * 0.35f, pFill); for (k in 0 until 8) { val a = k * 0.785f; c.drawLine(cx + cos(a) * r * 0.5f, cy + sin(a) * r * 0.5f, cx + cos(a) * r * 0.72f, cy + sin(a) * r * 0.72f, pLine) } }
            "moon" -> { glyph.addCircle(cx, cy, r * 0.55f, Path.Direction.CW); val cut = Path(); cut.addCircle(cx + r * 0.3f, cy - r * 0.2f, r * 0.45f, Path.Direction.CW); glyph.op(cut, Path.Op.DIFFERENCE); c.drawPath(glyph, pFill) }
        }
    }

    private class Moodle(val glyph: String, val level: Int, val label: String)

    private fun moodles(gm: Game, me: Player): List<Moodle> {
        val m = ArrayList<Moodle>()
        fun lvl(v: Float, a: Float, b: Float, c: Float) = when { v >= c -> 3; v >= b -> 2; v >= a -> 1; else -> 0 }
        if (me.bleeding) m.add(Moodle("bleed", 3, "Кровь"))
        lvl(me.hunger, 45f, 65f, 85f).let { if (it > 0) m.add(Moodle("hunger", it, "Голод")) }
        lvl(me.thirst, 45f, 65f, 85f).let { if (it > 0) m.add(Moodle("drop", it, "Жажда")) }
        if (me.infection > 420f) m.add(Moodle("fever", if (me.infection > 900f) 3 else 2, "Жар"))
        if (me.body.woundInfected) m.add(Moodle("wound", 2, "Нагноение"))
        if (me.body.parts.any { it.hasGlass }) m.add(Moodle("glass", 2, "Стекло"))
        if (me.weight() > Game.MAX_WEIGHT) m.add(Moodle("weight", 2, "Тяжело"))
        if (me.stamina < 25f) m.add(Moodle("tired", if (me.stamina < 10f) 2 else 1, "Устал"))
        if (gm.zombies.any { it.chase > 0f && it.down <= 0f && hypot(it.rx - me.rx, it.ry - me.ry) < 6f }) m.add(Moodle("panic", 2, "Паника"))
        if (me.sneaking) m.add(Moodle("eye", 0, "Тихо"))
        return m
    }

    private var hudL = 14f

    private fun drawHud(c: Canvas, gm: Game, me: Player, target: Int) {
        layoutButtons()
        val x = hudL; val y = 12 * dp
        // карточка здоровья
        val cw = 190 * dp
        pFill.color = 0x9A0E1216.toInt(); rect.set(x, y, x + cw, y + 44 * dp); c.drawRoundRect(rect, 12 * dp, 12 * dp, pFill)
        // сердце
        pFill.color = 0xFFE04848.toInt()
        val hx = x + 20 * dp; val hy = y + 17 * dp; val hr = 7 * dp
        glyph.reset(); glyph.moveTo(hx, hy + hr); glyph.cubicTo(hx - hr * 1.6f, hy - hr * 0.1f, hx - hr * 0.6f, hy - hr * 1.4f, hx, hy - hr * 0.4f)
        glyph.cubicTo(hx + hr * 0.6f, hy - hr * 1.4f, hx + hr * 1.6f, hy - hr * 0.1f, hx, hy + hr); c.drawPath(glyph, pFill)
        val bx = x + 36 * dp; val bw = cw - 48 * dp
        pFill.color = 0x55FFFFFF; rect.set(bx, y + 12 * dp, bx + bw, y + 22 * dp); c.drawRoundRect(rect, 5 * dp, 5 * dp, pFill)
        pFill.color = if (me.hp < 30f) 0xFFE03030.toInt() else 0xFFD85A4A.toInt()
        rect.set(bx, y + 12 * dp, bx + bw * (me.hp / 100f).coerceIn(0f, 1f), y + 22 * dp); c.drawRoundRect(rect, 5 * dp, 5 * dp, pFill)
        pFill.color = 0x40FFFFFF; rect.set(bx, y + 29 * dp, bx + bw, y + 33 * dp); c.drawRoundRect(rect, 2 * dp, 2 * dp, pFill)
        pFill.color = 0xFFE8C850.toInt(); rect.set(bx, y + 29 * dp, bx + bw * (me.stamina / 100f).coerceIn(0f, 1f), y + 33 * dp); c.drawRoundRect(rect, 2 * dp, 2 * dp, pFill)
        // мудлы
        var mx = x + 4 * dp; val my = y + 70 * dp
        for (m in moodles(gm, me)) {
            val ring = when (m.level) { 0 -> 0xFF8A9096.toInt(); 1 -> 0xFFE8C850.toInt(); 2 -> 0xFFE88A30.toInt(); else -> 0xFFE03A3A.toInt() }
            pFill.color = 0xC0141820.toInt(); c.drawCircle(mx + 17 * dp, my, 17 * dp, pFill)
            pLine.color = ring; pLine.strokeWidth = 2.5f * dp; c.drawCircle(mx + 17 * dp, my, 16 * dp, pLine)
            drawGlyph(c, m.glyph, mx + 17 * dp, my, 12 * dp, if (m.glyph == "bleed") 0xFFFF5050.toInt() else Color.WHITE)
            pText.textAlign = Paint.Align.CENTER; pText.textSize = 9.5f * dp; pText.color = 0xDDFFFFFF.toInt()
            c.drawText(m.label, mx + 17 * dp, my + 29 * dp, pText)
            mx += 42 * dp
        }
        pText.color = Color.WHITE

        // часы
        val night = gm.darkness() > 0.5f
        val clock = "День ${gm.day()}  ·  ${gm.clock()}"
        pText.textAlign = Paint.Align.CENTER; pText.textSize = 14 * dp
        val tw = pText.measureText(clock) + 44 * dp
        pFill.color = 0x9A0E1216.toInt(); rect.set(W / 2 - tw / 2, 12 * dp, W / 2 + tw / 2, 42 * dp); c.drawRoundRect(rect, 15 * dp, 15 * dp, pFill)
        drawGlyph(c, if (night) "moon" else "sun", W / 2 - tw / 2 + 18 * dp, 27 * dp, 10 * dp, if (night) 0xFFB8C8F0.toInt() else 0xFFF0C850.toInt())
        c.drawText(clock, W / 2 + 10 * dp, 32 * dp, pText)
        // сообщения
        pText.textSize = 12.5f * dp
        var ty = 66 * dp
        for ((m, ttl) in msgs) {
            val a = min(1f, ttl)
            val mw = pText.measureText(m) + 24 * dp
            pFill.color = Color.argb((150 * a).toInt(), 14, 18, 22); rect.set(W / 2 - mw / 2, ty - 16 * dp, W / 2 + mw / 2, ty + 6 * dp); c.drawRoundRect(rect, 11 * dp, 11 * dp, pFill)
            pText.alpha = (a * 255).toInt(); c.drawText(m, W / 2, ty, pText); pText.alpha = 255
            ty += 26 * dp
        }
        if (mode == Mode.HOST && !initSent) {
            pText.textSize = 12 * dp; pText.color = 0xFFB0D0FF.toInt()
            c.drawText("Ждём друга. Твой IP: $hostIp", W / 2, H - 14 * dp, pText)
            pText.color = Color.WHITE
        }
        pText.textAlign = Paint.Align.LEFT

        // джойстик
        if (joyPid >= 0) {
            pFill.color = 0x30FFFFFF; c.drawCircle(joyBX, joyBY, 62 * dp, pFill)
            pLine.color = 0x55FFFFFF; pLine.strokeWidth = 2 * dp; c.drawCircle(joyBX, joyBY, 62 * dp, pLine)
            pLine.color = 0x30FFFFFF; c.drawCircle(joyBX, joyBY, 62 * dp * 0.6f, pLine)
            val dx = joyX - joyBX; val dy = joyY - joyBY; val l = hypot(dx, dy); val k = if (l > 62 * dp) 62 * dp / l else 1f
            pFill.color = 0xB0FFFFFF.toInt(); c.drawCircle(joyBX + dx * k, joyBY + dy * k, 24 * dp, pFill)
        } else {
            pText.textSize = 11 * dp; pText.color = 0x77FFFFFF
            c.drawText("Слева — ходьба. Чуть сдвинь — красться", hudL, H - 16 * dp, pText); pText.color = Color.WHITE
        }
        // кнопки
        val w = me.weapon()
        iconBtn(c, bAttack, 0xB0A83030.toInt(), null, if (w.id == "fists") null else w.id, if (w.id == "fists") "fist" else null,
            if (w.kind == ItemKind.GUN) "${me.count("ammo")}" else null, if (w.id == "fists") "Толчок" else w.name)
        val tt = if (target >= 0) gm.world.tAt(target) else -1
        val hint = when {
            target < 0 -> "Под ногами"
            gm.world.barrAt(target) > 0 -> "Заколочено"
            tt == T.DOOR -> "Открыть"
            tt == T.DOOR_OPEN -> "Закрыть"
            tt == T.WINDOW -> "Разбить окно"
            T.isCar(tt) -> "Багажник"
            else -> "Обыскать"
        }
        iconBtn(c, bInteract, 0xB0285A86.toInt(), if (tt == T.DOOR || tt == T.DOOR_OPEN) "door" else if (target >= 0 && tt != T.WINDOW) "search" else "hand", null, null, null, hint)
        iconBtn(c, bRun, if (runToggle) 0xD0C89A20.toInt() else 0x88303438.toInt(), "run", null, null, null, if (runToggle) "Бег" else null)
        if (barrCell >= 0) iconBtn(c, bBarr, 0xB08A6030.toInt(), null, "planks", null, "${me.count("planks")}", "Забить")
        iconBtn(c, bInv, 0x99303438.toInt(), "bag", null, null, null, null)
        val hurt = me.body.parts.any { !it.ok }
        iconBtn(c, bBody, if (me.bleeding) 0xD0B02020.toInt() else if (hurt) 0xB08A6A20.toInt() else 0x99303438.toInt(), "body", null, null, null, null)
        iconBtn(c, bPause, 0x99303438.toInt(), "pause", null, null, null, null)
    }

    private fun iconBtn(c: Canvas, b: Btn, col: Int, glyphName: String?, itemIcon: String?, glyph2: String?, badge: String?, label: String?) {
        pFill.color = 0x40000000; c.drawCircle(b.x + 2 * dp, b.y + 3 * dp, b.r, pFill)
        pFill.color = col; c.drawCircle(b.x, b.y, b.r, pFill)
        pLine.color = 0x50FFFFFF; pLine.strokeWidth = 1.5f * dp; c.drawCircle(b.x, b.y, b.r - 1 * dp, pLine)
        when {
            itemIcon != null -> Icons.draw(c, itemIcon, b.x, b.y, b.r * 1.35f)
            glyph2 != null -> drawGlyph(c, glyph2, b.x, b.y, b.r * 0.6f)
            glyphName != null -> drawGlyph(c, glyphName, b.x, b.y, b.r * 0.55f)
        }
        if (badge != null) {
            pText.textAlign = Paint.Align.CENTER; pText.textSize = 11 * dp
            val bw = pText.measureText(badge) + 10 * dp
            pFill.color = 0xFF1A1E22.toInt(); rect.set(b.x + b.r * 0.4f, b.y + b.r * 0.45f, b.x + b.r * 0.4f + bw, b.y + b.r * 0.45f + 17 * dp); c.drawRoundRect(rect, 8 * dp, 8 * dp, pFill)
            c.drawText(badge, rect.centerX(), rect.bottom - 4.5f * dp, pText)
        }
        if (label != null) {
            pText.textAlign = Paint.Align.CENTER; pText.textSize = 11 * dp
            pText.setShadowLayer(3 * dp, 0f, 1 * dp, Color.BLACK)
            c.drawText(label, b.x, b.y - b.r - 6 * dp, pText)
            pText.clearShadowLayer()
        }
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
