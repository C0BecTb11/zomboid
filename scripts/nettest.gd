extends Node
## Автотест сети: две копии игры на одном компьютере.
## host: открывает мир, выдаёт другу лом, пишет его позицию.
## client: ищет мир в списке, заходит, ходит, выходит, заходит снова и проверяет, что всё на месте.

var role := ""
var main: Node
var t := 0.0
var step := 0
var log_t := 0.0
var saved_pos := Vector2.ZERO
var st_count := 0
var first_pid := -1

func _ready() -> void:
	set_process(true)

func L(s: String) -> void:
	print("[%s %.1f] %s" % [role, t, s])

func _process(dt: float) -> void:
	t += dt
	var scr: GameScreen = main.screen
	if role == "host":
		if step == 0 and scr != null and scr.game != null:
			step = 1
			L("мир открыт, IP %s" % Net.local_ip())
			var stt := scr.game.state_for(0)
			L("размер пакета позиций: %d байт (зомби рядом: %d)" % [var_to_bytes(stt).size(), stt["z"].size() / 7])
		if scr != null and scr.friend_pid >= 0:
			var fp := scr.game.player(scr.friend_pid)
			if fp != null and fp.count("crowbar") == 0 and not fp.get_meta("given", false):
				fp.inv.append({"id": "crowbar", "n": 1})
				fp.set_meta("given", true)
				L("выдал другу лом (pid %d)" % fp.id)
			log_t -= dt
			if log_t <= 0.0 and fp != null:
				log_t = 1.0
				L("друг на хосте: pos=%s move=%s" % [fp.pos.snapped(Vector2(0.01, 0.01)), fp.move.snapped(Vector2(0.01, 0.01))])
		if t > 40.0:
			L("сохранение uids=%s" % str(scr.game.uids if scr and scr.game else {}))
			get_tree().quit()
		return
	# ---------- client ----------
	if role == "touch":
		_touch_test(dt, scr)
		return
	match step:
		0:
			main._show_friends()
			step = 1
		1:
			if Net.servers.size() > 0:
				var ip: String = Net.servers.keys()[0]
				L("нашёл мир: %s %s" % [ip, str(Net.servers[ip]["info"])])
				main._join(ip)
				step = 2
			elif t > 10.0:
				L("ОШИБКА: мир не найден")
				get_tree().quit(1)
		2:
			if scr != null and scr.game != null and scr.me() != null:
				first_pid = scr.local_pid
				L("вошёл, pid=%d pos=%s" % [first_pid, scr.me().pos])
				scr.debug_move = Vector2(1, 0)
				step = 3
				log_t = 2.0
		3:
			log_t -= dt
			if log_t <= 0.0:
				scr.debug_move = Vector2.ZERO
				L("остановился на %s" % scr.me().pos.snapped(Vector2(0.01, 0.01)))
				step = 4
				log_t = 2.0
		4:
			log_t -= dt
			if log_t <= 0.0:
				saved_pos = scr.me().pos
				L("инвентарь перед выходом: %s, лом=%d" % [str(scr.me().inv), scr.me().count("crowbar")])
				scr.request_exit(false)
				step = 5
				log_t = 2.0
		5:
			log_t -= dt
			if log_t <= 0.0:
				L("захожу снова")
				main._show_friends()
				step = 6
		6:
			if Net.servers.size() > 0:
				main._join(Net.servers.keys()[0])
				step = 7
		7:
			if scr != null and scr.game != null and scr.me() != null:
				var me := scr.me()
				L("снова в игре: pid=%d (был %d) pos=%s (был %s) лом=%d" % [scr.local_pid, first_pid, me.pos.snapped(Vector2(0.01, 0.01)), saved_pos.snapped(Vector2(0.01, 0.01)), me.count("crowbar")])
				var ok := scr.local_pid == first_pid and me.pos.distance_to(saved_pos) < 0.3 and me.count("crowbar") == 1
				L("ИТОГ: %s" % ("ВСЁ СОХРАНИЛОСЬ" if ok else "ОШИБКА"))
				step = 8
				log_t = 1.0
		8:
			log_t -= dt
			if log_t <= 0.0:
				get_tree().quit()
	if t > 60.0:
		L("ОШИБКА: таймаут на шаге %d" % step)
		get_tree().quit(1)

func _tap(pos: Vector2, idx: int = 0) -> void:
	var e := InputEventScreenTouch.new()
	e.index = idx
	e.position = pos
	e.pressed = true
	Input.parse_input_event(e)
	var r := InputEventScreenTouch.new()
	r.index = idx
	r.position = pos
	r.pressed = false
	Input.parse_input_event(r)

var _tapped := false
var tap_pos := Vector2.ZERO
func _touch_test(dt: float, scr: GameScreen) -> void:
	match step:
		0:
			main._show_friends()
			step = 1
		1:
			if Net.servers.size() > 0 and main.server_box != null:
				for c in main.server_box.get_children():
					if c is Button and c.visible and c.is_inside_tree() and c.size.x > 0:
						var center: Vector2 = c.get_global_rect().get_center()
						L("нажимаю пальцем на мир: %s в точке %s, disabled=%s" % [c.text, center, c.disabled])
						c.gui_input.connect(func(ev): L("кнопка получила: %s" % ev))
						c.pressed.connect(func(): L("кнопка нажата (pressed)"))
						get_viewport().gui_focus_changed.connect(func(n): L("фокус: %s" % n))
						center = get_viewport().get_final_transform() * center
						L("в координатах окна: %s, окно %s" % [center, DisplayServer.window_get_size()])
						var e := InputEventScreenTouch.new()
						e.position = center
						e.pressed = true
						Input.parse_input_event(e)
						tap_pos = center
						step = 10
						log_t = 0.12
						break
			elif t > 10.0:
				L("ОШИБКА: мир не найден")
				get_tree().quit(1)
		10:
			log_t -= dt
			if log_t <= 0.0:
				var r := InputEventScreenTouch.new()
				r.position = tap_pos
				r.pressed = false
				Input.parse_input_event(r)
				L("отпустил палец")
				step = 2
				log_t = 4.0
		2:
			log_t -= dt
			if log_t <= 0.0:
				var in_game := main.screen != null and main.screen.game != null
				L("через 4 с: экран игры=%s, мир получен=%s, меню=%s" % [main.screen != null, in_game, main.menu != null])
				L("ИТОГ: %s" % ("ВОШЁЛ" if in_game else "ВЫКИНУЛО В МЕНЮ"))
				get_tree().quit()
	if t > 40.0:
		get_tree().quit(1)
