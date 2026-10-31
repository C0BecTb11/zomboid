package com.c0bectb11.zomboid

import android.opengl.GLES30
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin

/**
 * Построитель low-poly геометрии.
 * Вершина: позиция(3) нормаль(3) цвет(3) вид(1).
 * Вид: 0 — обычный, 1 — светится, 2 — стена (прорезается перед игроком).
 */
class MeshBuilder(initial: Int = 4096) {
    var data = FloatArray(initial * STRIDE)
    var count = 0          // вершин
    private val m = FloatArray(16)
    private val tmp = FloatArray(16)
    private val v = FloatArray(4)
    private val r = FloatArray(4)
    private val corners = FloatArray(24)

    companion object {
        const val STRIDE = 10
        fun cr(c: Int) = ((c shr 16) and 255) / 255f
        fun cg(c: Int) = ((c shr 8) and 255) / 255f
        fun cb(c: Int) = (c and 255) / 255f
    }

    fun reset() { count = 0 }

    private fun ensure(n: Int) {
        val need = (count + n) * STRIDE
        if (need > data.size) data = data.copyOf(maxOf(need, data.size * 2))
    }

    private fun put(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, c: Int, kind: Float) {
        val o = count * STRIDE
        data[o] = x; data[o + 1] = y; data[o + 2] = z
        data[o + 3] = nx; data[o + 4] = ny; data[o + 5] = nz
        data[o + 6] = cr(c); data[o + 7] = cg(c); data[o + 8] = cb(c); data[o + 9] = kind
        count++
    }

    /** Треугольник с плоской нормалью */
    fun tri(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float, c: Int, kind: Float = 0f) {
        ensure(3)
        val ux = bx - ax; val uy = by - ay; val uz = bz - az
        val wx = cx - ax; val wy = cy - ay; val wz = cz - az
        var nx = uy * wz - uz * wy; var ny = uz * wx - ux * wz; var nz = ux * wy - uy * wx
        val l = Math.sqrt((nx * nx + ny * ny + nz * nz).toDouble()).toFloat().coerceAtLeast(1e-6f)
        nx /= l; ny /= l; nz /= l
        put(ax, ay, az, nx, ny, nz, c, kind); put(bx, by, bz, nx, ny, nz, c, kind); put(cx, cy, cz, nx, ny, nz, c, kind)
    }

    /** Четырёхугольник a-b-c-d (против часовой при взгляде с лицевой стороны) */
    fun quad(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float, dx: Float, dy: Float, dz: Float, c: Int, kind: Float = 0f) {
        tri(ax, ay, az, bx, by, bz, cx, cy, cz, c, kind)
        tri(ax, ay, az, cx, cy, cz, dx, dy, dz, c, kind)
    }

    /** Горизонтальный прямоугольник на высоте y (смотрит вверх) */
    fun flat(x0: Float, z0: Float, x1: Float, z1: Float, y: Float, c: Int, kind: Float = 0f) {
        quad(x0, y, z0, x0, y, z1, x1, y, z1, x1, y, z0, c, kind)
    }

    /** Ящик по осям: от (x0,y0,z0) до (x1,y1,z1). bottom — рисовать ли дно */
    fun aabb(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, c: Int, kind: Float = 0f, top: Int = c) {
        flat(x0, z0, x1, z1, y1, top, kind)
        quad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, c, kind)   // +z
        quad(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, c, kind)   // -z
        quad(x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, c, kind)   // +x
        quad(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, c, kind)   // -x
    }

    // ---------- ящики с матрицей (для персонажей и поворотов) ----------
    val stack = ArrayList<FloatArray>()
    var cur = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    fun identity() { Matrix.setIdentityM(cur, 0); stack.clear() }
    fun push() { stack.add(cur.copyOf()) }
    fun pop() { cur = stack.removeAt(stack.size - 1) }
    fun translate(x: Float, y: Float, z: Float) { Matrix.translateM(cur, 0, x, y, z) }
    fun rotY(deg: Float) { Matrix.rotateM(cur, 0, deg, 0f, 1f, 0f) }
    fun rotX(deg: Float) { Matrix.rotateM(cur, 0, deg, 1f, 0f, 0f) }
    fun rotZ(deg: Float) { Matrix.rotateM(cur, 0, deg, 0f, 0f, 1f) }

    private fun xf(x: Float, y: Float, z: Float, w: Float, out: FloatArray, off: Int) {
        v[0] = x; v[1] = y; v[2] = z; v[3] = w
        Matrix.multiplyMV(r, 0, cur, 0, v, 0)
        out[off] = r[0]; out[off + 1] = r[1]; out[off + 2] = r[2]
    }

    /** Ящик с центром (cx,cy,cz) и размерами в локальных координатах текущей матрицы */
    fun box(cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float, c: Int, kind: Float = 0f, bottom: Boolean = false) {
        val hx = sx / 2; val hy = sy / 2; val hz = sz / 2
        var i = 0
        for (k in 0 until 8) {
            xf(cx + (if (k and 1 != 0) hx else -hx), cy + (if (k and 2 != 0) hy else -hy), cz + (if (k and 4 != 0) hz else -hz), 1f, corners, i); i += 3
        }
        fun cq(a: Int, b: Int, cc: Int, d: Int) {
            quad(corners[a * 3], corners[a * 3 + 1], corners[a * 3 + 2], corners[b * 3], corners[b * 3 + 1], corners[b * 3 + 2],
                corners[cc * 3], corners[cc * 3 + 1], corners[cc * 3 + 2], corners[d * 3], corners[d * 3 + 1], corners[d * 3 + 2], c, kind)
        }
        // индексы: бит0 — x, бит1 — y, бит2 — z
        cq(2, 6, 7, 3)          // верх
        cq(4, 5, 7, 6)          // +z
        cq(1, 0, 2, 3)          // -z
        cq(5, 1, 3, 7)          // +x
        cq(0, 4, 6, 2)          // -x
        if (bottom) cq(0, 1, 5, 4)
    }

    /** Low-poly «шар» (крона дерева) */
    fun blob(cx: Float, cy: Float, cz: Float, rx: Float, ry: Float, rz: Float, c: Int, seed: Int, kind: Float = 0f) {
        val rings = 3; val segs = 6
        val pts = Array((rings + 1) * segs) { FloatArray(3) }
        for (i in 0..rings) for (j in 0 until segs) {
            val phi = Math.PI * i / rings
            val th = 2 * Math.PI * j / segs + (if (i % 2 == 1) Math.PI / segs else 0.0)
            val jit = 1f + (((seed * 31 + i * 7 + j * 13) % 11) - 5) * 0.03f
            val p = pts[i * segs + j]
            p[0] = cx + (sin(phi) * cos(th)).toFloat() * rx * jit
            p[1] = cy + cos(phi).toFloat() * ry
            p[2] = cz + (sin(phi) * sin(th)).toFloat() * rz * jit
        }
        for (i in 0 until rings) for (j in 0 until segs) {
            val a = pts[i * segs + j]; val b = pts[i * segs + (j + 1) % segs]
            val cc = pts[(i + 1) * segs + (j + 1) % segs]; val d = pts[(i + 1) * segs + j]
            val shade = if (i == 0) c else darken(c, 0.88f + 0.04f * i)
            tri(a[0], a[1], a[2], b[0], b[1], b[2], cc[0], cc[1], cc[2], shade, kind)
            tri(a[0], a[1], a[2], cc[0], cc[1], cc[2], d[0], d[1], d[2], shade, kind)
        }
    }

    fun darken(c: Int, k: Float): Int {
        val rr = (((c shr 16) and 255) * k).toInt().coerceIn(0, 255)
        val gg = (((c shr 8) and 255) * k).toInt().coerceIn(0, 255)
        val bb = ((c and 255) * k).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
    }
}

/** Буфер вершин на GPU */
class Mesh {
    private var vbo = 0
    var count = 0
    private var cap = 0
    private var fb: FloatBuffer? = null

    fun upload(mb: MeshBuilder, dynamic: Boolean = false) {
        if (vbo == 0) { val ids = IntArray(1); GLES30.glGenBuffers(1, ids, 0); vbo = ids[0] }
        val n = mb.count * MeshBuilder.STRIDE
        if (fb == null || fb!!.capacity() < n) fb = ByteBuffer.allocateDirect(maxOf(n, 1024) * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val b = fb!!
        b.clear(); b.put(mb.data, 0, n); b.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        if (dynamic && n <= cap) GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, n * 4, b)
        else { GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, n * 4, b, if (dynamic) GLES30.GL_DYNAMIC_DRAW else GLES30.GL_STATIC_DRAW); cap = n }
        count = mb.count
    }

    fun draw() {
        if (count == 0 || vbo == 0) return
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        val st = MeshBuilder.STRIDE * 4
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, st, 0)
        GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, st, 12)
        GLES30.glEnableVertexAttribArray(2); GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, st, 24)
        GLES30.glEnableVertexAttribArray(3); GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, st, 36)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, count)
    }

    fun release() { if (vbo != 0) { GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0); vbo = 0 }; count = 0 }
}

object Shaders {
    fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
        val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) { val log = GLES30.glGetShaderInfoLog(s); android.util.Log.e("zomboid", "shader: $log"); throw RuntimeException("Shader: $log") }
        return s
    }

    fun program(vs: String, fs: String): Int {
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) { val log = GLES30.glGetProgramInfoLog(p); android.util.Log.e("zomboid", "link: $log"); throw RuntimeException("Link: $log") }
        return p
    }

    const val MAIN_VS = """#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNrm;
layout(location=2) in vec3 aCol;
layout(location=3) in float aKind;
uniform mat4 uVP;
uniform mat4 uLightVP;
out vec3 vPos; out vec3 vNrm; out vec3 vCol; out float vKind; out vec4 vLight;
void main() {
    vPos = aPos; vNrm = aNrm; vCol = aCol; vKind = aKind;
    vLight = uLightVP * vec4(aPos, 1.0);
    gl_Position = uVP * vec4(aPos, 1.0);
}
"""

    const val MAIN_FS = """#version 300 es
precision highp float;
precision highp sampler2DShadow;
in vec3 vPos; in vec3 vNrm; in vec3 vCol; in float vKind; in vec4 vLight;
out vec4 frag;
uniform sampler2DShadow uShadow;
uniform sampler2D uVis;
uniform vec3 uSunDir; uniform vec3 uSunCol; uniform vec3 uAmb; uniform float uNight;
uniform vec3 uPlayer; uniform vec2 uCamBack; uniform float uCut;
uniform vec3 uFlashPos; uniform vec3 uFlashDir; uniform float uFlash;
uniform int uNumLights; uniform vec4 uLights[12]; uniform vec3 uLightCols[12];
uniform vec3 uFogCol; uniform vec3 uCamPos; uniform float uShadowTexel;

float shadowAt() {
    vec3 p = vLight.xyz / vLight.w * 0.5 + 0.5;
    if (p.z > 1.0 || p.x < 0.0 || p.x > 1.0 || p.y < 0.0 || p.y > 1.0) return 1.0;
    float s = 0.0;
    for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++)
        s += texture(uShadow, vec3(p.xy + vec2(float(x), float(y)) * uShadowTexel, p.z - 0.0012));
    return s / 9.0;
}

void main() {
    if (vKind > 1.5 && uCut > 0.5 && vPos.y > 0.5) {
        vec2 d = vPos.xz - uPlayer.xz;
        float along = dot(d, uCamBack);
        float side = abs(d.x * uCamBack.y - d.y * uCamBack.x);
        if (along > 0.2 && along < 7.0 && side < 2.2 + along * 0.25) discard;
    }
    vec3 col = vCol;
    if (vKind > 0.5 && vKind < 1.5) { frag = vec4(col * (0.55 + uNight * 1.3), 1.0); return; }
    vec3 n = normalize(vNrm);
    float sh = shadowAt();
    float diff = max(dot(n, -uSunDir), 0.0);
    vec3 light = uAmb * (0.8 + 0.25 * n.y) + uSunCol * diff * sh;
    if (uFlash > 0.0) {
        vec3 L = vPos - uFlashPos; float dist = length(L); vec3 Ld = L / max(dist, 0.001);
        float cone = smoothstep(0.82, 0.95, dot(Ld, uFlashDir));
        float att = uFlash * cone * clamp(1.0 - dist / 14.0, 0.0, 1.0);
        light += vec3(1.0, 0.92, 0.75) * att * max(dot(n, -Ld), 0.3) * 1.6;
    }
    for (int i = 0; i < 12; i++) {
        if (i >= uNumLights) break;
        vec3 L = vPos - uLights[i].xyz; float dist = length(L);
        float att = clamp(1.0 - dist / uLights[i].w, 0.0, 1.0); att *= att;
        light += uLightCols[i] * att * max(dot(n, -L / max(dist, 0.001)), 0.35);
    }
    vec3 c = col * light;
    float v = texture(uVis, vPos.xz / 100.0).r;
    c *= mix(0.32, 1.0, v);
    float fd = length(vPos - uCamPos);
    c = mix(c, uFogCol, clamp((fd - 22.0) / 26.0, 0.0, 0.75));
    frag = vec4(c, 1.0);
}
"""

    const val DEPTH_VS = """#version 300 es
layout(location=0) in vec3 aPos;
uniform mat4 uLightVP;
void main() { gl_Position = uLightVP * vec4(aPos, 1.0); }
"""

    const val DEPTH_FS = """#version 300 es
precision mediump float;
out vec4 frag;
void main() { frag = vec4(1.0); }
"""
}
