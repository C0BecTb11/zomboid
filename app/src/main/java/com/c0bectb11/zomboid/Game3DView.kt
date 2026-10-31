package com.c0bectb11.zomboid

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** OpenGL-поверхность: гоняет логику игры и рисует 3D-мир. Интерфейс — прозрачный GameView сверху. */
@SuppressLint("ViewConstructor")
class Game3DView(ctx: Context, private val hud: GameView) : GLSurfaceView(ctx), GLSurfaceView.Renderer {
    private var last = 0L

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        preserveEGLContextOnPause = true
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        hud.scene.onCreated()
        last = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        hud.scene.onSize(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = ((now - last) / 1e9f).coerceIn(0f, 0.05f)
        last = now
        hud.frame(dt)
        if (hud.hasWorld()) {
            try { hud.renderGL() } catch (e: Exception) { android.util.Log.e("zomboid", "gl", e) }
        } else {
            GLES30.glClearColor(0.06f, 0.07f, 0.08f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        }
    }
}
