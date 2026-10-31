class_name Icons
extends RefCounted
## Иконки предметов и пиктограммы интерфейса — рисуются векторно.

const OUT := Color(0.17, 0.17, 0.2)
static var _sb := StyleBoxFlat.new()

static func _rr(ci: CanvasItem, r: Rect2, rad: float, col: Color, outline: bool = true) -> void:
	_sb.bg_color = col
	_sb.set_corner_radius_all(int(rad))
	_sb.border_width_left = 4 if outline else 0
	_sb.border_width_right = 4 if outline else 0
	_sb.border_width_top = 4 if outline else 0
	_sb.border_width_bottom = 4 if outline else 0
	_sb.border_color = OUT
	_sb.draw(ci.get_canvas_item(), r)

static func _poly(ci: CanvasItem, pts: Array, col: Color) -> void:
	var p := PackedVector2Array(pts)
	ci.draw_colored_polygon(p, col)
	p.append(p[0])
	ci.draw_polyline(p, OUT, 4.0, true)

static func _circ(ci: CanvasItem, c: Vector2, r: float, col: Color, outline: bool = true) -> void:
	ci.draw_circle(c, r, col)
	if outline:
		ci.draw_arc(c, r, 0, TAU, 32, OUT, 4.0, true)

static func _stroke(ci: CanvasItem, a: Vector2, b: Vector2, w: float, col: Color) -> void:
	ci.draw_line(a, b, OUT, w + 6.0, true)
	ci.draw_line(a, b, col, w, true)

## Иконка предмета с центром c и размером size
static func item(ci: CanvasItem, id: String, c: Vector2, size: float) -> void:
	var k := size / 96.0
	ci.draw_set_transform(c - Vector2(48, 48) * k, 0.0, Vector2(k, k))
	match id:
		"canned":
			_rr(ci, Rect2(26, 22, 44, 58), 6, Color8(0xB8, 0xC0, 0xC8))
			_rr(ci, Rect2(26, 38, 44, 26), 2, Color8(0xD0, 0x48, 0x38))
			ci.draw_circle(Vector2(48, 51), 7, Color8(0xF0, 0xE0, 0xC0))
		"chips":
			_poly(ci, [Vector2(28, 16), Vector2(68, 16), Vector2(74, 82), Vector2(22, 82)], Color8(0xE8, 0xC0, 0x30))
			_rr(ci, Rect2(26, 40, 44, 16), 3, Color8(0xD0, 0x3A, 0x2A))
		"bread":
			_rr(ci, Rect2(14, 34, 68, 36), 18, Color8(0xC8, 0x8A, 0x44))
			for i in 3:
				ci.draw_line(Vector2(30 + i * 16, 40), Vector2(38 + i * 16, 56), Color8(0x8A, 0x5A, 0x28), 4.0)
		"apple":
			_circ(ci, Vector2(48, 54), 26, Color8(0xD4, 0x30, 0x30))
			ci.draw_circle(Vector2(38, 44), 7, Color(1, 1, 1, 0.4))
			_stroke(ci, Vector2(48, 30), Vector2(52, 18), 4, Color8(0x6A, 0x40, 0x20))
			_poly(ci, [Vector2(54, 22), Vector2(70, 14), Vector2(66, 28)], Color8(0x4C, 0xA0, 0x40))
		"water":
			_rr(ci, Rect2(40, 8, 16, 12), 3, Color8(0x28, 0x58, 0xA8))
			_poly(ci, [Vector2(40, 20), Vector2(56, 20), Vector2(64, 32), Vector2(64, 86), Vector2(32, 86), Vector2(32, 32)], Color8(0x9C, 0xD4, 0xF4))
			_rr(ci, Rect2(32, 46, 32, 18), 2, Color8(0x3C, 0x78, 0xC8))
		"soda":
			_rr(ci, Rect2(30, 18, 36, 64), 8, Color8(0xD8, 0x3A, 0x3A))
			ci.draw_polyline(PackedVector2Array([Vector2(32, 56), Vector2(42, 46), Vector2(54, 58), Vector2(64, 50)]), Color.WHITE, 5.0, true)
			_rr(ci, Rect2(34, 12, 28, 8), 3, Color8(0xC8, 0xC8, 0xC8))
		"bandage":
			_rr(ci, Rect2(50, 52, 36, 14), 3, Color8(0xF4, 0xF4, 0xF4))
			_circ(ci, Vector2(42, 48), 26, Color8(0xF4, 0xF4, 0xF4))
			_circ(ci, Vector2(42, 48), 9, Color8(0xD8, 0xD8, 0xD8))
			ci.draw_arc(Vector2(42, 48), 17, 0, TAU, 24, Color8(0xD8, 0xD8, 0xD8), 2.5)
		"rag":
			_poly(ci, [Vector2(16, 30), Vector2(40, 22), Vector2(62, 32), Vector2(82, 24), Vector2(78, 70), Vector2(54, 76), Vector2(34, 68), Vector2(18, 74)], Color8(0xB8, 0xA8, 0x88))
			ci.draw_line(Vector2(30, 40), Vector2(66, 44), Color8(0x8A, 0x7A, 0x5C), 3.0)
			ci.draw_line(Vector2(28, 56), Vector2(64, 60), Color8(0x8A, 0x7A, 0x5C), 3.0)
		"disinfectant":
			_rr(ci, Rect2(40, 10, 16, 14), 3, Color8(0xF0, 0xF0, 0xF0))
			_rr(ci, Rect2(28, 24, 40, 62), 8, Color8(0x40, 0xA8, 0x60))
			ci.draw_rect(Rect2(44, 40, 8, 30), Color.WHITE)
			ci.draw_rect(Rect2(33, 51, 30, 8), Color.WHITE)
		"tweezers":
			_stroke(ci, Vector2(30, 14), Vector2(50, 84), 6, Color8(0xC0, 0xC4, 0xCC))
			_stroke(ci, Vector2(66, 14), Vector2(50, 84), 6, Color8(0xC0, 0xC4, 0xCC))
		"pills":
			_rr(ci, Rect2(30, 30, 36, 54), 6, Color8(0xE8, 0x90, 0x30))
			_rr(ci, Rect2(26, 16, 44, 16), 4, Color8(0xF4, 0xF4, 0xF4))
			_rr(ci, Rect2(34, 46, 28, 20), 2, Color8(0xF4, 0xF4, 0xF4))
		"knife":
			_poly(ci, [Vector2(22, 74), Vector2(64, 24), Vector2(74, 18), Vector2(70, 34), Vector2(32, 80)], Color8(0xD8, 0xDC, 0xE4))
			_stroke(ci, Vector2(20, 76), Vector2(30, 66), 9, Color8(0x2A, 0x2A, 0x2A))
		"bat":
			_stroke(ci, Vector2(66, 22), Vector2(44, 54), 16, Color8(0xC0, 0x8A, 0x4C))
			_stroke(ci, Vector2(44, 54), Vector2(22, 82), 8, Color8(0xC0, 0x8A, 0x4C))
			_stroke(ci, Vector2(30, 72), Vector2(22, 82), 9, Color8(0x2A, 0x2A, 0x2A))
		"crowbar":
			_stroke(ci, Vector2(26, 84), Vector2(66, 22), 8, Color8(0x5A, 0x5A, 0x66))
			_stroke(ci, Vector2(66, 22), Vector2(76, 26), 8, Color8(0x5A, 0x5A, 0x66))
			_stroke(ci, Vector2(26, 84), Vector2(34, 72), 8, Color8(0xC0, 0x30, 0x30))
		"hammer":
			_stroke(ci, Vector2(30, 84), Vector2(54, 34), 9, Color8(0xB0, 0x7A, 0x44))
			_poly(ci, [Vector2(38, 22), Vector2(76, 34), Vector2(72, 46), Vector2(34, 34)], Color8(0x8A, 0x8E, 0x98))
		"pistol":
			_poly(ci, [Vector2(14, 30), Vector2(82, 30), Vector2(82, 46), Vector2(46, 46), Vector2(40, 76), Vector2(24, 76), Vector2(28, 46), Vector2(14, 46)], Color8(0x3A, 0x3A, 0x40))
			ci.draw_line(Vector2(20, 36), Vector2(76, 36), Color8(0x5A, 0x5A, 0x62), 3.0)
		"ammo":
			_rr(ci, Rect2(16, 48, 64, 34), 4, Color8(0x6A, 0x7A, 0x3A))
			for i in 4:
				var x := 26.0 + i * 14
				_rr(ci, Rect2(x - 5, 24, 10, 26), 2, Color8(0xD8, 0xA8, 0x30))
				ci.draw_circle(Vector2(x, 24), 5, Color8(0xB8, 0x70, 0x30))
		"planks":
			ci.draw_set_transform(c - Vector2(48, 48) * k, 0.0, Vector2(k, k))
			_poly(ci, [Vector2(10, 44), Vector2(82, 18), Vector2(86, 32), Vector2(14, 58)], Color8(0xB0, 0x7A, 0x44))
			_poly(ci, [Vector2(14, 66), Vector2(86, 40), Vector2(90, 54), Vector2(18, 80)], Color8(0xC0, 0x8A, 0x54))
		"cap":
			var cc: Color = Items.def(id)["color"]
			_poly(ci, [Vector2(20, 58), Vector2(22, 38), Vector2(36, 24), Vector2(60, 24), Vector2(74, 38), Vector2(76, 58)], cc)
			_poly(ci, [Vector2(52, 58), Vector2(90, 56), Vector2(88, 66), Vector2(50, 66)], cc.darkened(0.3))
			ci.draw_circle(Vector2(48, 24), 4, cc.darkened(0.3))
		"beanie":
			var bc: Color = Items.def(id)["color"]
			_poly(ci, [Vector2(20, 64), Vector2(22, 40), Vector2(34, 24), Vector2(62, 24), Vector2(74, 40), Vector2(76, 64)], bc)
			_rr(ci, Rect2(16, 56, 64, 18), 6, bc.darkened(0.3))
			_circ(ci, Vector2(48, 18), 9, bc.lightened(0.2))
		"helmet":
			_circ(ci, Vector2(48, 50), 32, Color8(0x2A, 0x2A, 0x2E))
			_rr(ci, Rect2(26, 44, 50, 18), 6, Color8(0x6A, 0x8A, 0xA8))
			_rr(ci, Rect2(44, 14, 8, 26), 3, Color8(0xC0, 0x30, 0x30), false)
		"hoodie", "leather", "mil_jacket":
			var jc: Color = Items.def(id)["color"]
			_poly(ci, [Vector2(30, 18), Vector2(66, 18), Vector2(88, 40), Vector2(80, 50), Vector2(70, 42), Vector2(70, 84), Vector2(26, 84), Vector2(26, 42), Vector2(16, 50), Vector2(8, 40)], jc)
			ci.draw_line(Vector2(48, 24), Vector2(48, 82), jc.darkened(0.35) if id != "leather" else Color8(0xC8, 0xC8, 0xC0), 3.0)
			if id == "hoodie":
				_poly(ci, [Vector2(34, 18), Vector2(48, 32), Vector2(62, 18), Vector2(56, 10), Vector2(40, 10)], jc.darkened(0.25))
				_rr(ci, Rect2(34, 60, 28, 14), 3, jc.darkened(0.25), false)
			elif id == "leather":
				_poly(ci, [Vector2(34, 18), Vector2(48, 38), Vector2(40, 40)], jc.darkened(0.4))
				_poly(ci, [Vector2(62, 18), Vector2(48, 38), Vector2(56, 40)], jc.darkened(0.4))
			else:
				_rr(ci, Rect2(30, 36, 14, 12), 2, jc.darkened(0.3), false)
				_rr(ci, Rect2(52, 36, 14, 12), 2, jc.darkened(0.3), false)
		"jeans", "cargo":
			var pc: Color = Items.def(id)["color"]
			_poly(ci, [Vector2(26, 12), Vector2(70, 12), Vector2(76, 86), Vector2(54, 86), Vector2(48, 40), Vector2(42, 86), Vector2(20, 86)], pc)
			ci.draw_line(Vector2(26, 20), Vector2(70, 20), pc.darkened(0.4), 3.0)
			if id == "cargo":
				_rr(ci, Rect2(14, 44, 12, 16), 2, pc.darkened(0.3), false)
				_rr(ci, Rect2(70, 44, 12, 16), 2, pc.darkened(0.3), false)
		"boots":
			var tc: Color = Items.def(id)["color"]
			_poly(ci, [Vector2(30, 14), Vector2(54, 14), Vector2(56, 58), Vector2(84, 64), Vector2(86, 80), Vector2(28, 80)], tc)
			_rr(ci, Rect2(26, 78, 62, 8), 2, Color8(0x1A, 0x16, 0x12), false)
			for i in 3:
				ci.draw_line(Vector2(36, 26 + i * 10), Vector2(50, 26 + i * 10), tc.lightened(0.35), 2.5)
		"gloves":
			var gc: Color = Items.def(id)["color"]
			_poly(ci, [Vector2(28, 86), Vector2(28, 44), Vector2(18, 34), Vector2(26, 28), Vector2(34, 36), Vector2(34, 14), Vector2(42, 12), Vector2(44, 34), Vector2(46, 10), Vector2(54, 10), Vector2(56, 34), Vector2(58, 14), Vector2(66, 16), Vector2(66, 40), Vector2(70, 22), Vector2(78, 24), Vector2(72, 60), Vector2(68, 86)], gc)
			_rr(ci, Rect2(26, 72, 44, 14), 2, gc.darkened(0.3), false)
		"bp_school", "bp_military", "bp_hiking":
			var pk: Color = Items.def(id)["color"]
			var tall := 0 if id != "bp_hiking" else 8
			ci.draw_arc(Vector2(48, 20 - tall), 10, PI, TAU, 12, OUT, 5.0, true)
			_rr(ci, Rect2(20, 18 - tall, 56, 68 + tall), 14 if id != "bp_military" else 6, pk)
			_rr(ci, Rect2(18, 18 - tall, 60, 20), 8, pk.darkened(0.3))
			_rr(ci, Rect2(28, 52, 40, 26), 6, pk.darkened(0.2) if id != "bp_school" else Color8(0x55, 0x5A, 0x60))
			ci.draw_line(Vector2(32, 56), Vector2(64, 56), Color8(0xC8, 0xC8, 0xC0), 2.0)
			if id == "bp_hiking":
				_rr(ci, Rect2(14, 2, 68, 14), 7, Color8(0x3E, 0x8A, 0x4A))
			if id == "bp_military":
				for i in 3:
					ci.draw_line(Vector2(24, 44 + i * 6), Vector2(72, 44 + i * 6), Color8(0x26, 0x26, 0x24), 2.0)
		_:
			_circ(ci, Vector2(48, 48), 26, Items.def(id)["color"])
	ci.draw_set_transform(Vector2.ZERO, 0.0, Vector2.ONE)

## Белые пиктограммы интерфейса
static func glyph(ci: CanvasItem, kind: String, c: Vector2, r: float, col: Color = Color.WHITE) -> void:
	var w := r * 0.16
	match kind:
		"hunger":
			ci.draw_line(c + Vector2(-0.35, -0.6) * r, c + Vector2(-0.35, 0.6) * r, col, w, true)
			ci.draw_line(c + Vector2(-0.55, -0.6) * r, c + Vector2(-0.55, -0.15) * r, col, w, true)
			ci.draw_line(c + Vector2(-0.15, -0.6) * r, c + Vector2(-0.15, -0.15) * r, col, w, true)
			ci.draw_colored_polygon(PackedVector2Array([c + Vector2(0.3, 0.6) * r, c + Vector2(0.3, -0.6) * r, c + Vector2(0.62, -0.2) * r, c + Vector2(0.45, 0.05) * r, c + Vector2(0.42, 0.6) * r]), col)
		"drop", "bleed":
			var pts := PackedVector2Array()
			for i in 17:
				var a := PI * i / 16.0
				pts.append(c + Vector2(cos(a) * 0.5, 0.2 + sin(a) * 0.45) * r)
			pts.append(c + Vector2(0, -0.7) * r)
			ci.draw_colored_polygon(pts, col)
			if kind == "bleed":
				ci.draw_circle(c + Vector2(0.62, 0.55) * r, r * 0.12, col)
				ci.draw_circle(c + Vector2(-0.66, 0.4) * r, r * 0.09, col)
		"fever":
			ci.draw_line(c + Vector2(0, -0.6) * r, c + Vector2(0, 0.2) * r, col, w, true)
			ci.draw_circle(c + Vector2(0, 0.38) * r, r * 0.24, col)
			ci.draw_line(c + Vector2(0.25, -0.4) * r, c + Vector2(0.45, -0.4) * r, col, w, true)
			ci.draw_line(c + Vector2(0.25, -0.1) * r, c + Vector2(0.45, -0.1) * r, col, w, true)
		"wound":
			ci.draw_line(c + Vector2(-0.5, 0.5) * r, c + Vector2(0.5, -0.5) * r, col, r * 0.3, true)
			ci.draw_circle(c, r * 0.14, Color(0.82, 0.25, 0.25))
		"glass":
			ci.draw_colored_polygon(PackedVector2Array([c + Vector2(-0.2, -0.65) * r, c + Vector2(0.45, 0.5) * r, c + Vector2(-0.45, 0.35) * r]), col)
		"weight":
			ci.draw_arc(c + Vector2(0, -0.42) * r, r * 0.2, 0, TAU, 16, col, w, true)
			ci.draw_colored_polygon(PackedVector2Array([c + Vector2(-0.35, -0.2) * r, c + Vector2(0.35, -0.2) * r, c + Vector2(0.55, 0.6) * r, c + Vector2(-0.55, 0.6) * r]), col)
		"tired":
			ci.draw_colored_polygon(PackedVector2Array([c + Vector2(0.15, -0.7) * r, c + Vector2(-0.4, 0.1) * r, c + Vector2(0, 0.1) * r, c + Vector2(-0.15, 0.7) * r, c + Vector2(0.4, -0.1) * r, c + Vector2(0, -0.1) * r]), col)
		"eye":
			var e := PackedVector2Array()
			for i in 17:
				var a := TAU * i / 16.0
				e.append(c + Vector2(cos(a) * 0.65, sin(a) * 0.32) * r)
			ci.draw_polyline(e, col, w, true)
			ci.draw_circle(c, r * 0.18, col)
		"panic":
			ci.draw_line(c + Vector2(0, -0.6) * r, c + Vector2(0, 0.15) * r, col, r * 0.24, true)
			ci.draw_circle(c + Vector2(0, 0.5) * r, r * 0.13, col)
		"hand":
			Icons._sb.bg_color = col
			Icons._sb.set_corner_radius_all(int(r * 0.2))
			Icons._sb.border_width_left = 0
			Icons._sb.border_width_right = 0
			Icons._sb.border_width_top = 0
			Icons._sb.border_width_bottom = 0
			Icons._sb.draw(ci.get_canvas_item(), Rect2(c + Vector2(-0.42, -0.1) * r, Vector2(0.84, 0.75) * r))
			for i in 4:
				var fx := -0.32 + i * 0.21
				var top := -0.45 if (i == 0 or i == 3) else -0.6
				ci.draw_line(c + Vector2(fx, top) * r, c + Vector2(fx, 0.0) * r, col, r * 0.16, true)
		"run":
			ci.draw_circle(c + Vector2(0.15, -0.55) * r, r * 0.15, col)
			ci.draw_polyline(PackedVector2Array([c + Vector2(-0.45, -0.15) * r, c + Vector2(-0.05, -0.3) * r, c + Vector2(0.35, -0.05) * r]), col, w, true)
			ci.draw_polyline(PackedVector2Array([c + Vector2(-0.5, 0.65) * r, c + Vector2(-0.15, 0.25) * r, c + Vector2(-0.05, -0.3) * r]), col, w, true)
			ci.draw_polyline(PackedVector2Array([c + Vector2(-0.15, 0.25) * r, c + Vector2(0.25, 0.3) * r, c + Vector2(0.35, 0.7) * r]), col, w, true)
		"bag":
			Icons._sb.bg_color = col
			Icons._sb.set_corner_radius_all(int(r * 0.2))
			Icons._sb.border_width_left = 0
			Icons._sb.border_width_right = 0
			Icons._sb.border_width_top = 0
			Icons._sb.border_width_bottom = 0
			Icons._sb.draw(ci.get_canvas_item(), Rect2(c + Vector2(-0.5, -0.3) * r, Vector2(1.0, 0.95) * r))
			ci.draw_arc(c + Vector2(0, -0.35) * r, r * 0.22, PI, TAU, 12, col, w, true)
		"gear":
			ci.draw_colored_polygon(PackedVector2Array([c + Vector2(-0.25, -0.6) * r, c + Vector2(0.25, -0.6) * r, c + Vector2(0.7, -0.25) * r, c + Vector2(0.5, 0.0) * r, c + Vector2(0.35, -0.12) * r, c + Vector2(0.35, 0.65) * r, c + Vector2(-0.35, 0.65) * r, c + Vector2(-0.35, -0.12) * r, c + Vector2(-0.5, 0.0) * r, c + Vector2(-0.7, -0.25) * r]), col)
			ci.draw_arc(c + Vector2(0, -0.6) * r, r * 0.16, 0, PI, 8, Color(0.1, 0.1, 0.12), r * 0.1, true)
		"body":
			ci.draw_circle(c + Vector2(0, -0.5) * r, r * 0.2, col)
			ci.draw_rect(Rect2(c + Vector2(-0.25, -0.25) * r, Vector2(0.5, 0.5) * r), col)
			ci.draw_line(c + Vector2(-0.38, -0.2) * r, c + Vector2(-0.45, 0.2) * r, col, w, true)
			ci.draw_line(c + Vector2(0.38, -0.2) * r, c + Vector2(0.45, 0.2) * r, col, w, true)
			ci.draw_line(c + Vector2(-0.13, 0.3) * r, c + Vector2(-0.15, 0.7) * r, col, w, true)
			ci.draw_line(c + Vector2(0.13, 0.3) * r, c + Vector2(0.15, 0.7) * r, col, w, true)
		"pause":
			ci.draw_rect(Rect2(c + Vector2(-0.32, -0.42) * r, Vector2(0.22, 0.84) * r), col)
			ci.draw_rect(Rect2(c + Vector2(0.1, -0.42) * r, Vector2(0.22, 0.84) * r), col)
		"fist":
			ci.draw_rect(Rect2(c + Vector2(-0.45, -0.35) * r, Vector2(0.9, 0.8) * r), col)
			for i in range(1, 4):
				var fx2 := -0.45 + i * 0.225
				ci.draw_line(c + Vector2(fx2, -0.35) * r, c + Vector2(fx2, 0.0) * r, Color(0, 0, 0, 0.4), r * 0.06)
		"door":
			ci.draw_rect(Rect2(c + Vector2(-0.38, -0.65) * r, Vector2(0.76, 1.3) * r), col, false, w)
			ci.draw_circle(c + Vector2(0.18, 0) * r, r * 0.08, col)
		"search":
			ci.draw_arc(c + Vector2(-0.12, -0.12) * r, r * 0.38, 0, TAU, 24, col, w, true)
			ci.draw_line(c + Vector2(0.16, 0.16) * r, c + Vector2(0.55, 0.55) * r, col, r * 0.22, true)
		"sun":
			ci.draw_circle(c, r * 0.35, col)
			for i in 8:
				var a := i * TAU / 8.0
				ci.draw_line(c + Vector2(cos(a), sin(a)) * r * 0.5, c + Vector2(cos(a), sin(a)) * r * 0.72, col, w, true)
		"moon":
			ci.draw_circle(c, r * 0.55, col)
			ci.draw_circle(c + Vector2(0.3, -0.18) * r, r * 0.45, Color(0.09, 0.11, 0.13))
