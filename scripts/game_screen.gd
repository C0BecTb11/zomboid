class_name GameScreen
extends Node
## Игровой экран: цикл логики, сеть, сохранения. 3D — WorldView, интерфейс — Hud.

enum Mode { SOLO, HOST, CLIENT }

signal exited(delete_save: bool)

var mode := Mode.SOLO
var game: GameState
var view: WorldView
var hud: Hud
var local_pid := 0
var snap_t := 0.0
var input_t := 0.0
var autosave_t := 60.0
var lost := false
var demo := ""
var debug_move := Vector2.ZERO   # для автотестов
var _exiting := false

func start(m: int, g: GameState) -> void:
	mode = m
	game = g
	local_pid = 1 if m == Mode.CLIENT else 0
	var layer := CanvasLayer.new()
	add_child(layer)
	hud = Hud.new()
	hud.screen = self
	hud.set_anchors_preset(Control.PRESET_FULL_RECT)
	layer.add_child(hud)
	if game != null:
		_make_view()

func _make_view() -> void:
	view = WorldView.new()
	add_child(view)
	move_child(view, 0)
	view.setup(game.world)

func me() -> GameState.Player:
	return game.player(local_pid) if game != null else null

func act(a: Dictionary) -> void:
	if mode == Mode.CLIENT:
		if a.get("a") == "attack" and game != null and me() != null:
			game.predict_attack(me())
		var m := a.duplicate()
		m["t"] = "act"
		Net.send(m)
	elif game != null:
		game.actions.append([local_pid, a])

func save_now() -> void:
	if game == null or mode == Mode.CLIENT or demo != "" or _exiting:
		return
	var alive := false
	for p in game.players:
		if not p.dead:
			alive = true
	if not alive:
		return
	Saves.save(game)

func request_exit(delete_save: bool) -> void:
	if _exiting:
		return
	if not delete_save:
		save_now()
	_exiting = true
	Net.close()
	exited.emit(delete_save)

func _notification(what: int) -> void:
	if what == NOTIFICATION_APPLICATION_PAUSED or what == NOTIFICATION_WM_CLOSE_REQUEST or what == NOTIFICATION_APPLICATION_FOCUS_OUT:
		save_now()

var connect_t := 0.0

func _process(dt: float) -> void:
	dt = minf(dt, 0.1)
	act_budget = 30
	_handle_net()
	if game == null and mode == Mode.CLIENT and not lost:
		connect_t += dt
		if connect_t > 12.0:
			Net.status = "Мир не отвечает. Проверь, что вы в одной Wi‑Fi сети"
			Net.close()
			lost = true
	if game == null:
		hud.queue_redraw()
		return
	var p := me()
	var mv := hud.joystick()
	if mv.length() > 0.0:
		mv = view.screen_to_world(mv)
	if debug_move != Vector2.ZERO:
		mv = debug_move
	var run := hud.run_toggle
	hud.tick(dt)
	if mode == Mode.CLIENT:
		# свой персонаж двигается сразу на этом телефоне, хосту уходит готовая позиция
		if p != null and not p.dead:
			p.move = mv
			p.run = run
			game.move_player(p, dt, false)
			p.rpos = p.pos
		input_t -= dt
		if input_t <= 0.0 and p != null:
			input_t = 0.05
			var o := {"t": "in", "ts": Time.get_ticks_msec(), "mx": mv.x, "my": mv.y, "run": run, "x": p.pos.x, "y": p.pos.y,
				"fx": p.face.x, "fy": p.face.y, "cl": p.climb, "fs": p.force_seq}
			if p.climb >= 0.0:
				o["ctx"] = p.cl_to.x
				o["cty"] = p.cl_to.y
			Net.send_fast(o)
		game.tick_replica(dt)
		# всё чужое рисуем чуть в прошлом, между двумя пришедшими снимками — плавно и без забегания
		var rt := render_time()
		for z in game.zombies:
			GameState.sample_snaps(z, rt)
		for pl in game.players:
			if pl.id == local_pid:
				pl.rface = pl.face
				pl.rclimb = pl.climb
				continue
			GameState.sample_snaps(pl, rt)
	else:
		if p != null:
			p.move = mv
			p.run = run
		var paused := mode == Mode.SOLO and hud.paused()
		if not paused:
			game.update(dt)
		for z in game.zombies:
			z.rpos = z.pos
			z.rface = z.face
			z.rclimb = z.climb
		var rt2 := render_time()
		for pl in game.players:
			if pl.remote and pl.connected:
				GameState.sample_snaps(pl, rt2)     # друг: плавно по его пакетам
			else:
				pl.rpos = pl.pos
				pl.rface = pl.face
				pl.rclimb = pl.climb
		for m in game.pop_messages(local_pid):
			hud.add_msg(m)
		if mode == Mode.HOST:
			_host_send(dt)
		else:
			game.world.dirty_tiles.clear()
			game.world.dirty_cont.clear()
		if not paused:
			autosave_t -= dt
			if autosave_t <= 0.0:
				autosave_t = 60.0
				save_now()
	if p != null:
		view.target = game.find_interact(p)
		hud.barr_cell = game.find_barricade(p)
		view.update(dt, game, p, local_pid)
		if view.vibrate > 0:
			Input.vibrate_handheld(view.vibrate)
			view.vibrate = 0
	hud.queue_redraw()

var friend_pid := -1
# часы второго телефона: задержка отрисовки подстраивается под качество Wi‑Fi
var clock := GameState.NetClock.new()

func clock_sample(ts: float) -> void:
	clock.sample(ts, Time.get_ticks_msec())

func render_time() -> float:
	return clock.render_time(Time.get_ticks_msec())

var act_budget := 30            # не больше 30 действий друга за кадр
var state_t := 0.0
var full_t := 0.0
var last_corpses := -1

func _host_send(dt: float) -> void:
	if friend_pid < 0 or not Net.connected:
		game.world.dirty_tiles.clear()
		game.world.dirty_cont.clear()
		game.fx_net.clear()
		return
	state_t -= dt
	if state_t <= 0.0:
		state_t = 0.05
		Net.send_fast(game.state_for(friend_pid))
	full_t -= dt
	var full := full_t <= 0.0
	if full:
		full_t = 0.25
	var cp_changed := game.corpses.size() != last_corpses or (game.corpses.size() > 0 and full)
	last_corpses = game.corpses.size()
	var ev := game.events_for(friend_pid, full, cp_changed and full)
	if ev.size() > 1:
		Net.send(ev)

## Для списка серверов
func adv_info() -> Dictionary:
	var me_p := game.player(0) if game != null else null
	return {"id": Net.uid, "name": Net.nick, "day": game.day() if game != null else 1, "busy": friend_pid >= 0 and Net.connected,
		"alive": me_p != null and not me_p.dead}

func _handle_net() -> void:
	while Net.inbox.size() > 0:
		var o: Dictionary = Net.inbox.pop_front()
		var t := str(o.get("t", ""))
		# хост принимает от клиента только вход/ввод/действия, клиент — только данные мира
		if mode == Mode.HOST and not (t in ["hello", "in", "act", "disconnect"]):
			continue
		if mode == Mode.CLIENT and not (t in ["init", "st", "ev", "disconnect"]):
			continue
		if mode == Mode.SOLO:
			continue
		match t:
			"hello":
				if game != null and friend_pid < 0:
					clock = GameState.NetClock.new()
					var fp := game.join_player(str(o.get("uid", "")), str(o.get("nick", "Друг")))
					friend_pid = fp.id
					Net.send({"t": "init", "pid": fp.id, "seed": game.seed, "world": game.to_data()})
					game.world.dirty_tiles.clear()
					game.world.dirty_cont.clear()
					game.fx_net.clear()
					last_corpses = -1
					full_t = 0.0
					hud.add_msg("%s в игре" % fp.name)
					save_now()
			"init":
				if game != null:
					continue
				var g := GameState.new(int(o["seed"]))
				g.is_replica = true
				g.load_data(o["world"])
				local_pid = int(o["pid"])
				for pl in g.players:
					pl.connected = pl.id == 0 or pl.id == local_pid
				game = g
				if view == null:
					_make_view()
			"st":
				if game != null:
					clock_sample(float(o.get("ts", 0)))
					game.apply_state(o, local_pid)
			"ev":
				if game != null:
					game.apply_events(o, local_pid)
					for m in o.get("m", []):
						hud.add_msg(m)
			"in":
				if game != null and friend_pid >= 0:
					var pl := game.player(friend_pid)
					if pl != null:
						game.remote_input(pl, o)
						if o.has("ts"):
							var ts := float(o["ts"])
							clock_sample(ts)
							GameState.push_snap(pl, ts, pl.pos, pl.face, pl.climb)
			"act":
				if game != null and friend_pid >= 0 and act_budget > 0:
					act_budget -= 1
					var a := o.duplicate()
					a.erase("t")
					game.actions.append([friend_pid, a])
			"disconnect":
				if mode == Mode.HOST:
					if game != null and friend_pid >= 0:
						var pl2 := game.player(friend_pid)
						game.leave_player(friend_pid)
						hud.add_msg("%s вышел — его вещи и место сохранены" % (pl2.name if pl2 != null and pl2.name != "" else "Друг"))
						save_now()
					friend_pid = -1
				else:
					lost = true
