extends Node
## Автотесты механик для аудита: каждая проверка печатает OK или ОШИБКА с подробностями.
## Запуск: godot --headless --path . -- --audit

const W = preload("res://scripts/world.gd")

var fails := 0
var passes := 0

func check(name: String, ok: bool, details: String = "") -> void:
	if ok:
		passes += 1
		print("OK      ", name)
	else:
		fails += 1
		print("ОШИБКА  ", name, "  —  ", details)

func _ready() -> void:
	test_walls()
	test_corner()
	test_stuck_escape()
	test_barricade_on_zombie()
	test_melee_through_wall()
	test_zombie_spawn_near_friend()
	test_medicine()
	test_remote_teleport()
	test_remote_climb_anywhere()
	test_remote_actions()
	test_saves()
	test_dupe()
	test_world_gen()
	test_zombie_ai_perf()
	test_remote_door()
	test_remote_climb_host_driven()
	test_save_mid_climb()
	test_death_mid_climb()
	test_bullet_window()
	test_revive_host()
	test_remote_no_false_rollback()
	print("\nИТОГО: %d прошло, %d ошибок" % [passes, fails])
	get_tree().quit()

func _game() -> GameState:
	var g := GameState.new_game(777)
	g.zombies.clear()
	return g

## Клетка стены с полом по одну сторону и улицей по другую
func _find_wall(g: GameState) -> Vector2i:
	var w := g.world
	for y in range(2, w.h - 2):
		for x in range(2, w.w - 2):
			if w.t(x, y) == W.WALL and w.t(x, y + 1) == W.WOOD and not w.solid(x, y - 1) and w.t(x - 1, y) == W.WALL and w.t(x + 1, y) == W.WALL:
				return Vector2i(x, y)
	return Vector2i(-1, -1)

func test_walls() -> void:
	var g := _game()
	var wc := _find_wall(g)
	var p: GameState.Player = g.players[0]
	p.pos = Vector2(wc.x + 0.5, wc.y - 0.4)
	p.move = Vector2(0, 1)
	var crossed := false
	for i in 300:
		g.update(1.0 / 60.0)
		if p.pos.y > wc.y + 1.0:
			crossed = true
	check("Движение: нельзя пройти сквозь стену", not crossed, "игрок оказался в %s за стеной %s" % [p.pos, wc])
	p.move = Vector2(0, 1)
	p.run = true
	for i in 300:
		g.update(1.0 / 60.0)
	check("Движение: бегом тоже нельзя", p.pos.y < wc.y + 0.5, "pos %s" % p.pos)

func test_corner() -> void:
	var g := _game()
	var p: GameState.Player = g.players[0]
	var w := g.world
	var bad := 0
	for k in 200:
		p.pos = Vector2(randf_range(2, 98), randf_range(2, 98))
		if w.circle_blocked(p.pos.x, p.pos.y, GameState.PLAYER_R):
			continue
		p.move = Vector2.from_angle(randf() * TAU)
		for i in 120:
			g.update(1.0 / 60.0)
			if w.circle_blocked(p.pos.x, p.pos.y, GameState.PLAYER_R - 0.02):
				bad += 1
				break
	check("Движение по диагонали: персонаж не входит в стены/мебель (200 случайных маршрутов)", bad == 0, "%d раз игрок оказался внутри препятствия" % bad)
	p.pos = Vector2(0.5, 0.5)
	p.move = Vector2(-1, -1)
	for i in 120:
		g.update(1.0 / 60.0)
	check("Движение: нельзя выйти за край карты", p.pos.x >= 0.0 and p.pos.y >= 0.0, "pos %s" % p.pos)

## Если персонаж оказался внутри препятствия, режим «застрял» не должен позволять проходить сквозь стены
func test_stuck_escape() -> void:
	var g := _game()
	var wc := _find_wall(g)
	var p: GameState.Player = g.players[0]
	p.pos = Vector2(wc.x + 0.5, wc.y + 0.5)   # прямо внутри стены
	p.move = Vector2(1, 0)                       # вдоль стены
	var max_inside := 0
	var inside := 0
	for i in 180:
		g.update(1.0 / 60.0)
		if g.world.circle_blocked(p.pos.x, p.pos.y, GameState.PLAYER_R - 0.02):
			inside += 1
			max_inside = maxi(max_inside, inside)
		else:
			inside = 0
	check("Застревание: из стены выталкивает, а не даёт идти внутри неё", max_inside < 20, "игрок шёл внутри стен %d кадров подряд, до %s" % [max_inside, p.pos])

func test_barricade_on_zombie() -> void:
	var g := _game()
	var w := g.world
	var p: GameState.Player = g.players[0]
	var door := -1
	for i in w.tiles.size():
		if w.t_at(i) == W.DOOR_OPEN and w.along_y(i % w.w, i / w.w):
			door = i
			break
	w.tiles[door] = W.DOOR_BROKEN
	var c := Vector2(door % w.w + 0.5, door / w.w + 0.5)
	var z := GameState.Zombie.new(5000)
	z.pos = c
	z.stagger = 999.0     # стоит в проёме и не двигается
	g.zombies.append(z)
	p.pos = c + Vector2(0, -1.3)
	p.inv.append({"id": "hammer", "n": 1})
	p.inv.append({"id": "planks", "n": 2})
	g.actions.append([0, {"a": "barr", "c": door}])
	g.update(0.016)
	for i in 200:
		g.update(1.0 / 60.0)
	check("Баррикада: нельзя заколотить проём, в котором стоит зомби", w.barr_at(door) == 0, "баррикада поставлена прямо на зомби — он застрял в стене")
	p.pos = c + Vector2(0.0, -0.76)
	g.zombies.clear()
	w.barr.erase(door)
	g.actions.append([0, {"a": "barr", "c": door}])
	for i in 200:
		g.update(1.0 / 60.0)
	var stuck := w.circle_blocked(p.pos.x, p.pos.y, GameState.PLAYER_R - 0.02)
	check("Баррикада: нельзя заколотить проём, задевая самого себя", not stuck, "игрок на %s оказался внутри баррикады" % p.pos)

func test_melee_through_wall() -> void:
	var g := _game()
	var wc := _find_wall(g)
	var p: GameState.Player = g.players[0]
	p.pos = Vector2(wc.x + 0.5, wc.y - 0.31)
	p.face = Vector2(0, 1)
	p.eq = "crowbar"
	p.inv.append({"id": "crowbar", "n": 1})
	var z := GameState.Zombie.new(5001)
	z.pos = Vector2(wc.x + 0.5, wc.y + 1.31)
	z.hp = 500.0
	g.zombies.append(z)
	for k in 5:
		g.actions.append([0, {"a": "attack"}])
		p.cooldown = 0.0
		g.update(0.016)
	check("Бой: ломом нельзя ударить сквозь стену", z.hp >= 500.0, "зомби за стеной получил %.0f урона" % (500.0 - z.hp))
	var g2 := _game()
	var p2: GameState.Player = g2.players[0]
	var z2 := GameState.Zombie.new(5002)
	z2.pos = p2.pos + Vector2(1.0, 0)
	z2.hp = 500.0
	g2.zombies.append(z2)
	p2.face = Vector2(1, 0)
	g2.actions.append([0, {"a": "attack"}])
	g2.update(0.016)
	check("Бой: удар по зомби рядом проходит", z2.hp < 500.0, "урон не нанесён")

func test_zombie_spawn_near_friend() -> void:
	var g := _game()
	var f := g.join_player("friend-uid", "Друг")
	f.pos = Vector2(10.5, 10.5)
	while g.world.circle_blocked(f.pos.x, f.pos.y, 0.3):
		f.pos.x += 1.0
	g.spawn_zombies(400, g.players[0].pos)
	var near := 0
	for z in g.zombies:
		if z.pos.distance_to(f.pos) < 8.0:
			near += 1
	check("Зомби: не появляются вплотную ко второму игроку", near == 0, "%d зомби появились ближе 8 клеток к другу" % near)

func test_medicine() -> void:
	var g := _game()
	var p: GameState.Player = g.players[0]
	p.inv = [{"id": "bandage", "n": 1}, {"id": "disinfectant", "n": 1}, {"id": "rag", "n": 1}]
	g.treat(p, Body.HEAD, "bandage")
	check("Медицина: бинт не тратится на здоровую часть тела", p.count("bandage") == 1, "бинт потрачен на голову без ран")
	g.treat(p, Body.HEAD, "disinfect")
	check("Медицина: дезинфекция не тратится без ран", p.count("disinfectant") == 1, "средство потрачено впустую")
	g.wound(p, Body.L_FORE, Body.SCRATCH)
	g.treat(p, Body.L_FORE, "bandage")
	g.treat(p, Body.L_FORE, "rag")
	check("Медицина: на свежую повязку не накладывается вторая", p.count("rag") == 1, "тряпка потрачена поверх чистого бинта")
	g.treat(p, 99, "bandage")
	g.treat(p, -1, "bandage")
	check("Медицина: несуществующая часть тела не ломает игру", true)
	var q := g.join_player("x", "x")
	q.inv = [{"id": "tweezers", "n": 1}]
	g.wound(q, Body.R_HAND, Body.GLASS)
	g.treat(q, Body.R_HAND, "glass")
	check("Медицина: пинцет достаёт стекло и остаётся", not q.body.parts[Body.R_HAND].has_glass() and q.count("tweezers") == 1)

func test_remote_teleport() -> void:
	var g := _game()
	var f := g.join_player("t1", "Друг")
	var start := f.pos
	var fs := f.force_seq
	var o := {"mx": 0.0, "my": 0.0, "run": false, "fs": fs, "cl": -1.0, "fx": 1.0, "fy": 0.0}
	for k in 10:
		o["x"] = f.pos.x + 3.9
		o["y"] = f.pos.y
		g.remote_input(f, o)
	check("Сеть: друг не может телепортироваться (10 пакетов по 3.9 клетки)", f.pos.distance_to(start) < 3.0, "сдвинулся на %.1f клеток мгновенно" % f.pos.distance_to(start))

func test_remote_climb_anywhere() -> void:
	var g := _game()
	var wc := _find_wall(g)
	var f := g.join_player("t2", "Друг")
	f.pos = Vector2(wc.x + 0.5, wc.y - 0.4)
	var o := {"mx": 0.0, "my": 0.0, "run": false, "fs": f.force_seq, "cl": 0.0, "fx": 0.0, "fy": 1.0,
		"x": f.pos.x, "y": f.pos.y, "ctx": f.pos.x, "cty": wc.y + 1.5}
	g.remote_input(f, o)
	check("Сеть: друг не может «перелезть» через глухую стену", f.climb < 0.0, "хост принял перелезание через стену %s" % wc)
	var o2 := o.duplicate()
	o2["cl"] = -1.0
	o2["y"] = wc.y + 0.5
	g.remote_input(f, o2)
	check("Сеть: друг не может прислать позицию внутри стены", not g.world.circle_blocked(f.pos.x, f.pos.y, 0.25), "позиция %s внутри стены принята" % f.pos)

func test_remote_actions() -> void:
	var g := _game()
	var f := g.join_player("t3", "Друг")
	f.inv = []
	g.actions.append([f.id, {"a": "use", "id": "water"}])
	g.actions.append([f.id, {"a": "drop", "id": "pistol"}])
	g.actions.append([f.id, {"a": "put", "c": 0, "id": "ammo"}])
	g.actions.append([f.id, {"a": "treat", "part": 2, "w": "bandage"}])
	g.actions.append([f.id, {"a": "interact", "c": 999999}])
	g.actions.append([f.id, {"a": "take", "c": -5, "id": "x"}])
	g.actions.append([f.id, {"a": "unknown"}])
	g.actions.append([f.id, {}])
	g.update(0.016)
	check("Сеть: действия с несуществующими предметами/клетками не ломают хост и ничего не создают", f.inv.is_empty(), "инвентарь: %s" % str(f.inv))
	var far := -1
	for i in g.world.tiles.size():
		if g.world.t_at(i) == W.FRIDGE and Vector2(i % 100, i / 100).distance_to(f.pos) > 10.0:
			far = i
			break
	g.world.containers[far] = [{"id": "canned", "n": 5}]
	g.actions.append([f.id, {"a": "take", "c": far, "id": "canned"}])
	g.update(0.016)
	check("Сеть: нельзя взять предмет из холодильника на другом конце карты", f.count("canned") == 0, "взято %d" % f.count("canned"))

func test_saves() -> void:
	var g := _game()
	var d := g.to_data()
	var g2 := GameState.new(0)
	g2.load_data(JSON.parse_string(JSON.stringify(d)))
	check("Сохранение: мир, игроки, зомби восстанавливаются", g2.players.size() == g.players.size() and g2.world.tiles == g.world.tiles)
	var partial := d.duplicate(true)
	partial.erase("time")
	partial.erase("barr")
	var g3 := GameState.new(0)
	g3.load_data(partial)
	var pp: Dictionary = partial["p"][0]
	pp.erase("hp")
	pp.erase("inf")
	var g4 := GameState.new(0)
	g4.load_data(partial)
	check("Сохранение: файл без части полей загружается со значениями по умолчанию", g4.players.size() == 1 and g4.players[0].hp == 100.0, "загрузка упала")
	var path := "user://save.json"
	Saves.delete()
	var f := FileAccess.open(path, FileAccess.WRITE)
	f.store_string("{\"v\": 2, \"time\": 5, \"tiles\": \"AAAA\", \"p\": [")   # обрезанный файл
	f.close()
	var loaded := Saves.load_game()
	check("Сохранение: повреждённый файл не роняет игру", loaded == null or loaded is GameState)
	Saves.save(g)
	Saves.save(g)
	f = FileAccess.open(path, FileAccess.WRITE)
	f.store_string("мусор")
	f.close()
	var restored := Saves.load_game()
	check("Сохранение: при порче основного файла берётся резервная копия", restored != null, "резервной копии нет — прогресс потерян")

func _try_load(g: GameState, d: Dictionary) -> bool:
	# GDScript не ловит исключения, поэтому проверяем сами ключи, к которым загрузка обращается напрямую
	for k in ["time", "tiles"]:
		if not d.has(k):
			return false
	g.load_data(d)
	return true

func test_dupe() -> void:
	var g := _game()
	var p: GameState.Player = g.players[0]
	var cell := -1
	for i in g.world.tiles.size():
		if g.world.t_at(i) == W.FRIDGE:
			cell = i
			break
	var fc := Vector2(cell % 100 + 0.5, cell / 100 + 0.5)
	for k in 4:
		var n := fc + Vector2(GameWorld.DX4[k], GameWorld.DY4[k])
		if not g.world.circle_blocked(n.x, n.y, 0.3):
			p.pos = n
			break
	g.world.containers[cell] = [{"id": "ammo", "n": 10}]
	p.inv = []
	for k in 20:
		g.actions.append([0, {"a": "take", "c": cell, "id": "ammo"}])
		g.actions.append([0, {"a": "put", "c": cell, "id": "ammo"}])
	g.update(0.016)
	var total := p.count("ammo") + Items.count(g.world.containers[cell], "ammo")
	check("Инвентарь: быстрое перекладывание не размножает предметы", total == 10, "стало %d вместо 10" % total)

func test_world_gen() -> void:
	var a := GameState.new_game(4242)
	var b := GameState.new_game(4242)
	check("Мир: один seed — одинаковый город", a.world.tiles == b.world.tiles)
	var w := a.world
	var unreachable := 0
	# все двери домов доступны с улицы: поиск в ширину от точки старта
	var seen := PackedByteArray()
	seen.resize(w.w * w.h)
	var q := [a.world.idx(floori(a.players[0].pos.x), floori(a.players[0].pos.y))]
	seen[q[0]] = 1
	while q.size() > 0:
		var i: int = q.pop_back()
		for k in 4:
			var nx: int = i % w.w + GameWorld.DX4[k]
			var ny: int = i / w.w + GameWorld.DY4[k]
			if not w.inside(nx, ny):
				continue
			var ni := w.idx(nx, ny)
			var tt := w.t_at(ni)
			var passable := not (tt == W.WALL or tt == W.TREE or W.is_furniture(tt) or W.is_car(tt) or tt == W.WINDOW or tt == W.WINDOW_BROKEN)
			if passable and seen[ni] == 0:
				seen[ni] = 1
				q.append(ni)
	for i in w.tiles.size():
		if W.is_floor(w.t_at(i)) and seen[i] == 0:
			unreachable += 1
	check("Мир: все комнаты достижимы через двери", unreachable == 0, "%d клеток пола недостижимы" % unreachable)
	var blocked_furn := 0
	for i in w.tiles.size():
		if W.is_furniture(w.t_at(i)):
			var any := false
			for k in 4:
				var nx: int = i % w.w + GameWorld.DX4[k]
				var ny: int = i / w.w + GameWorld.DY4[k]
				if seen[w.idx(nx, ny)] == 1:
					any = true
			if not any:
				blocked_furn += 1
	check("Мир: к каждой мебели можно подойти", blocked_furn == 0, "%d предметов мебели недоступны" % blocked_furn)

func test_zombie_ai_perf() -> void:
	var g := GameState.new_game(99)
	g.spawn_zombies(200, Vector2(-100, -100))
	var t0 := Time.get_ticks_usec()
	for i in 120:
		g.update(1.0 / 60.0)
	var ms := (Time.get_ticks_usec() - t0) / 1000.0 / 120.0
	check("ИИ: %d зомби — %.2f мс на кадр (норма < 4 мс на ПК)" % [g.zombies.size(), ms], ms < 4.0, "слишком медленно")
	var through := 0
	for z in g.zombies:
		if g.world.circle_blocked(z.pos.x, z.pos.y, GameState.ZOMBIE_R - 0.05) and z.climb < 0.0:
			through += 1
	check("ИИ: зомби не находятся внутри стен после 2 секунд", through == 0, "%d зомби внутри препятствий" % through)


## Проём (дверь/окно) с проходимыми клетками по обе стороны по вертикали
func _find_opening(g: GameState, tile: int) -> Vector2i:
	var w := g.world
	for y in range(3, w.h - 3):
		for x in range(3, w.w - 3):
			if w.t(x, y) == tile and not w.solid(x, y - 1) and not w.solid(x, y + 1) and w.solid(x - 1, y) and w.solid(x + 1, y):
				return Vector2i(x, y)
	return Vector2i(-1, -1)

func _start_remote_climb(g: GameState, f: GameState.Player) -> Vector2i:
	var wc := _find_opening(g, W.WINDOW)
	if wc.x < 0:
		return wc
	var i := g.world.idx(wc.x, wc.y)
	g.world.tiles[i] = W.WINDOW_BROKEN
	f.pos = Vector2(wc.x + 0.5, wc.y - 0.5)
	var tg := g.world.climb_target(i, f.pos.x, f.pos.y)
	g.remote_input(f, {"mx": 0.0, "my": 1.0, "run": false, "fs": f.force_seq, "cl": 0.0, "fx": 0.0, "fy": 1.0,
		"x": f.pos.x, "y": f.pos.y, "ctx": tg.x, "cty": tg.y})
	return wc

func test_remote_door() -> void:
	var g := _game()
	var dc := _find_opening(g, W.DOOR)
	var f := g.join_player("d1", "Друг")
	f.pos = Vector2(dc.x + 0.5, dc.y - 0.35)
	var o := {"mx": 0.0, "my": 1.0, "run": false, "fs": f.force_seq, "cl": -1.0, "fx": 0.0, "fy": 1.0,
		"x": dc.x + 0.5, "y": dc.y + 1.35}
	g.remote_input(f, o)
	check("Сеть: друг не может пройти сквозь закрытую дверь одним пакетом", f.pos.y < dc.y, "дверь %s, друг принят на %s" % [dc, f.pos])

func test_remote_climb_host_driven() -> void:
	var g := _game()
	var f := g.join_player("c1", "Друг")
	var wc := _start_remote_climb(g, f)
	var started := f.climb >= 0.0
	# телефон друга «завис» и больше ничего не шлёт
	for k in 40:
		g.update(0.05)
	check("Сеть: перелезание друга заканчивает хост — нельзя зависнуть в окне неуязвимым", started and f.climb < 0.0 and g.world.climb_occ.is_empty() and f.pos.y > wc.y + 0.5,
		"окно %s, старт %s, climb %.2f, pos %s, занято %s" % [wc, started, f.climb, f.pos, g.world.climb_occ])

func test_save_mid_climb() -> void:
	var g := _game()
	var f := g.join_player("c2", "Друг")
	var wc := _start_remote_climb(g, f)
	for k in 8:
		g.update(0.05)
	var mid := f.climb
	var g2 := GameState.new(g.seed)
	g2.load_data(JSON.parse_string(JSON.stringify(g.to_data())))
	var f2 := g2.player(f.id)
	check("Сохранение посреди окна: персонаж не телепортируется в угол карты", mid > 0.0 and f2 != null and f2.climb < 0.0 and f2.pos.distance_to(f.cl_to) < 0.05,
		"climb %.2f, после загрузки %s, ожидалось %s" % [mid, f2.pos if f2 else Vector2.ZERO, f.cl_to])

func test_death_mid_climb() -> void:
	var g := _game()
	var f := g.join_player("c3", "Друг")
	_start_remote_climb(g, f)
	f.hp = -5.0
	g.update(0.05)
	check("Смерть в окне освобождает окно", f.dead and g.world.climb_occ.is_empty(), "занято: %s" % g.world.climb_occ)

func test_bullet_window() -> void:
	var g := _game()
	var wc := _find_opening(g, W.WINDOW)
	var a := Vector2(wc.x + 0.5, wc.y - 1.5)
	var b := Vector2(wc.x + 0.5, wc.y + 1.5)
	var through_glass := g.world.los(a.x, a.y, b.x, b.y, true)
	var see := g.world.los(a.x, a.y, b.x, b.y)
	g.world.tiles[g.world.idx(wc.x, wc.y)] = W.WINDOW_BROKEN
	var through_hole := g.world.los(a.x, a.y, b.x, b.y, true)
	check("Бой: пуля не проходит сквозь целое стекло, но проходит в разбитое окно", see and not through_glass and through_hole,
		"видно %s, сквозь стекло %s, сквозь проём %s" % [see, through_glass, through_hole])

func test_revive_host() -> void:
	var g := _game()
	var f := g.join_player("r1", "Друг")
	g.players[0].dead = true
	g.revive_host()
	var h := g.player(0)
	check("Хозяин погиб, друг жив: мир сохраняется, хозяин получает нового персонажа", h != null and not h.dead and g.players.size() == 2 and not f.dead and not g.world.circle_blocked(h.pos.x, h.pos.y, 0.3))


## Честный друг бегает вдоль стен и углов — хост не должен его откатывать
func test_remote_no_false_rollback() -> void:
	var g := _game()
	var f := g.join_player("nr", "Друг")
	var c := GameState.new(g.seed)
	c.is_replica = true
	c.load_data(JSON.parse_string(JSON.stringify(g.to_data())))
	var cf := c.player(f.id)
	var w := g.world
	var routes := 0
	var rollbacks := 0
	for k in 300:
		var st := Vector2(randf_range(2, 98), randf_range(2, 98))
		if w.circle_blocked(st.x, st.y, GameState.PLAYER_R):
			continue
		g.push_player(f, st)
		cf.pos = st
		cf.force_seq = f.force_seq
		cf.climb = -1.0
		cf.move = Vector2.from_angle(randf() * TAU)
		cf.run = randf() < 0.5
		routes += 1
		for i in 120:
			c.move_player(cf, 1.0 / 60.0, false)
			if cf.climb >= 0.0:
				break
			if i % 3 == 2:
				f.last_in_ms = Time.get_ticks_msec() - 50
				var fs := f.force_seq
				g.remote_input(f, {"mx": cf.move.x, "my": cf.move.y, "run": cf.run, "fs": cf.force_seq, "cl": -1.0,
					"fx": cf.face.x, "fy": cf.face.y, "x": cf.pos.x, "y": cf.pos.y})
				if f.force_seq != fs:
					rollbacks += 1
					cf.pos = f.pos
					cf.force_seq = f.force_seq
	check("Сеть: честного друга не откатывает у стен и углов (%d маршрутов)" % routes, rollbacks == 0, "%d ложных откатов" % rollbacks)
