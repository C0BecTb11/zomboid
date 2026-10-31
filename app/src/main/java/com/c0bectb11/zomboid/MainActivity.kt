package com.c0bectb11.zomboid

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private var gameView: GameView? = null
    private var glView: Game3DView? = null

    private fun launch(v: GameView) {
        val gl = Game3DView(this, v)
        val root = android.widget.FrameLayout(this)
        root.addView(gl); root.addView(v)
        gameView = v; glView = gl
        setContentView(root)
    }
    private var net: Net? = null
    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        }
        val demo = intent?.getStringExtra("demo")
        if (demo != null) {
            val v = GameView(this, Mode.SOLO, Demo.create(demo), null) { del -> onExit(del) }
            v.demoPanel = demo
            launch(v)
            return
        }
        showMenu()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    private fun btn(text: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 17f
        isAllCaps = false
        background = GradientDrawable().apply { setColor(color); cornerRadius = 12 * dp }
        layoutParams = LinearLayout.LayoutParams((300 * dp).toInt(), (52 * dp).toInt()).apply { topMargin = (10 * dp).toInt() }
        setOnClickListener { onClick() }
    }

    private fun showMenu() {
        glView?.onPause(); glView = null
        gameView = null
        net?.close(); net = null
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, (24 * dp).toInt(), 0, (24 * dp).toInt())
        }
        col.addView(TextView(this).apply {
            text = "МЁРТВЫЙ ГОРОД"
            setTextColor(0xFFD04040.toInt()); textSize = 32f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        col.addView(TextView(this).apply {
            text = "Вот так ты и умрёшь. Вопрос — когда."
            setTextColor(0xFF909090.toInt()); textSize = 14f; gravity = Gravity.CENTER
            setPadding(0, 0, 0, (10 * dp).toInt())
        })
        val hasSave = Saves.exists(this) && Saves.load(this) != null
        if (hasSave) col.addView(btn("Продолжить", 0xFF2E6B3A.toInt()) { startSolo(Saves.load(this)) })
        col.addView(btn("Новая игра", 0xFF3A4A5A.toInt()) {
            if (hasSave) AlertDialog.Builder(this).setMessage("Начать заново? Текущий мир будет удалён.")
                .setPositiveButton("Да") { _, _ -> Saves.delete(this); startSolo(null) }
                .setNegativeButton("Нет", null).show()
            else startSolo(null)
        })
        col.addView(btn(if (hasSave) "Пригласить друга в свой мир (Wi‑Fi)" else "Создать игру на двоих (Wi‑Fi)", 0xFF5A4A2A.toInt()) { startHost() })
        val ip = EditText(this).apply {
            hint = "IP друга, например 192.168.0.12"
            setHintTextColor(0xFF707070.toInt()); setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_PHONE
            setText(getPreferences(MODE_PRIVATE).getString("ip", ""))
            layoutParams = LinearLayout.LayoutParams((300 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (14 * dp).toInt() }
        }
        col.addView(ip)
        col.addView(btn("Подключиться к другу", 0xFF2A4A6A.toInt()) {
            val a = ip.text.toString().trim()
            if (a.isEmpty()) { ip.error = "Введи IP"; return@btn }
            getPreferences(MODE_PRIVATE).edit().putString("ip", a).apply()
            startClient(a)
        })
        col.addView(TextView(this).apply {
            text = "Для игры вдвоём оба телефона должны быть в одной Wi‑Fi сети (или один раздаёт точку доступа). " +
                "Хост видит свой IP внизу экрана — второй игрок вводит его здесь."
            setTextColor(0xFF808080.toInt()); textSize = 12f; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams((340 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (14 * dp).toInt() }
        })
        val scroll = ScrollView(this).apply { setBackgroundColor(0xFF111316.toInt()); addView(col) }
        val root = LinearLayout(this).apply { gravity = Gravity.CENTER; setBackgroundColor(0xFF111316.toInt()); addView(scroll) }
        setContentView(root)
    }

    private fun onExit(deleteSave: Boolean) {
        if (deleteSave) Saves.delete(this)
        showMenu()
    }

    private fun startSolo(loaded: Game?) {
        val g = loaded ?: Game.newGame(System.currentTimeMillis())
        g.players.filter { it.id != 0 }.forEach { it.connected = false }
        val v = GameView(this, Mode.SOLO, g, null) { del -> onExit(del) }
        launch(v)
    }

    private fun startHost() {
        val g = Saves.load(this) ?: Game.newGame(System.currentTimeMillis())
        g.players.filter { it.id != 0 }.forEach { it.connected = false }
        val n = Net(); n.host(); net = n
        val v = GameView(this, Mode.HOST, g, n) { del -> onExit(del) }
        launch(v)
    }

    private fun startClient(ip: String) {
        val n = Net(); n.join(ip); net = n
        val v = GameView(this, Mode.CLIENT, null, n) { del -> onExit(del) }
        launch(v)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (gameView != null) {
            gameView?.saveNow()
            AlertDialog.Builder(this).setMessage("Выйти в меню? Игра сохранится.")
                .setPositiveButton("Выйти") { _, _ -> gameView?.stop(); showMenu() }
                .setNegativeButton("Остаться", null).show()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        glView?.onResume()
    }

    override fun onPause() {
        super.onPause()
        glView?.onPause()
        gameView?.saveNow()
    }

    override fun onDestroy() {
        super.onDestroy()
        net?.close()
    }
}
