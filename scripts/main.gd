extends Node
## Главное меню и переключение экранов.

var screen: GameScreen
var menu: Control
var shot := ""
var shot_frames := 0
var shot_list: Array = []      # несколько кадров подряд: --shots=40,44,48
var ip_edit: LineEdit

func _ready() -> void:
	var demo := ""
	for a in OS.get_cmdline_user_args():
		if a.begins_with("--demo="):
			demo = a.substr(7)
		elif a.begins_with("--shot="):
			shot = a.substr(7)
		elif a.begins_with("--shots="):
			for f in a.substr(8).split(","):
				shot_list.append(int(f))
	get_tree().set_auto_accept_quit(false)
	if "--audit" in OS.get_cmdline_user_args():
		add_child(load("res://scripts/audit_test.gd").new())
		return
	for a in OS.get_cmdline_user_args():
		if a.begins_with("--nettest="):
			var nt = load("res://scripts/nettest.gd").new()
			nt.role = a.substr(10)
			nt.main = self
			add_child(nt)
			if nt.role == "host":
				Net.nick = "ТестХост"
				_start(GameScreen.Mode.HOST, GameState.new_game(777))
				Net.host(screen.adv_info)
			else:
				Net.nick = "ТестДруг"
			return
	if demo == "menu":
		_show_menu()
	elif demo == "friends":
		_show_friends()
	elif demo != "":
		_start(GameScreen.Mode.SOLO, Demo.create(demo))
		screen.demo = demo
		screen.hud.open_demo_panel(demo)
	else:
		_show_menu()

func _process(_dt: float) -> void:
	_process_menu()
	if shot != "":
		shot_frames += 1
		if screen != null and screen.demo in ["stomp", "fall"] and shot_frames == 40:
			screen.act({"a": "attack"})
		# кадры позы удара ногой без зависимости от FPS: --demo=stomp_pose
		if screen != null and screen.demo == "stomp_pose" and screen.game != null and shot_list.size() > 0:
			screen.game.freeze = true
			var me: GameState.Player = screen.game.players[0]
			me.atk_kind = 1
			var idx := 0
			for f in shot_list:
				if shot_frames >= f:
					idx += 1
			me.attack_anim = 1.0 - clampf(float(idx) / shot_list.size(), 0.0, 1.0)
		if shot_list.size() > 0:
			if shot_frames in shot_list:
				await RenderingServer.frame_post_draw
				get_viewport().get_texture().get_image().save_png(shot.replace(".png", "_%03d.png" % shot_frames))
			if shot_frames > shot_list.max():
				get_tree().quit()
		elif shot_frames == 45:
			await RenderingServer.frame_post_draw
			get_viewport().get_texture().get_image().save_png(shot)
			get_tree().quit()

func _notification(what: int) -> void:
	if what == NOTIFICATION_WM_CLOSE_REQUEST:
		if screen != null:
			screen.save_now()
		get_tree().quit()
	elif what == NOTIFICATION_WM_GO_BACK_REQUEST:
		if screen != null and screen.hud != null:
			var h: Hud = screen.hud
			h._release()          # пальцы, зажатые до паузы, больше не двигают персонажа
			h.panel_id = -1
			if screen.game == null:
				screen.request_exit(false)      # ещё ждём мир друга — «Назад» = отмена
			else:
				h.panel = Hud.Pnl.PAUSE if h.panel == Hud.Pnl.NONE else Hud.Pnl.NONE
		elif screen == null and is_instance_valid(server_box) and server_box.is_inside_tree():
			Net.stop_scan()
			_show_menu()
		elif screen == null:
			get_tree().quit()

# ================== МЕНЮ ==================
func _btn(text: String, color: Color, cb: Callable) -> Button:
	var b := Button.new()
	b.text = text
	b.custom_minimum_size = Vector2(320, 48)
	b.add_theme_font_size_override("font_size", 17)
	for st in ["normal", "hover", "pressed", "focus"]:
		var s := StyleBoxFlat.new()
		s.bg_color = color.lightened(0.12) if st == "pressed" else color
		s.set_corner_radius_all(12)
		b.add_theme_stylebox_override(st, s)
	b.pressed.connect(cb)
	return b

func _clear_menu() -> void:
	Net.stop_scan()
	if menu != null:
		menu.queue_free()
		menu = null

func _menu_base() -> VBoxContainer:
	_clear_menu()
	menu = Control.new()
	menu.set_anchors_preset(Control.PRESET_FULL_RECT)
	add_child(menu)
	var bg := ColorRect.new()
	bg.color = Color(0.067, 0.075, 0.086)
	bg.set_anchors_preset(Control.PRESET_FULL_RECT)
	menu.add_child(bg)
	var scroll := ScrollContainer.new()
	scroll.set_anchors_preset(Control.PRESET_FULL_RECT)
	scroll.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	menu.add_child(scroll)
	var center := CenterContainer.new()
	center.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	center.size_flags_vertical = Control.SIZE_EXPAND_FILL
	scroll.add_child(center)
	var col := VBoxContainer.new()
	col.add_theme_constant_override("separation", 10)
	center.add_child(col)
	return col

func _label(text: String, size: int, color: Color) -> Label:
	var l := Label.new()
	l.text = text
	l.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	l.add_theme_font_size_override("font_size", size)
	l.add_theme_color_override("font_color", color)
	return l

func _show_menu() -> void:
	if screen != null:
		screen.queue_free()
		screen = null
	Net.close()
	Net.stop_scan()
	var col := _menu_base()
	col.add_child(_label("МЁРТВЫЙ ГОРОД", 34, Color(0.82, 0.25, 0.25)))
	col.add_child(_label("Вот так ты и умрёшь. Вопрос — когда.", 16, Color(0.56, 0.56, 0.56)))
	var has_save := Saves.load_game() != null
	if has_save:
		col.add_child(_btn("Продолжить", Color(0.18, 0.42, 0.23), func(): _start(GameScreen.Mode.SOLO, Saves.load_game())))
	col.add_child(_btn("Новая игра", Color(0.23, 0.29, 0.35), _new_game.bind(has_save)))
	col.add_child(_btn("Играть с другом", Color(0.35, 0.29, 0.16), _show_friends))

# ---------- вкладка «Играть с другом» ----------
var server_box: VBoxContainer
var scan_label: Label
var nick_edit: LineEdit

func _show_friends() -> void:
	var col := _menu_base()
	col.add_child(_label("ИГРАТЬ С ДРУГОМ", 26, Color(0.9, 0.75, 0.4)))
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 8)
	var nl := Label.new()
	nl.text = "Твоё имя:"
	nl.add_theme_color_override("font_color", Color(0.7, 0.7, 0.7))
	row.add_child(nl)
	nick_edit = LineEdit.new()
	nick_edit.text = Net.nick
	nick_edit.max_length = 18
	nick_edit.custom_minimum_size = Vector2(230, 40)
	nick_edit.text_changed.connect(func(t): Net.nick = t.strip_edges() if t.strip_edges() != "" else Net.nick; Net.save_profile())
	row.add_child(nick_edit)
	col.add_child(row)
	var has_save := Saves.load_game() != null
	col.add_child(_btn("Открыть мой мир для друга" if has_save else "Создать мир для друга", Color(0.18, 0.42, 0.23), _host))
	col.add_child(_label("Миры в твоей Wi‑Fi сети:", 14, Color(0.7, 0.7, 0.7)))
	server_box = VBoxContainer.new()
	server_box.add_theme_constant_override("separation", 8)
	col.add_child(server_box)
	scan_label = _label("Ищу… (друг должен открыть свой мир)", 13, Color(0.5, 0.5, 0.5))
	server_box.add_child(scan_label)
	server_btns.clear()
	# ручной ввод на случай, если поиск не сработал
	var manual := HBoxContainer.new()
	manual.add_theme_constant_override("separation", 8)
	ip_edit = LineEdit.new()
	ip_edit.placeholder_text = "или IP вручную: 192.168.0.12"
	ip_edit.custom_minimum_size = Vector2(230, 44)
	ip_edit.virtual_keyboard_type = LineEdit.KEYBOARD_TYPE_URL
	ip_edit.text_changed.connect(_filter_ip)
	var cfg := ConfigFile.new()
	if cfg.load("user://settings.cfg") == OK:
		ip_edit.text = cfg.get_value("net", "ip", "")
	manual.add_child(ip_edit)
	var go := _btn("Войти", Color(0.16, 0.29, 0.42), func(): _join(ip_edit.text.strip_edges()))
	go.custom_minimum_size = Vector2(82, 44)
	manual.add_child(go)
	col.add_child(manual)
	col.add_child(_btn("Назад", Color(0.22, 0.22, 0.24), _show_menu))
	col.add_child(_label("Оба телефона — в одной Wi‑Fi сети (или один раздаёт точку доступа).", 12, Color(0.5, 0.5, 0.5)))
	Net.start_scan()

func _filter_ip(t: String) -> void:
	var out := ""
	for ch in t.replace(",", "."):
		if ch == "." or (ch >= "0" and ch <= "9"):
			out += ch
	if out != t:
		var pos := ip_edit.caret_column
		ip_edit.text = out
		ip_edit.caret_column = mini(pos, out.length())

var server_btns := {}     # ключ мира → кнопка (кнопки не пересоздаются, чтобы не терять нажатие)

func _process_menu() -> void:
	if server_box == null or not is_instance_valid(server_box) or Net.scan_sock == null:
		return
	var list := Net.server_list()
	var keys := {}
	for sv in list:
		var key: String = sv["key"]
		keys[key] = true
		var inf: Dictionary = sv["info"]
		var busy: bool = inf.get("busy", false)
		var text := "%s  ·  день %d%s" % [inf.get("name", "Мир"), int(inf.get("day", 1)), "  ·  занято" if busy else ""]
		var b: Button = server_btns.get(key)
		if b == null or not is_instance_valid(b):
			b = _btn(text, Color(0.16, 0.36, 0.5), func(): pass)
			b.pressed.connect(_join_button.bind(b))
			server_box.add_child(b)
			server_btns[key] = b
		b.set_meta("ip", sv["ip"])
		if b.text != text:
			b.text = text
		b.disabled = busy
	for key in server_btns.keys():
		if not keys.has(key):
			if is_instance_valid(server_btns[key]):
				server_btns[key].queue_free()
			server_btns.erase(key)
	scan_label.visible = list.is_empty()

func _join_button(b: Button) -> void:
	_join(str(b.get_meta("ip")))

func _new_game(has_save: bool) -> void:
	if not has_save:
		_start(GameScreen.Mode.SOLO, null)
		return
	_confirm = ConfirmationDialog.new()
	_confirm.dialog_text = "Начать заново? Текущий мир будет удалён."
	_confirm.ok_button_text = "Да"
	_confirm.cancel_button_text = "Нет"
	_confirm.confirmed.connect(func(): Saves.delete(); _start(GameScreen.Mode.SOLO, null))
	menu.add_child(_confirm)
	_confirm.popup_centered()

var _confirm: ConfirmationDialog

func _host() -> void:
	var g := Saves.load_game()
	_start(GameScreen.Mode.HOST, g)
	Net.host(screen.adv_info)

func _join(ip: String) -> void:
	if ip == "" or ip.split(".").size() != 4:
		if ip_edit != null and is_instance_valid(ip_edit):
			ip_edit.text = ""
			ip_edit.placeholder_text = "Нужен IP вида 192.168.0.12"
		return
	var cfg := ConfigFile.new()
	cfg.load("user://settings.cfg")
	cfg.set_value("net", "ip", ip)
	cfg.save("user://settings.cfg")
	Net.join(ip)
	_start(GameScreen.Mode.CLIENT, null)

func _start(mode: int, g: GameState) -> void:
	_clear_menu()
	if mode != GameScreen.Mode.CLIENT:
		if g == null:
			g = GameState.new_game(int(Time.get_unix_time_from_system()))
		for p in g.players:
			if p.id != 0:
				p.connected = false
		g.revive_host()
	screen = GameScreen.new()
	add_child(screen)
	screen.start(mode, g)
	screen.exited.connect(_on_exit)

func _on_exit(delete_save: bool) -> void:
	if delete_save:
		Saves.delete()
	_show_menu.call_deferred()
