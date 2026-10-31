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
var init_sent := false
var snap_t := 0.0
var input_t := 0.0
var autosave_t := 60.0
var lost := false
var demo := ""
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

func _process(dt: float) -> void:
	dt = minf(dt, 0.05)
	_handle_net()
	if game == null:
		hud.queue_redraw()
		return
	var p := me()
	var mv := hud.joystick()
	if mv.length() > 0.0:
		mv = view.screen_to_world(mv)
	var run := hud.run_toggle
	hud.tick(dt)
	if mode == Mode.CLIENT:
		input_t -= dt
		if input_t <= 0.0:
			input_t = 0.05
			Net.send({"t": "in", "mx": mv.x, "my": mv.y, "run": run})
		game.tick_replica(dt)
		var k := minf(1.0, dt * 12.0)
		for z in game.zombies:
			z.rpos = z.rpos.lerp(z.pos, k)
		for pl in game.players:
			if pl.rpos.distance_to(pl.pos) > 3.0:
				pl.rpos = pl.pos
			pl.rpos = pl.rpos.lerp(pl.pos, k)
	else:
		if p != null:
			p.move = mv
			p.run = run
		var paused := mode == Mode.SOLO and hud.paused()
		if not paused:
			game.update(dt)
		for z in game.zombies:
			z.rpos = z.pos
		for pl in game.players:
			pl.rpos = pl.pos
		for m in game.pop_messages(local_pid):
			hud.add_msg(m)
		if mode == Mode.HOST:
			if Net.connected and not init_sent:
				game.add_player(1)
				Net.send({"t": "init", "pid": 1, "seed": game.seed, "world": game.to_data()})
				game.world.dirty_tiles.clear()
				game.world.dirty_cont.clear()
				init_sent = true
				hud.add_msg("Второй игрок подключился")
			snap_t -= dt
			if init_sent and snap_t <= 0.0:
				snap_t = 1.0 / 15.0
				var s := game.snapshot()
				s["m"] = game.pop_messages(1)
				Net.send(s)
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

func _handle_net() -> void:
	while Net.inbox.size() > 0:
		var o: Dictionary = Net.inbox.pop_front()
		match o.get("t", ""):
			"init":
				var g := GameState.new(int(o["seed"]))
				g.is_replica = true
				g.load_data(o["world"])
				local_pid = int(o["pid"])
				game = g
				_make_view()
			"snap":
				if game != null:
					game.apply_snapshot(o)
					for m in o.get("m", []):
						hud.add_msg(m)
			"in":
				if game != null:
					var pl := game.player(1)
					if pl != null:
						pl.move = Vector2(o.get("mx", 0.0), o.get("my", 0.0))
						pl.run = o.get("run", false)
			"act":
				if game != null:
					var a := o.duplicate()
					a.erase("t")
					game.actions.append([1, a])
			"disconnect":
				if mode == Mode.HOST:
					if game != null:
						var pl2 := game.player(1)
						if pl2 != null:
							pl2.connected = false
							pl2.move = Vector2.ZERO
					init_sent = false
					hud.add_msg("Второй игрок отключился")
				else:
					lost = true
