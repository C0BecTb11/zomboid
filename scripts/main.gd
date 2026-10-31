extends Node
## Главное меню и переключение экранов.

var screen: GameScreen
var menu: Control
var shot := ""
var shot_frames := 0
var ip_edit: LineEdit

func _ready() -> void:
	var demo := ""
	for a in OS.get_cmdline_user_args():
		if a.begins_with("--demo="):
			demo = a.substr(7)
		elif a.begins_with("--shot="):
			shot = a.substr(7)
	get_tree().set_auto_accept_quit(false)
	if demo == "menu":
		_show_menu()
	elif demo != "":
		_start(GameScreen.Mode.SOLO, Demo.create(demo))
		screen.demo = demo
		screen.hud.open_demo_panel(demo)
	else:
		_show_menu()

func _process(_dt: float) -> void:
	if shot != "":
		shot_frames += 1
		if shot_frames == 45:
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
			screen.hud.panel = Hud.Pnl.PAUSE if screen.hud.panel == Hud.Pnl.NONE else Hud.Pnl.NONE
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

func _show_menu() -> void:
	if screen != null:
		screen.queue_free()
		screen = null
	Net.close()
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
	var title := Label.new()
	title.text = "МЁРТВЫЙ ГОРОД"
	title.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	title.add_theme_font_size_override("font_size", 34)
	title.add_theme_color_override("font_color", Color(0.82, 0.25, 0.25))
	col.add_child(title)
	var sub := Label.new()
	sub.text = "Вот так ты и умрёшь. Вопрос — когда."
	sub.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	sub.add_theme_color_override("font_color", Color(0.56, 0.56, 0.56))
	col.add_child(sub)
	var has_save := Saves.load_game() != null
	if has_save:
		col.add_child(_btn("Продолжить", Color(0.18, 0.42, 0.23), func(): _start(GameScreen.Mode.SOLO, Saves.load_game())))
	col.add_child(_btn("Новая игра", Color(0.23, 0.29, 0.35), _new_game.bind(has_save)))
	col.add_child(_btn("Пригласить друга в свой мир (Wi‑Fi)" if has_save else "Создать игру на двоих (Wi‑Fi)", Color(0.35, 0.29, 0.16), _host))
	ip_edit = LineEdit.new()
	ip_edit.placeholder_text = "IP друга, например 192.168.0.12"
	ip_edit.custom_minimum_size = Vector2(320, 44)
	ip_edit.virtual_keyboard_type = LineEdit.KEYBOARD_TYPE_NUMBER_DECIMAL
	var cfg := ConfigFile.new()
	if cfg.load("user://settings.cfg") == OK:
		ip_edit.text = cfg.get_value("net", "ip", "")
	col.add_child(ip_edit)
	col.add_child(_btn("Подключиться к другу", Color(0.16, 0.29, 0.42), _join))
	var info := Label.new()
	info.text = "Для игры вдвоём оба телефона должны быть в одной Wi‑Fi сети\n(или один раздаёт точку доступа). Хост видит свой IP внизу экрана."
	info.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	info.add_theme_font_size_override("font_size", 12)
	info.add_theme_color_override("font_color", Color(0.5, 0.5, 0.5))
	col.add_child(info)

var _confirm: ConfirmationDialog

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

func _host() -> void:
	var g := Saves.load_game()
	Net.host()
	_start(GameScreen.Mode.HOST, g)

func _join() -> void:
	var ip := ip_edit.text.strip_edges()
	if ip == "":
		ip_edit.placeholder_text = "Сначала введи IP"
		return
	var cfg := ConfigFile.new()
	cfg.set_value("net", "ip", ip)
	cfg.save("user://settings.cfg")
	Net.join(ip)
	_start(GameScreen.Mode.CLIENT, null)

func _start(mode: int, g: GameState) -> void:
	if menu != null:
		menu.queue_free()
		menu = null
	if mode != GameScreen.Mode.CLIENT:
		if g == null:
			g = GameState.new_game(int(Time.get_unix_time_from_system()))
		for p in g.players:
			if p.id != 0:
				p.connected = false
	screen = GameScreen.new()
	add_child(screen)
	screen.start(mode, g)
	screen.exited.connect(_on_exit)

func _on_exit(delete_save: bool) -> void:
	if delete_save:
		Saves.delete()
	_show_menu.call_deferred()
