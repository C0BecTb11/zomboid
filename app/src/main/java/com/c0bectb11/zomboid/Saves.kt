package com.c0bectb11.zomboid

import android.content.Context
import org.json.JSONObject
import java.io.File

object Saves {
    private fun file(ctx: Context) = File(ctx.filesDir, "save.json")

    fun exists(ctx: Context) = file(ctx).exists()

    fun save(ctx: Context, g: Game) {
        try {
            val tmp = File(ctx.filesDir, "save.tmp")
            tmp.writeText(g.toJson().toString())
            tmp.renameTo(file(ctx))
        } catch (e: Exception) {
            android.util.Log.e("zomboid", "save", e)
        }
    }

    fun load(ctx: Context): Game? = try {
        val o = JSONObject(file(ctx).readText())
        val g = Game(o.getLong("seed"))
        g.loadJson(o)
        if (g.players.isEmpty() || g.players.all { it.dead }) null else g
    } catch (e: Exception) {
        android.util.Log.e("zomboid", "load", e); null
    }

    fun delete(ctx: Context) { file(ctx).delete() }
}
