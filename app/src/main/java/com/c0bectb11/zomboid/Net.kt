package com.c0bectb11.zomboid

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/** Простая сеть на двоих: TCP, сообщения JSON с префиксом длины. */
class Net {
    val inbox = ConcurrentLinkedQueue<JSONObject>()
    @Volatile var connected = false
    @Volatile var status = ""
    @Volatile var closed = false
    private var server: ServerSocket? = null
    private var sock: Socket? = null
    private var out: DataOutputStream? = null
    private val writer = Executors.newSingleThreadExecutor()

    companion object {
        const val PORT = 7777

        fun localIps(): List<String> {
            val res = ArrayList<String>()
            try {
                for (ni in NetworkInterface.getNetworkInterfaces()) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (a in ni.inetAddresses) if (a is Inet4Address && !a.isLoopbackAddress) res.add(a.hostAddress ?: continue)
                }
            } catch (_: Exception) {}
            return res
        }
    }

    fun host() {
        status = "Ждём второго игрока…"
        Thread {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(PORT))
                server = s
                while (!closed) {
                    val c = s.accept()
                    if (connected) { c.close(); continue }
                    attach(c)
                }
            } catch (e: Exception) {
                if (!closed) status = "Ошибка сервера: ${e.message}"
            }
        }.apply { isDaemon = true; start() }
    }

    fun join(ip: String) {
        status = "Подключение к $ip…"
        Thread {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(ip, PORT), 6000)
                attach(s)
            } catch (e: Exception) {
                status = "Не удалось подключиться: ${e.message}"
            }
        }.apply { isDaemon = true; start() }
    }

    private fun attach(s: Socket) {
        s.tcpNoDelay = true
        sock = s
        out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 65536))
        connected = true
        status = "Подключено"
        Thread {
            try {
                val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 65536))
                while (!closed) {
                    val len = inp.readInt()
                    if (len <= 0 || len > 20_000_000) throw IllegalStateException("bad packet")
                    val buf = ByteArray(len)
                    inp.readFully(buf)
                    inbox.add(JSONObject(String(buf, Charsets.UTF_8)))
                }
            } catch (e: Exception) {
                if (!closed) status = "Соединение потеряно"
            } finally {
                connected = false
                try { s.close() } catch (_: Exception) {}
                inbox.add(JSONObject().put("t", "disconnect"))
            }
        }.apply { isDaemon = true; start() }
    }

    fun send(o: JSONObject) {
        if (!connected) return
        val bytes = o.toString().toByteArray(Charsets.UTF_8)
        writer.execute {
            try {
                val o2 = out ?: return@execute
                o2.writeInt(bytes.size); o2.write(bytes); o2.flush()
            } catch (_: Exception) {
                connected = false
            }
        }
    }

    fun close() {
        closed = true
        connected = false
        try { sock?.close() } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        writer.shutdownNow()
    }
}
