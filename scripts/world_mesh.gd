class_name WorldMesh
extends RefCounted
## Строит статическую 3D-геометрию по карте. X — x карты, Z — y карты, Y — вверх.

const WH := 2.4
const HALF := 0.11
const CHUNK := 10
const W = preload("res://scripts/world.gd")

const WALL_COLS := [0xDCD2C2, 0xCDB99C, 0xB9C1C6, 0xD8BEA6, 0xC9C3B0, 0xB8C4B0]
const ROOF_COLS := [0x8A4236, 0x4E5A66, 0x6E5C46, 0x46604E, 0x6A4440]
const CAR_COLS := [0x8E2E2A, 0x2C4C7C, 0xD6D6CC, 0x35373B, 0x55744A, 0xC89A3C, 0x6A6E74]

var w: GameWorld
var chunks_x := 0
var chunks_y := 0
var lamps: Array = []     # [x, z основания, x, z головы]

static func hash2(x: int, y: int) -> int:
	var h := (x * 374761393 + y * 668265263) & 0xFFFFFFFF
	h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
	return (h ^ (h >> 16)) & 0x7FFFFFFF

static func c(hex: int, k: float = 1.0) -> Color:
	return Color8((hex >> 16) & 255, (hex >> 8) & 255, hex & 255, int(round(k * 255.0)))

static func dk(col: Color, k: float) -> Color:
	return Color(col.r * k, col.g * k, col.b * k, col.a)

static func jit(col: Color, h: int, amt: float) -> Color:
	var k := 1.0 + ((((h >> 4) % 21) - 10) / 10.0) * amt
	return Color(clampf(col.r * k, 0, 1), clampf(col.g * k, 0, 1), clampf(col.b * k, 0, 1), col.a)

func _init(world: GameWorld) -> void:
	w = world
	chunks_x = (w.w + CHUNK - 1) / CHUNK
	chunks_y = (w.h + CHUNK - 1) / CHUNK
	for y in w.h:
		for x in w.w:
			if w.t(x, y) != W.SIDEWALK:
				continue
			var up := w.t(x, y - 1) == W.ROAD
			var dn := w.t(x, y + 1) == W.ROAD
			var lf := w.t(x - 1, y) == W.ROAD
			var rt := w.t(x + 1, y) == W.ROAD
			if (up or dn) and not (lf or rt) and x % 9 == 4:
				lamps.append([x + 0.5, y + 0.5, x + 0.5, y + 0.5 + (-0.8 if up else 0.8)])
			elif (lf or rt) and not (up or dn) and y % 9 == 4:
				lamps.append([x + 0.5, y + 0.5, x + 0.5 + (-0.8 if lf else 0.8), y + 0.5])

func wall_color(x: int, y: int) -> Color:
	var b: int = w.building_of[w.idx(clampi(x, 0, w.w - 1), clampi(y, 0, w.h - 1))]
	return c(WALL_COLS[maxi(b, 0) % WALL_COLS.size()], 0.0)

static func ground_h(t: int) -> float:
	if t == W.SIDEWALK:
		return 0.12
	if W.is_floor(t) or W.is_furniture(t):
		return 0.1
	return 0.0

# ================== ЧАНК ==================
func build_chunk(m: Mesher, cx: int, cy: int) -> void:
	m.clear()
	for y in range(cy * CHUNK, mini(w.h, (cy + 1) * CHUNK)):
		for x in range(cx * CHUNK, mini(w.w, (cx + 1) * CHUNK)):
			_tile(m, x, y)
	for l in lamps:
		if int(l[0]) / CHUNK == cx and int(l[1]) / CHUNK == cy:
			_lamp(m, l)

func _tile(m: Mesher, x: int, y: int) -> void:
	var t := w.t(x, y)
	var hv := hash2(x, y)
	var fx := float(x)
	var fz := float(y)
	var i := w.idx(x, y)
	if W.wall_like(t):
		_ground_under_wall(m, x, y)
		_walls(m, x, y, t)
	elif W.is_furniture(t):
		_floor_top(m, _floor_near(x, y), x, y, fx, fz, fx + 1, fz + 1)
		_furniture(m, x, y, t)
	else:
		_ground(m, W.ROAD if W.is_car(t) else t, x, y, fx, fz, fx + 1, fz + 1)
		var h := ground_h(t)
		if h > 0.0:
			for k in 4:
				var n := w.t(x + W.DX4[k], y + W.DY4[k])
				var hn := h if W.wall_like(n) else ground_h(n)
				if hn >= h:
					continue
				var side := jit(c(0x7C7C76), hv, 0.05)
				match k:
					0: m.quad(Vector3(fx + 1, hn, fz + 1), Vector3(fx + 1, hn, fz), Vector3(fx + 1, h, fz), Vector3(fx + 1, h, fz + 1), side)
					1: m.quad(Vector3(fx, hn, fz), Vector3(fx, hn, fz + 1), Vector3(fx, h, fz + 1), Vector3(fx, h, fz), side)
					2: m.quad(Vector3(fx, hn, fz + 1), Vector3(fx + 1, hn, fz + 1), Vector3(fx + 1, h, fz + 1), Vector3(fx, h, fz + 1), side)
					_: m.quad(Vector3(fx + 1, hn, fz), Vector3(fx, hn, fz), Vector3(fx, h, fz), Vector3(fx + 1, h, fz), side)
		match t:
			W.TREE: _tree(m, x, y)
			W.CAR: _car(m, x, y, true)
			W.CAR_V: _car(m, x, y, false)
			W.DOOR_BROKEN: _broken_door(m, x, y)
	# баррикада
	var bl := w.barr_at(i)
	if bl > 0:
		_barricade(m, x, y, bl)
	# кучка вещей
	if not W.is_container(t) and t != W.CAR_TAIL and w.containers.has(i) and not w.containers[i].is_empty():
		_pile(m, x, y, w.containers[i])

func _floor_near(x: int, y: int) -> int:
	for k in 4:
		var n := w.t(x + W.DX4[k], y + W.DY4[k])
		if W.is_floor(n):
			return n
	return W.WOOD

func _ground(m: Mesher, t: int, x: int, y: int, x0: float, z0: float, x1: float, z1: float) -> void:
	var hv := hash2(x, y)
	if t == W.GRASS or t == W.TREE:
		var base := c([0x4C6A38, 0x56723E, 0x4A6436, 0x5A6E3A][hv % 4])
		var mx := (x0 + x1) / 2
		var mz := (z0 + z1) / 2
		m.flat(x0, z0, mx, mz, 0, jit(base, hv, 0.06))
		m.flat(mx, z0, x1, mz, 0, jit(base, hv * 3, 0.06))
		m.flat(x0, mz, mx, z1, 0, jit(base, hv * 5, 0.06))
		m.flat(mx, mz, x1, z1, 0, jit(base, hv * 7, 0.06))
		if x1 - x0 > 0.9 and hv % 37 == 0:
			var ox := x0 + 0.3 + (hv % 5) * 0.08
			var oz := z0 + 0.3 + (hv % 7) * 0.06
			m.aabb(ox, 0, oz, ox + 0.22, 0.12, oz + 0.16, c(0x7E7E78))
		if x1 - x0 > 0.9 and hv % 23 == 1:
			m.identity()
			m.translate(Vector3(x0 + 0.5, 0, z0 + 0.5))
			m.rot_y(hv % 90)
			m.box(Vector3(0, 0.1, 0), Vector3(0.05, 0.2, 0.25), c(0x6A8A44))
			m.box(Vector3(0, 0.1, 0), Vector3(0.25, 0.2, 0.05), c(0x6A8A44))
			m.identity()
	elif t == W.ROAD:
		m.flat(x0, z0, x1, z1, 0, jit(c(0x3C3D40), hv, 0.05))
		if x1 - x0 > 0.9:
			var cxr := x % 25
			var cyr := y % 25
			if cyr == 1 and cxr >= 4 and cxr <= 23 and x % 2 == 0:
				m.flat(x0 + 0.1, z0 + 0.46, x1 - 0.1, z0 + 0.54, 0.006, c(0xC8A83A))
			if cxr == 1 and cyr >= 4 and cyr <= 23 and y % 2 == 0:
				m.flat(x0 + 0.46, z0 + 0.1, x0 + 0.54, z1 - 0.1, 0.006, c(0xC8A83A))
			if (cxr == 4 or cxr == 23) and cyr <= 2:
				for k in 3:
					m.flat(x0 + 0.15, z0 + k * 0.33 + 0.06, x1 - 0.15, z0 + k * 0.33 + 0.22, 0.006, c(0xD8D8D0))
			if (cyr == 4 or cyr == 23) and cxr <= 2:
				for k in 3:
					m.flat(x0 + k * 0.33 + 0.06, z0 + 0.15, x0 + k * 0.33 + 0.22, z1 - 0.15, 0.006, c(0xD8D8D0))
			if hv % 19 == 0:
				m.flat(x0 + 0.2, z0 + 0.3, x0 + 0.7, z0 + 0.6, 0.004, c(0x2E2F31))
	elif t == W.SIDEWALK:
		var h := 0.12
		var mx2 := (x0 + x1) / 2
		var mz2 := (z0 + z1) / 2
		var g := 0.02
		m.flat(x0, z0, x1, z1, h - 0.005, c(0x6E6E68))
		var base2 := c(0x9C9C96)
		m.flat(x0 + g, z0 + g, mx2 - g, mz2 - g, h, jit(base2, hv, 0.05))
		m.flat(mx2 + g, z0 + g, x1 - g, mz2 - g, h, jit(base2, hv * 3, 0.05))
		m.flat(x0 + g, mz2 + g, mx2 - g, z1 - g, h, jit(base2, hv * 5, 0.05))
		m.flat(mx2 + g, mz2 + g, x1 - g, z1 - g, h, jit(base2, hv * 7, 0.05))
	elif W.is_floor(t):
		_floor_top(m, t, x, y, x0, z0, x1, z1)
	else:
		m.flat(x0, z0, x1, z1, 0, c(0x4C6A38))

func _floor_top(m: Mesher, t: int, x: int, y: int, x0: float, z0: float, x1: float, z1: float) -> void:
	var hv := hash2(x, y)
	var h := 0.1
	if t == W.TILEFLOOR:
		var mx := x + 0.5
		var mz := y + 0.5
		var a := c(0xD8D0C0)
		var b := c(0xB8AE9C)
		for q in 4:
			var qx0 := float(x) if q % 2 == 0 else mx
			var qz0 := float(y) if q < 2 else mz
			var cx0 := maxf(qx0, x0)
			var cz0 := maxf(qz0, z0)
			var cx1 := minf(qx0 + 0.5, x1)
			var cz1 := minf(qz0 + 0.5, z1)
			if cx1 > cx0 and cz1 > cz0:
				m.flat(cx0, cz0, cx1, cz1, h, a if (q + (q >> 1)) % 2 == 0 else b)
	else:
		for k in 4:
			var pz0 := y + k * 0.25
			var pz1 := pz0 + 0.25
			var cz0 := maxf(pz0, z0)
			var cz1 := minf(pz1, z1)
			if cz1 <= cz0:
				continue
			m.flat(x0, cz0, x1, cz1 - 0.01, h, jit(c(0x8E6842), hv * (k + 3), 0.08))
			m.flat(x0, cz1 - 0.01, x1, cz1, h, c(0x5E4428))
		if hv % 53 == 0 and x1 - x0 > 0.9:
			m.flat(x0 + 0.1, z0 + 0.1, x1 - 0.1, z1 - 0.1, h + 0.01, c([0x8A3A30, 0x3A5070, 0x6A5A30][hv % 3]))

func _ground_under_wall(m: Mesher, x: int, y: int) -> void:
	var out := W.GRASS
	for k in 4:
		var n := w.t(x + W.DX4[k], y + W.DY4[k])
		if not W.wall_like(n) and not W.is_floor(n) and not W.is_furniture(n) and not W.is_car(n):
			out = W.GRASS if n == W.TREE else n
			break
	for q in 4:
		var dx := -1 if q % 2 == 0 else 1
		var dy := -1 if q < 2 else 1
		var fl := -1
		for n in [w.t(x + dx, y), w.t(x, y + dy), w.t(x + dx, y + dy)]:
			if W.is_floor(n):
				fl = n
				break
			if W.is_furniture(n):
				fl = W.WOOD
		var x0 := x + (0.0 if dx < 0 else 0.5)
		var z0 := y + (0.0 if dy < 0 else 0.5)
		if fl >= 0:
			_floor_top(m, fl, x, y, x0, z0, x0 + 0.5, z0 + 0.5)
		else:
			_ground(m, out, x, y, x0, z0, x0 + 0.5, z0 + 0.5)

# ================== СТЕНЫ ==================
func _walls(m: Mesher, x: int, y: int, t: int) -> void:
	var col := wall_color(x, y)
	var cap := dk(col, 0.72)
	var cx := x + 0.5
	var cz := y + 0.5
	var h := HALF
	var l := W.wall_like(w.t(x - 1, y))
	var r := W.wall_like(w.t(x + 1, y))
	var u := W.wall_like(w.t(x, y - 1))
	var d := W.wall_like(w.t(x, y + 1))
	var horiz := l or r
	match t:
		W.WALL:
			m.aabb(cx - h, 0, cz - h, cx + h, WH, cz + h, col, cap)
			if r: m.aabb(cx, 0, cz - h, x + 1.0, WH, cz + h, col, cap)
			if l: m.aabb(x, 0, cz - h, cx, WH, cz + h, col, cap)
			if d: m.aabb(cx - h, 0, cz, cx + h, WH, y + 1.0, col, cap)
			if u: m.aabb(cx - h, 0, y, cx + h, WH, cz, col, cap)
			var base := dk(col, 0.6)
			if r or l:
				m.aabb(x if l else cx - h, 0, cz - h - 0.01, x + 1.0 if r else cx + h, 0.18, cz + h + 0.01, base)
			if u or d:
				m.aabb(cx - h - 0.01, 0, y if u else cz - h, cx + h + 0.01, 0.18, y + 1.0 if d else cz + h, base)
		W.WINDOW, W.WINDOW_BROKEN:
			_seg(m, x, y, horiz, 0, 0.95, col, cap)
			_seg(m, x, y, horiz, 1.85, WH, col, cap)
			var frame := c(0xEDE6DA, 0)
			if horiz:
				m.aabb(x + 0.04, 0.95, cz - h - 0.05, x + 0.96, 1.0, cz + h + 0.05, frame)
				m.aabb(x, 1.0, cz - h, x + 0.06, 1.85, cz + h, frame)
				m.aabb(x + 0.94, 1.0, cz - h, x + 1.0, 1.85, cz + h, frame)
			else:
				m.aabb(cx - h - 0.05, 0.95, y + 0.04, cx + h + 0.05, 1.0, y + 0.96, frame)
				m.aabb(cx - h, 1.0, y, cx + h, 1.85, y + 0.06, frame)
				m.aabb(cx - h, 1.0, y + 0.94, cx + h, 1.85, y + 1.0, frame)
			if t == W.WINDOW:
				var glass := c(0x7FA6BA, 0)
				if horiz:
					m.aabb(x + 0.06, 1.0, cz - 0.02, x + 0.94, 1.85, cz + 0.02, glass)
					m.aabb(cx - 0.02, 1.0, cz - 0.03, cx + 0.02, 1.85, cz + 0.03, frame)
				else:
					m.aabb(cx - 0.02, 1.0, y + 0.06, cx + 0.02, 1.85, y + 0.94, glass)
					m.aabb(cx - 0.03, 1.0, cz - 0.02, cx + 0.03, 1.85, cz + 0.02, frame)
			else:
				var g := c(0x9FC4D4, 0)
				if horiz:
					m.tri(Vector3(x + 0.06, 1.0, cz), Vector3(x + 0.3, 1.0, cz), Vector3(x + 0.1, 1.3, cz), g)
					m.tri(Vector3(x + 0.94, 1.85, cz), Vector3(x + 0.7, 1.85, cz), Vector3(x + 0.9, 1.55, cz), g)
				else:
					m.tri(Vector3(cx, 1.0, y + 0.06), Vector3(cx, 1.0, y + 0.3), Vector3(cx, 1.3, y + 0.1), g)
					m.tri(Vector3(cx, 1.85, y + 0.94), Vector3(cx, 1.85, y + 0.7), Vector3(cx, 1.55, y + 0.9), g)
				var gf := c(0x9FC4D4)
				for k in 4:
					var ox := cx + ((hash2(x + k, y) % 9) - 4) * 0.09
					var oz := cz + ((hash2(x, y + k) % 9) - 4) * 0.09
					m.tri(Vector3(ox, 0.11, oz), Vector3(ox + 0.08, 0.11, oz + 0.03), Vector3(ox + 0.02, 0.11, oz + 0.07), gf)
		_:
			_seg(m, x, y, horiz, 2.05, WH, col, cap)
			var jamb := dk(col, 0.8)
			if horiz:
				m.aabb(x, 0, cz - h - 0.02, x + 0.07, 2.05, cz + h + 0.02, jamb)
				m.aabb(x + 0.93, 0, cz - h - 0.02, x + 1.0, 2.05, cz + h + 0.02, jamb)
			else:
				m.aabb(cx - h - 0.02, 0, y, cx + h + 0.02, 2.05, y + 0.07, jamb)
				m.aabb(cx - h - 0.02, 0, y + 0.93, cx + h + 0.02, 2.05, y + 1.0, jamb)

func _seg(m: Mesher, x: int, y: int, horiz: bool, y0: float, y1: float, col: Color, cap: Color) -> void:
	var cx := x + 0.5
	var cz := y + 0.5
	if horiz:
		m.aabb(x, y0, cz - HALF, x + 1.0, y1, cz + HALF, col, cap)
	else:
		m.aabb(cx - HALF, y0, y, cx + HALF, y1, y + 1.0, col, cap)

func _broken_door(m: Mesher, x: int, y: int) -> void:
	m.identity()
	m.translate(Vector3(x + 0.5, 0.12, y + 0.5))
	m.rot_y(hash2(x, y) % 180)
	m.box(Vector3(-0.15, 0, 0), Vector3(0.8, 0.04, 0.18), c(0x8A5E34))
	m.rot_y(40)
	m.box(Vector3(0.2, 0.03, 0.1), Vector3(0.6, 0.04, 0.14), c(0x6E4A2A))
	m.identity()

func _barricade(m: Mesher, x: int, y: int, level: int) -> void:
	var horiz := w.along_y(x, y)
	var window := W.is_window_like(w.t(x, y))
	m.identity()
	m.translate(Vector3(x + 0.5, 0, y + 0.5))
	if not horiz:
		m.rot_y(90)
	for k in level:
		var yy := (1.15 if window else 0.6) + k * 0.35
		for side in [-0.16, 0.16]:
			m.push()
			m.translate(Vector3(0, yy, side))
			m.rot_z(9.0 if k % 2 == 0 else -8.0)
			m.box(Vector3.ZERO, Vector3(1.12, 0.16, 0.05), c(0xB07A44 if k % 2 == 0 else 0x9A6A3A))
			m.box(Vector3(-0.45, 0, 0.03), Vector3(0.03, 0.03, 0.02), c(0x606060))
			m.box(Vector3(0.45, 0, 0.03), Vector3(0.03, 0.03, 0.02), c(0x606060))
			m.pop()
	m.identity()

func _pile(m: Mesher, x: int, y: int, list: Array) -> void:
	var gy := ground_h(w.t(x, y))
	if W.is_door_like(w.t(x, y)) or W.is_window_like(w.t(x, y)):
		gy = 0.1
	m.identity()
	m.translate(Vector3(x + 0.5, gy, y + 0.5))
	m.rot_y(hash2(x, y) % 60)
	m.box(Vector3(0, 0.15, 0), Vector3(0.42, 0.3, 0.36), c(0x9A7448))
	m.box(Vector3(0, 0.15, 0), Vector3(0.44, 0.04, 0.38), c(0x6A4A2A))
	var ic: Color = Items.def(list[0]["id"])["color"]
	m.box(Vector3(0.05, 0.36, 0.02), Vector3(0.18, 0.12, 0.14), Color(ic.r, ic.g, ic.b, 1.0))
	m.identity()

# ================== МЕБЕЛЬ ==================
func _furniture(m: Mesher, x: int, y: int, t: int) -> void:
	var yaw := 0.0
	for k in 4:
		if W.is_floor(w.t(x + W.DX4[k], y + W.DY4[k])):
			yaw = rad_to_deg(atan2(W.DX4[k], W.DY4[k]))
			break
	var hv := hash2(x, y)
	m.identity()
	m.translate(Vector3(x + 0.5, 0.1, y + 0.5))
	m.rot_y(yaw)
	var wood := c(0x7A5434)
	match t:
		W.FRIDGE:
			m.box(Vector3(0, 0.85, -0.05), Vector3(0.76, 1.7, 0.66), c(0xE6E6E2))
			m.box(Vector3(0, 1.22, 0.285), Vector3(0.74, 0.02, 0.02), c(0xA8A8A8))
			m.box(Vector3(0.28, 1.45, 0.29), Vector3(0.04, 0.3, 0.04), c(0x8A8A8A))
			m.box(Vector3(0.28, 0.85, 0.29), Vector3(0.04, 0.3, 0.04), c(0x8A8A8A))
		W.COUNTER:
			var stove := hv % 3 == 0
			m.box(Vector3(0, 0.42, -0.06), Vector3(0.92, 0.84, 0.7), c(0x2E2E30 if stove else 0xD8D2C4))
			m.box(Vector3(0, 0.87, -0.06), Vector3(0.96, 0.06, 0.76), c(0x1E1E20 if stove else 0x5E6064))
			if stove:
				for k in 4:
					m.box(Vector3(-0.2 + (k % 2) * 0.4, 0.91, -0.2 + (k / 2) * 0.3), Vector3(0.2, 0.02, 0.2), c(0x101010))
			else:
				m.box(Vector3(-0.22, 0.45, 0.295), Vector3(0.02, 0.7, 0.02), c(0xA09888))
				m.box(Vector3(0.22, 0.45, 0.295), Vector3(0.02, 0.7, 0.02), c(0xA09888))
		W.WARDROBE:
			m.box(Vector3(0, 0.95, -0.15), Vector3(0.92, 1.9, 0.56), c(0x6A4628))
			m.box(Vector3(0, 0.95, 0.135), Vector3(0.02, 1.8, 0.02), c(0x3A2414))
			m.box(Vector3(-0.08, 1.0, 0.14), Vector3(0.04, 0.12, 0.03), c(0xC8A040))
			m.box(Vector3(0.08, 1.0, 0.14), Vector3(0.04, 0.12, 0.03), c(0xC8A040))
		W.SHELF:
			m.box(Vector3(0, 0.8, -0.28), Vector3(0.92, 1.6, 0.06), wood)
			m.box(Vector3(-0.44, 0.8, -0.1), Vector3(0.04, 1.6, 0.4), wood)
			m.box(Vector3(0.44, 0.8, -0.1), Vector3(0.04, 1.6, 0.4), wood)
			for s in 4:
				var sy := 0.05 + s * 0.5
				m.box(Vector3(0, sy, -0.1), Vector3(0.88, 0.04, 0.4), wood)
				if s < 3:
					for b in 6:
						if (hv >> (b + s * 3)) & 1 == 0:
							continue
						var bc := c([0xB03A30, 0x3A6AA0, 0xD0B040, 0x4A8A4A, 0xE0E0D8][(hv + b * 7 + s) % 5])
						m.box(Vector3(-0.33 + b * 0.13, sy + 0.17, -0.12), Vector3(0.1, 0.3, 0.28), bc)
		W.MEDCAB:
			m.box(Vector3(0, 0.9, -0.22), Vector3(0.7, 1.1, 0.36), c(0xF0F0EE))
			m.box(Vector3(0, 0.9, -0.035), Vector3(0.1, 0.42, 0.02), c(0xD03030))
			m.box(Vector3(0, 0.9, -0.035), Vector3(0.42, 0.1, 0.02), c(0xD03030))
		W.LOCKER:
			m.box(Vector3(0, 0.95, -0.15), Vector3(0.8, 1.9, 0.5), c(0x4E5E6E))
			for k in 3:
				m.box(Vector3(0, 1.55 + k * 0.08, 0.105), Vector3(0.5, 0.02, 0.02), c(0x2E3A46))
			m.box(Vector3(0, 0.95, 0.105), Vector3(0.02, 1.8, 0.02), c(0x2E3A46))
		_:
			m.box(Vector3(0, 0.65, 0), Vector3(0.92, 1.3, 0.06), c(0x8A8E94))
			for s in 3:
				var sy2 := 0.12 + s * 0.45
				m.box(Vector3(0, sy2, 0), Vector3(0.92, 0.04, 0.84), c(0xA8ACB2))
				for b in 8:
					if (hv >> (b + s)) & 3 == 0:
						continue
					var side := -1.0 if b < 4 else 1.0
					var bc2 := c([0xD04838, 0xE8C040, 0x4A8AD0, 0xE8E8E0, 0x5AA050][(hv + b + s * 3) % 5])
					m.box(Vector3(-0.33 + (b % 4) * 0.22, sy2 + 0.12, side * 0.22), Vector3(0.16, 0.2, 0.18), bc2)
	# обысканная пустая мебель — тёмная метка сверху
	if w.containers.has(w.idx(x, y)) and w.containers[w.idx(x, y)].is_empty():
		m.box(Vector3(0, 0.02, 0.36), Vector3(0.5, 0.01, 0.1), c(0x202020))
	m.identity()

# ================== УЛИЦА ==================
func _tree(m: Mesher, x: int, y: int) -> void:
	var hv := hash2(x, y)
	var cx := x + 0.5
	var cz := y + 0.5
	m.aabb(cx - 0.1, 0, cz - 0.1, cx + 0.1, 1.5, cz + 0.1, c(0x5A4030))
	var autumn := hv % 7 == 0
	var c1 := c(0xB8682C, 0) if autumn else c([0x3E6A32, 0x46723A, 0x365E2C][hv % 3], 0)
	var c2 := c(0xD08A3A, 0) if autumn else Color(minf(c1.r * 1.18, 1), minf(c1.g * 1.18, 1), minf(c1.b * 1.18, 1), 0)
	var s := 0.9 + (hv % 5) * 0.08
	m.blob(Vector3(cx, 1.85 * s, cz), Vector3(0.95, 0.75, 0.95) * s, c1, hv)
	m.blob(Vector3(cx + 0.25, 2.4 * s, cz - 0.15), Vector3(0.62, 0.55, 0.62) * s, c2, hv + 1)
	m.blob(Vector3(cx - 0.3, 2.25 * s, cz + 0.25), Vector3(0.55, 0.5, 0.55) * s, c1, hv + 2)

func _car(m: Mesher, x: int, y: int, horiz: bool) -> void:
	var hv := hash2(x, y)
	var col := c(CAR_COLS[hv % CAR_COLS.size()])
	m.identity()
	if horiz:
		m.translate(Vector3(x + 1.0, 0, y + 0.5))
	else:
		m.translate(Vector3(x + 0.5, 0, y + 1.0))
		m.rot_y(90)
	if hv % 2 == 0:
		m.rot_y(180)
	m.rot_y((hv >> 5) % 9 - 4)
	var dark := c(0x161616)
	for sx in [-0.58, 0.58]:
		for sz in [-0.4, 0.4]:
			m.box(Vector3(sx, 0.17, sz), Vector3(0.34, 0.34, 0.12), dark)
	m.box(Vector3(0, 0.45, 0), Vector3(1.84, 0.42, 0.84), col)
	m.box(Vector3(0.86, 0.36, 0), Vector3(0.14, 0.18, 0.86), c(0x3A3A3C))
	m.box(Vector3(-0.86, 0.36, 0), Vector3(0.14, 0.18, 0.86), c(0x3A3A3C))
	var glass := c(0x1A2228 if hv % 5 == 0 else 0x34495A)
	m.box(Vector3(-0.12, 0.86, 0), Vector3(1.0, 0.42, 0.74), glass)
	m.box(Vector3(-0.12, 1.08, 0), Vector3(0.86, 0.04, 0.7), col)
	m.box(Vector3(-0.12, 0.86, 0), Vector3(0.08, 0.42, 0.76), col)
	m.box(Vector3(0.93, 0.52, 0.28), Vector3(0.03, 0.1, 0.16), c(0xF4ECC4, 0.5))
	m.box(Vector3(0.93, 0.52, -0.28), Vector3(0.03, 0.1, 0.16), c(0xF4ECC4, 0.5))
	m.box(Vector3(-0.93, 0.52, 0.3), Vector3(0.03, 0.1, 0.14), c(0xB02020, 0.5))
	m.box(Vector3(-0.93, 0.52, -0.3), Vector3(0.03, 0.1, 0.14), c(0xB02020, 0.5))
	if hv % 3 == 0:
		m.box(Vector3(0.4, 0.67, 0.1), Vector3(0.3, 0.01, 0.25), dk(col, 0.6))
	m.identity()

func _lamp(m: Mesher, l: Array) -> void:
	var pole := c(0x2E3236)
	m.aabb(l[0] - 0.05, 0.12, l[1] - 0.05, l[0] + 0.05, 3.0, l[1] + 0.05, pole)
	m.aabb(minf(l[0], l[2]) - 0.03, 2.92, minf(l[1], l[3]) - 0.03, maxf(l[0], l[2]) + 0.03, 2.98, maxf(l[1], l[3]) + 0.03, pole)
	m.aabb(l[2] - 0.16, 2.86, l[3] - 0.16, l[2] + 0.16, 2.98, l[3] + 0.16, c(0x3A3E42))
	m.aabb(l[2] - 0.12, 2.82, l[3] - 0.12, l[2] + 0.12, 2.86, l[3] + 0.12, c(0xFFE6A0, 0.5))

# ================== КРЫШИ ==================
func build_roof(m: Mesher, bi: int) -> void:
	m.clear()
	var b: Array = w.buildings[bi]
	if b[2] - b[0] < 2 or b[3] - b[1] < 2:
		return
	var ov := 0.35
	var X0: float = b[0] + 0.5 - ov
	var X1: float = b[2] + 0.5 + ov
	var Z0: float = b[1] + 0.5 - ov
	var Z1: float = b[3] + 0.5 + ov
	var col := c(ROOF_COLS[bi % ROOF_COLS.size()])
	var wall := dk(c(WALL_COLS[bi % WALL_COLS.size()]), 0.82)
	var along_x := (X1 - X0) >= (Z1 - Z0)
	var span := (Z1 - Z0) if along_x else (X1 - X0)
	var e := WH
	var R := WH + minf(1.5, span * 0.32)
	var bands := 4
	if along_x:
		var Zm := (Z0 + Z1) / 2
		for i in bands:
			var t0 := float(i) / bands
			var t1 := float(i + 1) / bands
			var cc := col if i % 2 == 0 else dk(col, 0.9)
			var za := Z0 + (Zm - Z0) * t0
			var zb := Z0 + (Zm - Z0) * t1
			var ya := e + (R - e) * t0
			var yb := e + (R - e) * t1
			m.quad(Vector3(X0, ya, za), Vector3(X0, yb, zb), Vector3(X1, yb, zb), Vector3(X1, ya, za), cc)
			var zc := Z1 - (Z1 - Zm) * t0
			var zd := Z1 - (Z1 - Zm) * t1
			m.quad(Vector3(X0, yb, zd), Vector3(X0, ya, zc), Vector3(X1, ya, zc), Vector3(X1, yb, zd), dk(cc, 0.82))
		m.tri(Vector3(X0, e, Z0), Vector3(X0, e, Z1), Vector3(X0, R, Zm), wall)
		m.tri(Vector3(X1, e, Z1), Vector3(X1, e, Z0), Vector3(X1, R, Zm), wall)
		m.aabb(X0, R - 0.03, Zm - 0.06, X1, R + 0.05, Zm + 0.06, dk(col, 0.6))
	else:
		var Xm := (X0 + X1) / 2
		for i in bands:
			var t0 := float(i) / bands
			var t1 := float(i + 1) / bands
			var cc := col if i % 2 == 0 else dk(col, 0.9)
			var xa := X0 + (Xm - X0) * t0
			var xb := X0 + (Xm - X0) * t1
			var ya := e + (R - e) * t0
			var yb := e + (R - e) * t1
			m.quad(Vector3(xa, ya, Z1), Vector3(xb, yb, Z1), Vector3(xb, yb, Z0), Vector3(xa, ya, Z0), cc)
			var xc := X1 - (X1 - Xm) * t0
			var xd := X1 - (X1 - Xm) * t1
			m.quad(Vector3(xd, yb, Z1), Vector3(xc, ya, Z1), Vector3(xc, ya, Z0), Vector3(xd, yb, Z0), dk(cc, 0.82))
		m.tri(Vector3(X1, e, Z0), Vector3(X0, e, Z0), Vector3(Xm, R, Z0), wall)
		m.tri(Vector3(X0, e, Z1), Vector3(X1, e, Z1), Vector3(Xm, R, Z1), wall)
		m.aabb(Xm - 0.06, R - 0.03, Z0, Xm + 0.06, R + 0.05, Z1, dk(col, 0.6))
	var hv := hash2(b[0], b[1])
	var px := X0 + (X1 - X0) * (0.25 + (hv % 40) / 100.0)
	var pz := Z0 + (Z1 - Z0) * (0.3 + (hv % 30) / 100.0)
	m.aabb(px - 0.2, e, pz - 0.2, px + 0.2, R + 0.45, pz + 0.2, c(0x7A4434), c(0x2A1A14))

## Дверь: меш вокруг петли, лицом вдоль X
static func build_door(m: Mesher) -> void:
	m.clear()
	var wood := c(0xA0703E, 0)
	var d := c(0x6E4A2A, 0)
	m.box(Vector3(0.42, 0.97, 0), Vector3(0.84, 1.94, 0.06), wood)
	for z in [0.032, -0.032]:
		m.box(Vector3(0.42, 1.42, z), Vector3(0.6, 0.62, 0.01), d)
		m.box(Vector3(0.42, 0.52, z), Vector3(0.6, 0.62, 0.01), d)
	m.box(Vector3(0.72, 1.0, 0.05), Vector3(0.05, 0.05, 0.06), c(0xE0C050, 0))
	m.box(Vector3(0.72, 1.0, -0.05), Vector3(0.05, 0.05, 0.06), c(0xE0C050, 0))
