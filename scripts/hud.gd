class_name Hud
extends Control
## Интерфейс поверх 3D: мудлы, кнопки, джойстик, панели (вещи, лут, тело, пауза).

enum Pnl { NONE, INV, LOOT, PAUSE, BODY }

var screen: GameScreen
var font: Font
var sb := StyleBoxFlat.new()

# ввод
var joy_id := -1
var joy_base := Vector2.ZERO
var joy_pos := Vector2.ZERO
var attack_id := -1
var attack_repeat := 0.0
var cam_id := -1
var cam_last := 0.0
var run_toggle := false

var panel := Pnl.NONE
var loot_cell := -1
var selected := ""
var sel_part := Body.TORSO
var scroll := [0.0, 0.0]
var panel_id := -1
var panel_down := Vector2.ZERO
var panel_last_y := 0.0
var panel_drag := false
var loot_wait := 0.0
var barr_cell := -1
var msgs: Array = []      # [текст, время]

# кнопки: центр и радиус
var b_attack := [Vector2.ZERO, 50.0]
var b_interact := [Vector2.ZERO, 36.0]
var b_run := [Vector2.ZERO, 32.0]
var b_barr := [Vector2.ZERO, 32.0]
var b_inv := [Vector2.ZERO, 24.0]
var b_body := [Vector2.ZERO, 24.0]
var b_pause := [Vector2.ZERO, 24.0]
var treat_btns: Array = []   # [Rect2, что]
var btn_a := Rect2()
var btn_b := Rect2()
var btn_close := Rect2()
var dead_btn := Rect2()
var panel_rect := Rect2()
const ROW_H := 40.0

var vignette: GradientTexture2D
var hurt_tex: GradientTexture2D

func _ready() -> void:
	font = ThemeDB.fallback_font
	mouse_filter = Control.MOUSE_FILTER_IGNORE
	vignette = _radial(Color(0, 0, 0, 0), Color(0, 0, 0, 0.45), 0.55)
	hurt_tex = _radial(Color(0.6, 0, 0, 0), Color(0.65, 0, 0, 0.75), 0.35)

static func _radial(inner: Color, outer: Color, start: float) -> GradientTexture2D:
	var g := Gradient.new()
	g.set_offset(0, 0.0)
	g.set_color(0, inner)
	g.set_offset(1, 1.0)
	g.set_color(1, outer)
	g.add_point(start, inner)
	var t := GradientTexture2D.new()
	t.gradient = g
	t.fill = GradientTexture2D.FILL_RADIAL
	t.fill_from = Vector2(0.5, 0.5)
	t.fill_to = Vector2(1.0, 1.0)
	t.width = 128
	t.height = 128
	return t

func paused() -> bool: return panel == Pnl.PAUSE

func add_msg(s: String) -> void:
	msgs.append([s, 4.0])
	if msgs.size() > 4:
		msgs.pop_front()

func joystick() -> Vector2:
	if joy_id < 0:
		return Vector2.ZERO
	var m := ((joy_pos - joy_base) / 60.0).limit_length(1.0)
	return Vector2.ZERO if m.length() < 0.15 else m

func tick(dt: float) -> void:
	if attack_id >= 0:
		attack_repeat -= dt
		if attack_repeat <= 0.0:
			attack_repeat = 0.12
			screen.act({"a": "attack"})
	var i := msgs.size() - 1
	while i >= 0:
		msgs[i][1] -= dt
		if msgs[i][1] <= 0.0:
			msgs.remove_at(i)
		i -= 1
	var me := screen.me()
	var g := screen.game
	if panel == Pnl.LOOT and me != null:
		if not g.near_cell(me, loot_cell, 2.2) or me.dead:
			panel = Pnl.NONE
		elif not g.world.containers.has(loot_cell) and GameWorld.is_container(g.world.t_at(loot_cell)):
			if me.ta_act == null:
				loot_wait += dt
				if loot_wait > 0.4:
					panel = Pnl.NONE
			else:
				loot_wait = 0.0
		else:
			loot_wait = 0.0

func open_demo_panel(kind: String) -> void:
	var g := screen.game
	match kind:
		"body":
			panel = Pnl.BODY
			sel_part = Body.L_FORE
		"inv":
			panel = Pnl.INV
			selected = "bandage"
		"loot":
			var me := screen.me()
			var c := g.find_interact(me)
			if c >= 0:
				g.world.open_container(c, g.rnd)
				loot_cell = c
				panel = Pnl.LOOT

# ================== РАСКЛАДКА ==================
func _safe() -> Rect2:
	var r := get_viewport_rect()
	var safe := DisplayServer.get_display_safe_area()
	var scr := DisplayServer.screen_get_size()
	if scr.x > 0 and safe.size.x > 0:
		var k := r.size.x / scr.x
		var l := safe.position.x * k
		var rr := (scr.x - safe.end.x) * k
		return Rect2(r.position + Vector2(l, 0), r.size - Vector2(l + rr, 0))
	return r

func _layout() -> void:
	var s := _safe()
	var W := s.end.x
	var H := s.size.y
	b_attack[0] = Vector2(W - 88, H - 84)
	b_interact[0] = Vector2(W - 196, H - 54)
	b_run[0] = Vector2(W - 186, H - 150)
	b_barr[0] = Vector2(W - 80, H - 196)
	b_pause[0] = Vector2(W - 38, 38)
	b_inv[0] = Vector2(W - 96, 38)
	b_body[0] = Vector2(W - 154, 38)

func _hit(b: Array, p: Vector2) -> bool:
	return p.distance_to(b[0]) < b[1] * 1.15

func _release() -> void:
	joy_id = -1
	attack_id = -1
	cam_id = -1

# ================== КАСАНИЯ ==================
func _input(e: InputEvent) -> void:
	if screen == null:
		return
	if e is InputEventScreenTouch:
		var t := e as InputEventScreenTouch
		_touch(0 if t.pressed else 2, t.index, t.position)
	elif e is InputEventScreenDrag:
		var d := e as InputEventScreenDrag
		_touch(1, d.index, d.position)

func _touch(type: int, id: int, p: Vector2) -> void:
	var g := screen.game
	if screen.lost or g == null:
		if type == 2:
			screen.request_exit(false)
		return
	var me := screen.me()
	if me == null:
		return
	if me.dead:
		if type == 2 and dead_btn.has_point(p):
			var all_dead := true
			for pl in g.players:
				if not pl.dead:
					all_dead = false
			screen.request_exit(screen.mode == GameScreen.Mode.SOLO or (screen.mode == GameScreen.Mode.HOST and all_dead))
		return
	if panel != Pnl.NONE:
		_panel_touch(g, me, type, id, p)
		return
	_layout()
	match type:
		0:
			if _hit(b_pause, p):
				panel = Pnl.PAUSE
				_release()
			elif _hit(b_inv, p):
				panel = Pnl.INV
				scroll[0] = 0.0
				_release()
			elif _hit(b_body, p):
				panel = Pnl.BODY
				sel_part = _most_hurt(me)
				_release()
			elif barr_cell >= 0 and _hit(b_barr, p):
				screen.act({"a": "barr", "c": barr_cell})
			elif _hit(b_run, p):
				run_toggle = not run_toggle
			elif _hit(b_interact, p):
				var c := g.find_interact(me)
				if c >= 0:
					screen.act({"a": "interact", "c": c})
					var tt := g.world.t_at(c)
					if GameWorld.is_container(tt) or (g.world.containers.has(c) and not g.world.containers[c].is_empty() and not GameWorld.is_door_like(tt) and not GameWorld.is_window_like(tt)):
						loot_cell = c
						panel = Pnl.LOOT
						scroll = [0.0, 0.0]
						_release()
				else:
					loot_cell = g.world.idx(floori(me.pos.x), floori(me.pos.y))
					panel = Pnl.LOOT
					scroll = [0.0, 0.0]
					_release()
			elif _hit(b_attack, p):
				attack_id = id
				attack_repeat = 0.0
			elif p.x < get_viewport_rect().size.x * 0.55 and joy_id < 0:
				joy_id = id
				joy_base = p
				joy_pos = p
			elif cam_id < 0:
				cam_id = id
				cam_last = p.x
		1:
			if id == joy_id:
				joy_pos = p
			if id == cam_id:
				screen.view.cam_yaw = fmod(screen.view.cam_yaw - (p.x - cam_last) * 0.3, 360.0)
				cam_last = p.x
		2:
			if id == joy_id:
				joy_id = -1
			if id == attack_id:
				attack_id = -1
			if id == cam_id:
				cam_id = -1

func _panel_touch(g: GameState, me: GameState.Player, type: int, id: int, p: Vector2) -> void:
	_layout_panel()
	match type:
		0:
			if panel_id < 0:
				panel_id = id
				panel_down = p
				panel_last_y = p.y
				panel_drag = false
		1:
			if id == panel_id:
				if p.distance_to(panel_down) > 10.0:
					panel_drag = true
				if panel_drag:
					var col := 1 if (panel == Pnl.LOOT and p.x > panel_rect.get_center().x) else 0
					scroll[col] = maxf(0.0, scroll[col] - (p.y - panel_last_y))
				panel_last_y = p.y
		2:
			if id == panel_id:
				panel_id = -1
				if not panel_drag:
					_panel_tap(g, me, p)

func _panel_tap(g: GameState, me: GameState.Player, p: Vector2) -> void:
	if btn_close.has_point(p):
		panel = Pnl.NONE
		return
	match panel:
		Pnl.PAUSE:
			if btn_a.has_point(p):
				panel = Pnl.NONE
			elif btn_b.has_point(p):
				screen.request_exit(false)
		Pnl.INV:
			var col := _col_rect(0, 1)
			if col.has_point(p):
				var i := _row_at(col, scroll[0], p.y, me.inv.size())
				if i >= 0:
					selected = me.inv[i]["id"]
			if selected != "" and me.count(selected) > 0:
				if btn_a.has_point(p):
					screen.act({"a": "use", "id": selected})
				elif btn_b.has_point(p):
					screen.act({"a": "drop", "id": selected})
					selected = ""
		Pnl.LOOT:
			var left := _col_rect(0, 2)
			var right := _col_rect(1, 2)
			var cont: Array = g.world.containers.get(loot_cell, [])
			if left.has_point(p):
				var i := _row_at(left, scroll[0], p.y, cont.size())
				if i >= 0:
					screen.act({"a": "take", "c": loot_cell, "id": cont[i]["id"]})
			elif right.has_point(p):
				var j := _row_at(right, scroll[1], p.y, me.inv.size())
				if j >= 0:
					screen.act({"a": "put", "c": loot_cell, "id": me.inv[j]["id"]})
		Pnl.BODY:
			for tb in treat_btns:
				if tb[0].has_point(p):
					screen.act({"a": "treat", "part": sel_part, "w": tb[1]})
					return
			var box := _body_box()
			for i in Body.COUNT:
				if _part_rect(box, i).has_point(p):
					sel_part = i
					return

func _most_hurt(me: GameState.Player) -> int:
	var best := sel_part
	var score := -1
	for i in Body.COUNT:
		var bp: Body.Part = me.body.parts[i]
		var sc := (100 if bp.bleeding() else 0) + (50 if bp.has_glass() else 0) + (40 if bp.infected() else 0) + bp.wounds.size() * 10 + (1 if bp.bruise > 0.0 else 0)
		if sc > score and sc > 0:
			score = sc
			best = i
	return best

# ================== РИСОВАНИЕ: помощники ==================
func _rr(r: Rect2, rad: float, col: Color) -> void:
	sb.bg_color = col
	sb.set_corner_radius_all(int(rad))
	sb.border_width_left = 0
	sb.border_width_right = 0
	sb.border_width_top = 0
	sb.border_width_bottom = 0
	sb.draw(get_canvas_item(), r)

func _text(pos: Vector2, s: String, size: int, col: Color = Color.WHITE, align: int = HORIZONTAL_ALIGNMENT_LEFT, shadow: bool = false) -> void:
	var w := font.get_string_size(s, HORIZONTAL_ALIGNMENT_LEFT, -1, size).x
	var x := pos.x
	if align == HORIZONTAL_ALIGNMENT_CENTER:
		x -= w / 2.0
	elif align == HORIZONTAL_ALIGNMENT_RIGHT:
		x -= w
	if shadow:
		draw_string(font, Vector2(x + 1, pos.y + 1.5), s, HORIZONTAL_ALIGNMENT_LEFT, -1, size, Color(0, 0, 0, col.a * 0.8))
	draw_string(font, Vector2(x, pos.y), s, HORIZONTAL_ALIGNMENT_LEFT, -1, size, col)

func _tw(s: String, size: int) -> float:
	return font.get_string_size(s, HORIZONTAL_ALIGNMENT_LEFT, -1, size).x

# ================== РИСОВАНИЕ ==================
func _draw() -> void:
	if screen == null:
		return
	var g := screen.game
	var vr := get_viewport_rect()
	if g == null:
		draw_rect(vr, Color(0.06, 0.07, 0.08))
		_text(vr.get_center(), "Соединение потеряно — коснись экрана" if screen.lost else (Net.status if Net.status != "" else "Загрузка…"), 18, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER)
		return
	var me := screen.me()
	if me == null:
		return
	_overlays(g, me, vr)
	_hud(g, me)
	match panel:
		Pnl.INV: _draw_inv(me)
		Pnl.LOOT: _draw_loot(g, me)
		Pnl.PAUSE: _draw_pause()
		Pnl.BODY: _draw_body(me)
	if me.dead:
		_draw_dead(g, me, vr)
	if screen.lost:
		draw_rect(vr, Color(0, 0, 0, 0.8))
		_text(vr.get_center(), "Соединение с хостом потеряно", 20, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER)
		_text(vr.get_center() + Vector2(0, 30), "Коснись, чтобы выйти в меню", 14, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER)

func _overlays(g: GameState, me: GameState.Player, vr: Rect2) -> void:
	for p in g.players:
		if p.dead or not p.connected or p.ta_act == null:
			continue
		var s: Vector2 = screen.view.project(Vector3(p.rpos.x, 2.2, p.rpos.y))
		var r := 16.0
		draw_circle(s, r + 4, Color(0.06, 0.07, 0.08, 0.7))
		draw_arc(s, r, -PI / 2, -PI / 2 + TAU * p.ta_progress(), 32, Color(0.94, 0.88, 0.63), 4.0, true)
		_text(s + Vector2(0, -r - 9), p.ta_name, 11, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, true)
	for p in g.players:
		if p.dead or not p.connected or p.id == me.id:
			continue
		var nm: String = p.name if p.name != "" else ("Хост" if p.id == 0 else "Друг")
		var sp: Vector2 = screen.view.project(Vector3(p.rpos.x, 2.05, p.rpos.y))
		_text(sp, nm, 12, Color(0.75, 0.9, 1.0), HORIZONTAL_ALIGNMENT_CENTER, true)
	draw_texture_rect(vignette, vr, false)
	var low := 0.0
	if me.hp < 30.0 and not me.dead:
		low = (0.35 + 0.25 * sin(Time.get_ticks_msec() / 220.0)) * (1.0 - me.hp / 30.0)
	var hf := maxf(screen.view.hurt_flash, low)
	if hf > 0.01:
		draw_texture_rect(hurt_tex, vr, false, Color(1, 1, 1, hf))

class Moodle:
	var g := ""
	var lvl := 0
	var label := ""
	func _init(a: String, b: int, c: String) -> void:
		g = a
		lvl = b
		label = c

func _moodles(g: GameState, me: GameState.Player) -> Array:
	var m: Array = []
	var lv := func(v: float) -> int: return 3 if v >= 85.0 else (2 if v >= 65.0 else (1 if v >= 45.0 else 0))
	if me.bleeding(): m.append(Moodle.new("bleed", 3, "Кровь"))
	var hu: int = lv.call(me.hunger)
	if hu > 0: m.append(Moodle.new("hunger", hu, "Голод"))
	var th: int = lv.call(me.thirst)
	if th > 0: m.append(Moodle.new("drop", th, "Жажда"))
	if me.infection > 420.0: m.append(Moodle.new("fever", 3 if me.infection > 900.0 else 2, "Жар"))
	if me.body.wound_infected(): m.append(Moodle.new("wound", 2, "Нагноение"))
	if me.body.any_glass(): m.append(Moodle.new("glass", 2, "Стекло"))
	if me.weight() > GameState.MAX_WEIGHT: m.append(Moodle.new("weight", 2, "Тяжело"))
	if me.stamina < 25.0: m.append(Moodle.new("tired", 2 if me.stamina < 10.0 else 1, "Устал"))
	for z in g.zombies:
		if z.chase > 0.0 and z.down <= 0.0 and z.rpos.distance_to(me.rpos) < 6.0:
			m.append(Moodle.new("panic", 2, "Паника"))
			break
	if me.sneaking: m.append(Moodle.new("eye", 0, "Тихо"))
	return m

func _hud(g: GameState, me: GameState.Player) -> void:
	_layout()
	var s := _safe()
	var x := s.position.x + 14.0
	var y := 12.0
	var cw := 190.0
	_rr(Rect2(x, y, cw, 44), 12, Color(0.055, 0.07, 0.085, 0.6))
	# сердце
	var hc := Vector2(x + 20, y + 18)
	draw_circle(hc + Vector2(-3.5, -2), 4.5, Color(0.88, 0.28, 0.28))
	draw_circle(hc + Vector2(3.5, -2), 4.5, Color(0.88, 0.28, 0.28))
	draw_colored_polygon(PackedVector2Array([hc + Vector2(-8, -0.5), hc + Vector2(8, -0.5), hc + Vector2(0, 8)]), Color(0.88, 0.28, 0.28))
	var bx := x + 36
	var bw := cw - 48
	_rr(Rect2(bx, y + 12, bw, 10), 5, Color(1, 1, 1, 0.33))
	_rr(Rect2(bx, y + 12, bw * clampf(me.hp / 100.0, 0, 1), 10), 5, Color(0.88, 0.19, 0.19) if me.hp < 30.0 else Color(0.85, 0.35, 0.29))
	_rr(Rect2(bx, y + 29, bw, 4), 2, Color(1, 1, 1, 0.25))
	_rr(Rect2(bx, y + 29, bw * clampf(me.stamina / 100.0, 0, 1), 4), 2, Color(0.91, 0.78, 0.31))
	# мудлы
	var mx := x + 4
	var my := y + 70
	for m in _moodles(g, me):
		var ring: Color = [Color(0.54, 0.56, 0.59), Color(0.91, 0.78, 0.31), Color(0.91, 0.54, 0.19), Color(0.88, 0.23, 0.23)][m.lvl]
		draw_circle(Vector2(mx + 17, my), 17, Color(0.08, 0.09, 0.13, 0.75))
		draw_arc(Vector2(mx + 17, my), 16, 0, TAU, 32, ring, 2.5, true)
		Icons.glyph(self, m.g, Vector2(mx + 17, my), 12, Color(1, 0.31, 0.31) if m.g == "bleed" else Color.WHITE)
		_text(Vector2(mx + 17, my + 30), m.label, 10, Color(1, 1, 1, 0.87), HORIZONTAL_ALIGNMENT_CENTER, true)
		mx += 42
	# часы
	var W := get_viewport_rect().size.x
	var H := get_viewport_rect().size.y
	var night := g.darkness() > 0.5
	var clock := "День %d  ·  %s" % [g.day(), g.clock()]
	var tw := _tw(clock, 14) + 44
	_rr(Rect2(W / 2 - tw / 2, 12, tw, 30), 15, Color(0.055, 0.07, 0.085, 0.6))
	Icons.glyph(self, "moon" if night else "sun", Vector2(W / 2 - tw / 2 + 18, 27), 10, Color(0.72, 0.78, 0.94) if night else Color(0.94, 0.78, 0.31))
	_text(Vector2(W / 2 + 10, 32), clock, 14, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER)
	# сообщения
	var ty := 66.0
	for msg in msgs:
		var a := minf(1.0, msg[1])
		var mw := _tw(msg[0], 13) + 24
		_rr(Rect2(W / 2 - mw / 2, ty - 16, mw, 22), 11, Color(0.055, 0.07, 0.085, 0.6 * a))
		_text(Vector2(W / 2, ty), msg[0], 13, Color(1, 1, 1, a), HORIZONTAL_ALIGNMENT_CENTER)
		ty += 26
	if screen.mode == GameScreen.Mode.HOST and screen.friend_pid < 0:
		_text(Vector2(W / 2, H - 14), "Мир открыт для друга — он увидит его во вкладке «Играть с другом»  (IP: %s)" % Net.local_ip(), 12, Color(0.69, 0.82, 1.0), HORIZONTAL_ALIGNMENT_CENTER, true)
	# джойстик
	if joy_id >= 0:
		draw_circle(joy_base, 62, Color(1, 1, 1, 0.19))
		draw_arc(joy_base, 62, 0, TAU, 48, Color(1, 1, 1, 0.33), 2.0, true)
		draw_arc(joy_base, 37, 0, TAU, 40, Color(1, 1, 1, 0.19), 2.0, true)
		draw_circle(joy_base + (joy_pos - joy_base).limit_length(62), 24, Color(1, 1, 1, 0.7))
	else:
		_text(Vector2(x, H - 16), "Слева — ходьба (чуть сдвинь — красться). Свайп справа — поворот камеры", 11, Color(1, 1, 1, 0.47), HORIZONTAL_ALIGNMENT_LEFT, true)
	# кнопки
	var wid := me.weapon_id()
	var w := me.weapon()
	_btn(b_attack, Color(0.66, 0.19, 0.19, 0.7), "" if wid != "fists" else "fist", wid if wid != "fists" else "",
		str(me.count("ammo")) if w["kind"] == Items.Kind.GUN else "", "Толчок" if wid == "fists" else w["name"])
	var target := screen.view.target
	var tt := g.world.t_at(target) if target >= 0 else -1
	var hint := "Обыскать"
	if target < 0: hint = "Под ногами"
	elif g.world.barr_at(target) > 0: hint = "Заколочено"
	elif tt == GameWorld.DOOR: hint = "Открыть"
	elif tt == GameWorld.DOOR_OPEN: hint = "Закрыть"
	elif tt == GameWorld.WINDOW: hint = "Разбить окно"
	elif GameWorld.is_car(tt): hint = "Багажник"
	var ig := "hand"
	if tt == GameWorld.DOOR or tt == GameWorld.DOOR_OPEN: ig = "door"
	elif target >= 0 and tt != GameWorld.WINDOW: ig = "search"
	_btn(b_interact, Color(0.16, 0.35, 0.53, 0.7), ig, "", "", hint)
	_btn(b_run, Color(0.78, 0.6, 0.13, 0.82) if run_toggle else Color(0.19, 0.2, 0.22, 0.53), "run", "", "", "Бег" if run_toggle else "")
	if barr_cell >= 0:
		_btn(b_barr, Color(0.54, 0.38, 0.19, 0.7), "", "planks", str(me.count("planks")), "Забить")
	_btn(b_inv, Color(0.19, 0.2, 0.22, 0.6), "bag", "", "", "")
	var hurt := me.body.any_hurt()
	_btn(b_body, Color(0.69, 0.13, 0.13, 0.82) if me.bleeding() else (Color(0.54, 0.42, 0.13, 0.7) if hurt else Color(0.19, 0.2, 0.22, 0.6)), "body", "", "", "")
	_btn(b_pause, Color(0.19, 0.2, 0.22, 0.6), "pause", "", "", "")

func _btn(b: Array, col: Color, glyph: String, item: String, badge: String, label: String) -> void:
	var c: Vector2 = b[0]
	var r: float = b[1]
	draw_circle(c + Vector2(2, 3), r, Color(0, 0, 0, 0.25))
	draw_circle(c, r, col)
	draw_arc(c, r - 1, 0, TAU, 48, Color(1, 1, 1, 0.31), 1.5, true)
	if item != "":
		Icons.item(self, item, c, r * 1.35)
	elif glyph != "":
		Icons.glyph(self, glyph, c, r * (0.6 if glyph == "fist" else 0.55))
	if badge != "":
		var bw := _tw(badge, 11) + 10
		var br := Rect2(c.x + r * 0.4, c.y + r * 0.45, bw, 17)
		_rr(br, 8, Color(0.1, 0.12, 0.13))
		_text(Vector2(br.get_center().x, br.end.y - 4.5), badge, 11, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER)
	if label != "":
		_text(Vector2(c.x, c.y - r - 6), label, 11, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, true)

# ================== ПАНЕЛИ ==================
func _layout_panel() -> void:
	var vr := get_viewport_rect()
	var pw := minf(vr.size.x * 0.86, 720.0)
	var ph := vr.size.y * 0.86
	panel_rect = Rect2((vr.size.x - pw) / 2, (vr.size.y - ph) / 2, pw, ph)
	btn_close = Rect2(panel_rect.end.x - 48, panel_rect.position.y + 8, 40, 36)

func _col_rect(col: int, cols: int) -> Rect2:
	var top := panel_rect.position.y + 56
	var w := panel_rect.size.x * 0.55 if cols == 1 else panel_rect.size.x / 2
	var l := panel_rect.position.x + col * w
	return Rect2(l + 10, top, w - 20, panel_rect.end.y - 12 - top)

func _row_at(col: Rect2, scr: float, y: float, n: int) -> int:
	if y < col.position.y:
		return -1
	var i := int((y - col.position.y + scr) / ROW_H)
	return i if i >= 0 and i < n else -1

func _panel_bg(title: String) -> void:
	_layout_panel()
	draw_rect(get_viewport_rect(), Color(0, 0, 0, 0.6))
	_rr(panel_rect, 14, Color(0.125, 0.137, 0.15, 0.95))
	_text(panel_rect.position + Vector2(16, 32), title, 17)
	_rr(btn_close, 8, Color(0.31, 0.33, 0.35))
	_text(btn_close.get_center() + Vector2(0, 6), "✕", 17, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER)

func _item_list(col: Rect2, items: Array, scr_i: int, highlight: String, empty: String) -> void:
	if items.is_empty():
		_text(col.position + Vector2(8, 24), empty, 14, Color(0.56, 0.56, 0.56))
	for i in items.size():
		var s: Dictionary = items[i]
		var top: float = col.position.y + i * ROW_H - scroll[scr_i]
		if top > col.end.y - ROW_H * 0.5 or top + ROW_H < col.position.y:
			continue
		var r := Rect2(col.position.x, top + 2, col.size.x, ROW_H - 4)
		_rr(r, 8, Color(0.24, 0.35, 0.47) if s["id"] == highlight else Color(0.18, 0.2, 0.21))
		Icons.item(self, s["id"], Vector2(col.position.x + 18, top + ROW_H / 2), 30)
		var nm: String = Items.def(s["id"])["name"] + (" ×%d" % s["n"] if s["n"] > 1 else "")
		_text(Vector2(col.position.x + 38, top + ROW_H / 2 + 5), nm, 14)

func _draw_inv(me: GameState.Player) -> void:
	_panel_bg("Вещи  —  вес %.1f / %d" % [me.weight(), int(GameState.MAX_WEIGHT)])
	var col := _col_rect(0, 1)
	scroll[0] = minf(scroll[0], maxf(0.0, me.inv.size() * ROW_H - col.size.y))
	_item_list(col, me.inv, 0, selected, "Пусто")
	var rx := col.end.x + 20
	var ry := col.position.y + 20
	var bw := panel_rect.end.x - rx - 16
	if selected == "" or me.count(selected) <= 0:
		_text(Vector2(rx, ry), "Выбери предмет", 13, Color(0.56, 0.56, 0.56))
		btn_a = Rect2()
		btn_b = Rect2()
		return
	var d := Items.def(selected)
	_text(Vector2(rx, ry), d["name"], 16)
	ry += 24
	var info := ""
	match d["kind"]:
		Items.Kind.FOOD, Items.Kind.DRINK: info = "Голод −%d  Жажда −%d" % [int(d["hunger"]), int(d["thirst"])]
		Items.Kind.MED:
			match selected:
				"bandage": info = "Перевязка ран. Лучше — через «Тело»"
				"rag": info = "Перевязка похуже, быстро пачкается"
				"disinfectant": info = "Защищает рану от воспаления"
				"tweezers": info = "Достать стекло из раны"
				_: info = "+%d HP" % int(d["heal"])
		Items.Kind.MELEE: info = "Урон %d, дальность %.1f" % [int(d["dmg"]), d["range"]]
		Items.Kind.GUN: info = "Урон %d, громко! Нужны патроны" % int(d["dmg"])
		Items.Kind.AMMO: info = "Для пистолета"
		Items.Kind.MATERIAL: info = "Нужен молоток, чтобы заколотить проём"
	_text(Vector2(rx, ry), info, 13, Color(0.69, 0.69, 0.69))
	ry += 18
	_text(Vector2(rx, ry), "Вес %.2f" % d["w"], 13, Color(0.69, 0.69, 0.69))
	ry += 26
	var use := ""
	match d["kind"]:
		Items.Kind.FOOD: use = "Съесть"
		Items.Kind.DRINK: use = "Выпить"
		Items.Kind.MED: use = "Применить"
		Items.Kind.MELEE, Items.Kind.GUN: use = "Убрать из рук" if me.eq == selected else "Взять в руки"
	if use != "":
		btn_a = Rect2(rx, ry, bw, 44)
		_rr(btn_a, 10, Color(0.24, 0.42, 0.24))
		_text(btn_a.get_center() + Vector2(0, 5), use, 15, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER)
		ry += 54
	else:
		btn_a = Rect2()
	btn_b = Rect2(rx, ry, bw, 44)
	_rr(btn_b, 10, Color(0.42, 0.24, 0.24))
	_text(btn_b.get_center() + Vector2(0, 5), "Выбросить", 15, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER)

func _draw_loot(g: GameState, me: GameState.Player) -> void:
	var tt := g.world.t_at(loot_cell)
	var names := {GameWorld.FRIDGE: "Холодильник", GameWorld.COUNTER: "Кухонный шкаф", GameWorld.WARDROBE: "Гардероб", GameWorld.SHELF: "Полка",
		GameWorld.MEDCAB: "Аптечка", GameWorld.LOCKER: "Оружейный шкаф", GameWorld.STORESHELF: "Стеллаж", GameWorld.CAR: "Багажник", GameWorld.CAR_V: "Багажник"}
	_panel_bg("%s  ⇄  Инвентарь" % names.get(tt, "На земле"))
	var left := _col_rect(0, 2)
	var right := _col_rect(1, 2)
	_text(left.position + Vector2(0, -6), "Нажми — взять", 11, Color(0.56, 0.56, 0.56))
	_text(right.position + Vector2(0, -6), "Нажми — положить сюда", 11, Color(0.56, 0.56, 0.56))
	var cont: Array = g.world.containers.get(loot_cell, [])
	scroll[0] = minf(scroll[0], maxf(0.0, cont.size() * ROW_H - left.size.y))
	scroll[1] = minf(scroll[1], maxf(0.0, me.inv.size() * ROW_H - right.size.y))
	var empty := "Обыскиваю…" if (not g.world.containers.has(loot_cell) and GameWorld.is_container(tt)) else "Пусто"
	_item_list(left, cont, 0, "", empty)
	_item_list(right, me.inv, 1, "", "Пусто")

func _draw_pause() -> void:
	_panel_bg("Пауза" if screen.mode == GameScreen.Mode.SOLO else "Меню")
	var bw := minf(panel_rect.size.x * 0.6, 360.0)
	var cx := panel_rect.get_center().x
	var y := panel_rect.position.y + 80
	btn_a = Rect2(cx - bw / 2, y, bw, 50)
	btn_b = Rect2(cx - bw / 2, y + 66, bw, 50)
	_rr(btn_a, 10, Color(0.24, 0.42, 0.24))
	_rr(btn_b, 10, Color(0.42, 0.24, 0.24))
	_text(btn_a.get_center() + Vector2(0, 6), "Продолжить", 16, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER)
	_text(btn_b.get_center() + Vector2(0, 6), "Отключиться" if screen.mode == GameScreen.Mode.CLIENT else "Сохранить и выйти", 16, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER)
	_text(Vector2(cx, y + 66 + 80), "Игра сохраняется сама каждую минуту", 12, Color(0.56, 0.56, 0.56), HORIZONTAL_ALIGNMENT_CENTER)

# ---------- экран «Тело» ----------
const PART_BOXES := [
	[84, 6, 116, 44], [92, 46, 108, 56], [68, 58, 132, 128], [72, 130, 128, 154],
	[46, 60, 66, 104], [38, 106, 60, 148], [32, 150, 56, 174],
	[134, 60, 154, 104], [140, 106, 162, 148], [144, 150, 168, 174],
	[74, 156, 98, 210], [76, 212, 96, 262], [70, 264, 98, 282],
	[102, 156, 126, 210], [104, 212, 124, 262], [102, 264, 130, 282]]

func _body_box() -> Rect2:
	_layout_panel()
	var top := panel_rect.position.y + 52
	var bottom := panel_rect.end.y - 14
	var h := bottom - top
	return Rect2(panel_rect.position.x + 14, top, h * 200.0 / 290.0, h)

func _part_rect(box: Rect2, i: int) -> Rect2:
	var k := box.size.y / 290.0
	var b: Array = PART_BOXES[i]
	return Rect2(box.position.x + b[0] * k, box.position.y + b[1] * k, (b[2] - b[0]) * k, (b[3] - b[1]) * k)

func _part_color(p: Body.Part, pulse: float) -> Color:
	if p.bleeding(): return Color(0.88, 0.16, 0.16) if pulse > 0.5 else Color(0.63, 0.09, 0.09)
	if p.infected(): return Color(0.6, 0.31, 0.78)
	if p.has_glass(): return Color(0.35, 0.72, 0.85)
	if not p.wounds.is_empty() and p.bandage != 0: return Color(0.69, 0.6, 0.35) if p.dirty() else Color(0.91, 0.91, 0.91)
	if not p.wounds.is_empty(): return Color(0.88, 0.54, 0.23)
	if p.bruise > 0.0: return Color(0.78, 0.69, 0.25)
	return Color(0.31, 0.54, 0.31)

func _draw_body(me: GameState.Player) -> void:
	_panel_bg("Тело  —  здоровье %d%%" % int(me.hp))
	var box := _body_box()
	var pulse := fmod(Time.get_ticks_msec(), 800.0) / 800.0
	for i in Body.COUNT:
		var r := _part_rect(box, i)
		var bp: Body.Part = me.body.parts[i]
		_rr(r, r.size.x / 2 if i == Body.HEAD else 6, _part_color(bp, pulse))
		if bp.bandage != 0:
			for k in range(1, 3):
				var yy := r.position.y + r.size.y * k / 3.0
				draw_line(Vector2(r.position.x, yy), Vector2(r.end.x, yy), Color(0.54, 0.44, 0.25) if bp.dirty() else Color.WHITE, 2.0)
		if i == sel_part:
			draw_rect(r, Color.WHITE, false, 2.5)
	var lx := box.end.x + 6
	var ly := box.end.y - 6
	var legend := [[Color(0.91, 0.91, 0.91), "повязка"], [Color(0.6, 0.31, 0.78), "воспал."], [Color(0.35, 0.72, 0.85), "стекло"],
		[Color(0.88, 0.16, 0.16), "кровь"], [Color(0.88, 0.54, 0.23), "рана"], [Color(0.31, 0.54, 0.31), "цело"]]
	for lg in legend:
		draw_circle(Vector2(lx + 5, ly - 4), 4, lg[0])
		_text(Vector2(lx + 13, ly), lg[1], 10)
		ly -= 14
	var rx := box.end.x + 70
	var ry := panel_rect.position.y + 74
	var rw := panel_rect.end.x - rx - 16
	var bp2: Body.Part = me.body.parts[sel_part]
	_text(Vector2(rx, ry), Body.NAMES[sel_part], 17)
	ry += 24
	if bp2.ok():
		_text(Vector2(rx, ry), "Без повреждений", 13, Color(0.56, 0.75, 0.56))
		ry += 20
	if bp2.bruise > 0.0:
		_text(Vector2(rx, ry), "Ушиб — пройдёт сам", 13, Color(0.85, 0.75, 0.38))
		ry += 18
	for wd in bp2.wounds:
		var heal := clampi(int(100.0 * (1.0 - wd.heal / Body.HEAL_TIME[wd.type])), 0, 100)
		var parts := []
		if wd.glass: parts.append("стекло внутри")
		if wd.bleeding() and bp2.bandage == 0: parts.append("кровоточит")
		if wd.infected(): parts.append("воспаление")
		elif wd.disinfected: parts.append("обработана")
		parts.append("заживает %d%%" % heal)
		var col := Color(1, 0.44, 0.44) if (wd.bleeding() and bp2.bandage == 0) else (Color(0.75, 0.56, 0.94) if wd.infected() else Color(0.88, 0.88, 0.88))
		_text(Vector2(rx, ry), "%s: %s" % [Body.WOUND_NAMES[wd.type], ", ".join(parts)], 13, col)
		ry += 18
	if bp2.bandage != 0:
		_text(Vector2(rx, ry), "Повязка: %s%s" % ["бинт" if bp2.bandage == 1 else "тряпка", ", грязная — смени" if bp2.dirty() else ""], 13, Color(0.75, 0.63, 0.38) if bp2.dirty() else Color(0.88, 0.88, 0.88))
		ry += 18
	var gen := []
	if me.infection > 420.0: gen.append("Лихорадка, слабость")
	if me.body.leg_penalty() < 0.95: gen.append("Хромаешь (−%d%% скорости)" % int(100 * (1 - me.body.leg_penalty())))
	if me.body.arm_penalty() < 0.95: gen.append("Слабый удар (−%d%% урона)" % int(100 * (1 - me.body.arm_penalty())))
	if gen.size() > 0:
		_text(Vector2(rx, panel_rect.position.y + 48), "  ·  ".join(gen), 12, Color(0.69, 0.69, 0.69))
	treat_btns.clear()
	ry += 10
	var opts := []
	if bp2.has_glass():
		opts.append(["Достать стекло пинцетом" if me.count("tweezers") > 0 else "Нужен пинцет", "glass" if me.count("tweezers") > 0 else ""])
	if not bp2.wounds.is_empty() and (bp2.bandage == 0 or bp2.dirty()):
		if me.count("bandage") > 0:
			opts.append([("Сменить на бинт" if bp2.bandage != 0 else "Перевязать бинтом") + " (%d)" % me.count("bandage"), "bandage"])
		if me.count("rag") > 0:
			opts.append([("Сменить на тряпку" if bp2.bandage != 0 else "Перевязать тряпкой") + " (%d)" % me.count("rag"), "rag"])
		if me.count("bandage") == 0 and me.count("rag") == 0:
			opts.append(["Нечем перевязать — найди бинт или тряпку", ""])
	var undis := false
	for wd2 in bp2.wounds:
		if not wd2.disinfected:
			undis = true
	if undis:
		opts.append(["Продезинфицировать" if me.count("disinfectant") > 0 else "Нет дезинфицирующего", "disinfect" if me.count("disinfectant") > 0 else ""])
	if bp2.bandage != 0:
		opts.append(["Снять повязку", "unbandage"])
	for o in opts:
		var r2 := Rect2(rx, ry, rw, 38)
		_rr(r2, 8, Color(0.23, 0.23, 0.23) if o[1] == "" else Color(0.24, 0.35, 0.42))
		_text(Vector2(r2.position.x + 12, r2.get_center().y + 5), o[0], 13)
		if o[1] != "":
			treat_btns.append([r2, o[1]])
		ry += 44

func _draw_dead(g: GameState, me: GameState.Player, vr: Rect2) -> void:
	draw_rect(vr, Color(0.19, 0, 0, 0.73))
	var W := vr.size.x
	var H := vr.size.y
	_text(Vector2(W / 2, H * 0.36), "Вот так ты и умер", 34, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, true)
	_text(Vector2(W / 2, H * 0.36 + 36), "Дней прожито: %d   Убито зомби: %d" % [g.day() - 1, me.kills], 15, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER)
	for p in g.players:
		if not p.dead and p.id != me.id and p.connected:
			_text(Vector2(W / 2, H * 0.36 + 60), "Напарник ещё жив — можешь наблюдать", 15, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER)
			break
	dead_btn = Rect2(W / 2 - 110, H * 0.62, 220, 50)
	_rr(dead_btn, 10, Color(0.31, 0.33, 0.35))
	_text(dead_btn.get_center() + Vector2(0, 6), "В меню", 16, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER)
