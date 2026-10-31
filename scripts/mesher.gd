class_name Mesher
extends RefCounted
## Построитель low-poly геометрии с цветом вершин.
## Альфа цвета — «вид»: 1.0 обычный, 0.5 светится, 0.0 стена (прорезается перед игроком).

const NORMAL := 1.0
const GLOW := 0.5
const WALL := 0.0

var verts := PackedVector3Array()
var norms := PackedVector3Array()
var cols := PackedColorArray()
var bones := PackedInt32Array()
var weights := PackedFloat32Array()
var skinned := false
var bone := 0                    # текущая кость для скелетных мешей
var xf := Transform3D.IDENTITY   # текущее преобразование
var _stack: Array = []

func clear() -> void:
	verts.clear()
	norms.clear()
	cols.clear()
	bones.clear()
	weights.clear()
	xf = Transform3D.IDENTITY
	_stack.clear()

func push() -> void: _stack.append(xf)
func pop() -> void: xf = _stack.pop_back()
func identity() -> void:
	xf = Transform3D.IDENTITY
	_stack.clear()
func translate(v: Vector3) -> void: xf = xf * Transform3D(Basis.IDENTITY, v)
func rot_y(deg: float) -> void: xf = xf * Transform3D(Basis(Vector3.UP, deg_to_rad(deg)), Vector3.ZERO)
func rot_x(deg: float) -> void: xf = xf * Transform3D(Basis(Vector3.RIGHT, deg_to_rad(deg)), Vector3.ZERO)
func rot_z(deg: float) -> void: xf = xf * Transform3D(Basis(Vector3.BACK, deg_to_rad(deg)), Vector3.ZERO)

func _put(v: Vector3, n: Vector3, c: Color) -> void:
	verts.append(v)
	norms.append(n)
	cols.append(c)
	if skinned:
		bones.append(bone)
		bones.append(0)
		bones.append(0)
		bones.append(0)
		weights.append(1.0)
		weights.append(0.0)
		weights.append(0.0)
		weights.append(0.0)

static func kind(c: Color, k: float) -> Color:
	return Color(c.r, c.g, c.b, k)

## Треугольник в мировых координатах (порядок против часовой при взгляде снаружи)
func tri(a: Vector3, b: Vector3, c: Vector3, col: Color) -> void:
	var n := (b - a).cross(c - a)
	if n.length_squared() < 1e-12:
		return
	n = n.normalized()
	# Godot считает лицевыми треугольники по часовой — меняем порядок
	_put(a, n, col)
	_put(c, n, col)
	_put(b, n, col)

func quad(a: Vector3, b: Vector3, c: Vector3, d: Vector3, col: Color) -> void:
	tri(a, b, c, col)
	tri(a, c, d, col)

func flat(x0: float, z0: float, x1: float, z1: float, y: float, col: Color) -> void:
	quad(Vector3(x0, y, z0), Vector3(x0, y, z1), Vector3(x1, y, z1), Vector3(x1, y, z0), col)

## Ящик по осям (без дна)
func aabb(x0: float, y0: float, z0: float, x1: float, y1: float, z1: float, col: Color, top = null) -> void:
	flat(x0, z0, x1, z1, y1, top if top != null else col)
	quad(Vector3(x0, y0, z1), Vector3(x1, y0, z1), Vector3(x1, y1, z1), Vector3(x0, y1, z1), col)
	quad(Vector3(x1, y0, z0), Vector3(x0, y0, z0), Vector3(x0, y1, z0), Vector3(x1, y1, z0), col)
	quad(Vector3(x1, y0, z1), Vector3(x1, y0, z0), Vector3(x1, y1, z0), Vector3(x1, y1, z1), col)
	quad(Vector3(x0, y0, z0), Vector3(x0, y0, z1), Vector3(x0, y1, z1), Vector3(x0, y1, z0), col)

## Ящик с центром и размерами в локальных координатах текущего преобразования
func box(c: Vector3, s: Vector3, col: Color, bottom: bool = false) -> void:
	var h := s * 0.5
	var p: Array = []
	for k in 8:
		p.append(xf * (c + Vector3(h.x if k & 1 else -h.x, h.y if k & 2 else -h.y, h.z if k & 4 else -h.z)))
	quad(p[2], p[6], p[7], p[3], col)
	quad(p[4], p[5], p[7], p[6], col)
	quad(p[1], p[0], p[2], p[3], col)
	quad(p[5], p[1], p[3], p[7], col)
	quad(p[0], p[4], p[6], p[2], col)
	if bottom:
		quad(p[0], p[1], p[5], p[4], col)

## Вершина с гладкой нормалью в локальных координатах
func _lv(v: Vector3, n: Vector3, c: Color) -> void:
	_put(xf * v, (xf.basis * n).normalized(), c)

func _ltri_smooth(a: Vector3, na: Vector3, b: Vector3, nb: Vector3, c: Vector3, nc: Vector3, col: Color) -> void:
	_lv(a, na, col)
	_lv(c, nc, col)
	_lv(b, nb, col)

## Гладкий усечённый конус вдоль локальной Y
func tube(y0: float, y1: float, r0: float, r1: float, col: Color, segs: int = 8, sx: float = 1.0, sz: float = 1.0, cap_top: bool = true) -> void:
	var slope := (r0 - r1) / (y1 - y0)
	for i in segs:
		var a0 := TAU * i / segs
		var a1 := TAU * (i + 1) / segs
		var c0 := cos(a0)
		var s0 := sin(a0)
		var c1 := cos(a1)
		var s1 := sin(a1)
		var n0 := Vector3(c0 / sx, slope, s0 / sz)
		var n1 := Vector3(c1 / sx, slope, s1 / sz)
		var b0 := Vector3(c0 * r0 * sx, y0, s0 * r0 * sz)
		var b1 := Vector3(c1 * r0 * sx, y0, s1 * r0 * sz)
		var t0 := Vector3(c0 * r1 * sx, y1, s0 * r1 * sz)
		var t1 := Vector3(c1 * r1 * sx, y1, s1 * r1 * sz)
		_ltri_smooth(b0, n0, t1, n1, b1, n1, col)
		_ltri_smooth(b0, n0, t0, n0, t1, n1, col)
		if cap_top:
			_ltri_smooth(Vector3(0, y1, 0), Vector3.UP, t1, Vector3.UP, t0, Vector3.UP, col)

## Гладкий эллипсоид. lat_from/lat_to — доля по высоте (0 верх, 1 низ)
func ellipsoid(c: Vector3, r: Vector3, col: Color, rings: int = 6, segs: int = 10, lat_from: float = 0.0, lat_to: float = 1.0) -> void:
	for i in rings:
		var p0 := PI * (lat_from + (lat_to - lat_from) * i / rings)
		var p1 := PI * (lat_from + (lat_to - lat_from) * (i + 1) / rings)
		for j in segs:
			var t0 := TAU * j / segs
			var t1 := TAU * (j + 1) / segs
			var u00 := Vector3(sin(p0) * cos(t0), cos(p0), sin(p0) * sin(t0))
			var u10 := Vector3(sin(p1) * cos(t0), cos(p1), sin(p1) * sin(t0))
			var u01 := Vector3(sin(p0) * cos(t1), cos(p0), sin(p0) * sin(t1))
			var u11 := Vector3(sin(p1) * cos(t1), cos(p1), sin(p1) * sin(t1))
			var v00 := c + u00 * r
			var v10 := c + u10 * r
			var v01 := c + u01 * r
			var v11 := c + u11 * r
			var n00 := u00 / r
			var n10 := u10 / r
			var n01 := u01 / r
			var n11 := u11 / r
			_ltri_smooth(v00, n00, v11, n11, v10, n10, col)
			_ltri_smooth(v00, n00, v01, n01, v11, n11, col)

## Low-poly «облако» (крона дерева) в мировых координатах
func blob(c: Vector3, r: Vector3, col: Color, seed: int) -> void:
	var rings := 3
	var segs := 6
	var pts: Array = []
	for i in rings + 1:
		for j in segs:
			var phi := PI * i / rings
			var th := TAU * j / segs + (PI / segs if i % 2 == 1 else 0.0)
			var jit := 1.0 + (((seed * 31 + i * 7 + j * 13) % 11) - 5) * 0.03
			pts.append(c + Vector3(sin(phi) * cos(th) * r.x * jit, cos(phi) * r.y, sin(phi) * sin(th) * r.z * jit))
	for i in rings:
		for j in segs:
			var a: Vector3 = pts[i * segs + j]
			var b: Vector3 = pts[i * segs + (j + 1) % segs]
			var cc: Vector3 = pts[(i + 1) * segs + (j + 1) % segs]
			var d: Vector3 = pts[(i + 1) * segs + j]
			var shade := col if i == 0 else col.darkened(0.12 - 0.04 * i)
			shade.a = col.a
			tri(a, b, cc, shade)
			tri(a, cc, d, shade)

func commit(mesh: ArrayMesh = null) -> ArrayMesh:
	if mesh == null:
		mesh = ArrayMesh.new()
	else:
		mesh.clear_surfaces()
	if verts.size() == 0:
		return mesh
	var arr := []
	arr.resize(Mesh.ARRAY_MAX)
	arr[Mesh.ARRAY_VERTEX] = verts
	arr[Mesh.ARRAY_NORMAL] = norms
	arr[Mesh.ARRAY_COLOR] = cols
	if skinned:
		arr[Mesh.ARRAY_BONES] = bones
		arr[Mesh.ARRAY_WEIGHTS] = weights
	mesh.add_surface_from_arrays(Mesh.PRIMITIVE_TRIANGLES, arr)
	return mesh
