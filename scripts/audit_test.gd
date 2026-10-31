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
	test_smooth_remote()
	test_stomp()
	test_attack_sync()
	test_corpse_facing()
	test_house_layout()
	test_wear()
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


## Друг бежит и останавливается; пакеты 20 раз в секунду приходят с разбросом 20–150 мс (плохой Wi‑Fi).
## Меряем, как его видит другой телефон: замирания посреди бега, рывки и «пробег» после остановки.
func _sim_remote(smooth: bool) -> Array:
	var rng := RandomNumberGenerator.new()
	rng.seed = 5
	var e := GameState.Player.new(1)
	var clk := GameState.NetClock.new()
	var queue := []                # [время прихода, время отправки, позиция]
	var true_pos := Vector2(10, 10)
	for k in 60:                   # 3 секунды бега, потом 2 секунды стоит
		var ts := k * 50.0
		if ts < 3000.0:
			true_pos = Vector2(10 + 3.9 * ts / 1000.0, 10)
		queue.append([ts + rng.randf_range(20.0, 150.0), ts, true_pos])
	for k in range(60, 100):
		queue.append([k * 50.0 + rng.randf_range(20.0, 150.0), k * 50.0, true_pos])
	queue.sort_custom(func(a, b): return a[0] < b[0])
	var frames := 0
	var frozen := 0
	var max_step := 0.0
	var prev := Vector2(10, 10)
	var stop_frame_pos := Vector2.ZERO
	var overshoot := 0.0
	var qi := 0
	var now := 0.0
	while now < 5000.0:
		now += 1000.0 / 60.0
		while qi < queue.size() and queue[qi][0] <= now:
			var q: Array = queue[qi]
			e.pos = q[2]
			if smooth:
				clk.sample(q[1], now)
				GameState.push_snap(e, q[1], q[2], Vector2(1, 0), -1.0)
			qi += 1
		if smooth:
			GameState.sample_snaps(e, clk.render_time(now))
		else:
			e.rpos = e.pos              # как было: позиция ставится в момент прихода пакета
		var step := e.rpos.distance_to(prev)
		if now > 400.0 and now < 2900.0:
			frames += 1
			if step < 0.0001:
				frozen += 1
			max_step = maxf(max_step, step)
		overshoot = maxf(overshoot, e.rpos.x - true_pos.x)
		prev = e.rpos
	return [frozen * 100.0 / frames, max_step, overshoot, e.rpos.distance_to(true_pos)]

func test_smooth_remote() -> void:
	var old := _sim_remote(false)
	var neu := _sim_remote(true)
	print("        было: замирал в %.0f%% кадров бега, рывок до %.2f клетки; стало: %.0f%%, рывок до %.2f, пробег %.2f, итог в %.2f от места остановки" % [old[0], old[1], neu[0], neu[1], neu[2], neu[3]])
	check("Сеть: бегущий друг не замирает и не дёргается на плохом Wi‑Fi", neu[0] < 2.0 and neu[1] < 0.12, "замирал %.0f%%, рывок %.2f" % [neu[0], neu[1]])
	check("Сеть: остановился — стоит на месте, без пробега вперёд", neu[2] < 0.01 and neu[3] < 0.01, "пробег %.2f" % neu[2])

func test_stomp() -> void:
	var g := _game()
	var p: GameState.Player = g.players[0]
	var z := GameState.Zombie.new(9001)
	z.pos = p.pos + Vector2(0.7, 0)
	if g.world.circle_blocked(z.pos.x, z.pos.y, 0.3):
		z.pos = p.pos + Vector2(-0.7, 0)
	z.hp = 500.0
	z.down = 3.0
	g.zombies.append(z)
	p.face = Vector2(0, 1)
	g.actions.append([0, {"a": "attack"}])
	g.update(0.016)
	var kind := p.atk_kind
	var hp_at_start := z.hp
	for i in 25:
		g.update(0.016)
	check("Добивание лежачего — удар ногой, урон в момент удара стопой", kind == 1 and hp_at_start == 500.0 and z.hp < 500.0 and p.face.dot((z.pos - p.pos).normalized()) > 0.9,
		"вид %d, хп в начале %.0f, после %.0f" % [kind, hp_at_start, z.hp])

## Удар хоста виден на втором телефоне ровно один раз и с начала, даже если пакеты теряются
func test_attack_sync() -> void:
	var g := _game()
	var p: GameState.Player = g.players[0]
	var c := GameState.new(g.seed)
	c.is_replica = true
	c.load_data(JSON.parse_string(JSON.stringify(g.to_data())))
	g.actions.append([0, {"a": "attack"}])
	g.update(0.016)
	g.update(0.016)
	g.update(0.016)                # первые пакеты потерялись — анимация на хосте уже идёт
	var st := g.state_for(1)
	c.apply_state(st, 1)
	var cp := c.player(0)
	var first := cp.attack_anim
	c.tick_replica(0.1)
	c.apply_state(g.state_for(1), 1)   # повтор того же удара не должен перезапускать анимацию
	check("Сеть: удар второго игрока проигрывается с начала и один раз", first == 1.0 and cp.attack_anim < 1.0, "в начале %.2f, после повтора %.2f" % [first, cp.attack_anim])
	var ev := g.events_for(1, true, false)
	cp.pos = Vector2(1, 1)
	c.player(0).snaps.append([0.0, 1.0, 1.0, 1.0, 0.0, -1.0])
	c.apply_events(JSON.parse_string(JSON.stringify(ev)), 1)
	check("Сеть: надёжный пакет не откатывает позицию и удар второго игрока", cp.pos == Vector2(1, 1), "позиция откатилась на %s" % cp.pos)

func test_corpse_facing() -> void:
	var g := _game()
	var z := GameState.Zombie.new(77)
	z.pos = g.players[0].pos + Vector2(5, 0)
	z.face = Vector2(0, -1)
	z.hp = -1.0
	g.zombies.append(z)
	g.update(0.016)
	var c: Array = g.corpses[g.corpses.size() - 1]
	check("Труп лежит в ту же сторону, куда смотрел зомби, и знает, чей он", c.size() == 5 and absf(c[3] - atan2(z.face.x, z.face.y)) < 0.02 and int(c[4]) == 77, str(c))


## Дома: окно не упирается во внутреннюю стену/мебель, окно повернуто вдоль стены дома
func test_house_layout() -> void:
	var bad_win := 0
	var bad_dir := 0
	var furn_win := 0
	for sd in [777, 12345, 4242, 99]:
		var g := GameState.new_game(sd)
		var w := g.world
		for b in w.buildings:
			var x0: int = b[0]
			var y0: int = b[1]
			var x1: int = b[2]
			var y1: int = b[3]
			for y in range(y0, y1 + 1):
				for x in range(x0, x1 + 1):
					var tt := w.t(x, y)
					if W.is_window_like(tt):
						var top_bottom := (y == y0 or y == y1) and x > x0 and x < x1
						var sides := (x == x0 or x == x1) and y > y0 and y < y1
						if (top_bottom and not w.along_y(x, y)) or (sides and w.along_y(x, y)):
							bad_dir += 1
						var inn := Vector2i(x, y + 1) if y == y0 else (Vector2i(x, y - 1) if y == y1 else (Vector2i(x + 1, y) if x == x0 else Vector2i(x - 1, y)))
						var it := w.t(inn.x, inn.y)
						if it == W.WALL or W.is_door_like(it):
							bad_win += 1
						if W.is_furniture(it):
							furn_win += 1
	check("Дома: внутренняя стена не делит окно и не торчит наружу", bad_win == 0 and bad_dir == 0, "окон в стену: %d, повернуто поперёк: %d" % [bad_win, bad_dir])
	check("Дома: мебель не стоит вплотную к окну", furn_win == 0, "%d окон за мебелью" % furn_win)


## Одежда: надеть/снять без потерь и дублей, рюкзак добавляет вес, сохранение, защита
func test_wear() -> void:
	var g := _game()
	var p: GameState.Player = g.players[0]
	p.inv = [{"id": "bp_hiking", "n": 1}, {"id": "bp_school", "n": 1}, {"id": "leather", "n": 1}]
	p.wear = {}
	var base := p.max_weight()
	g.actions.append([0, {"a": "wear", "id": "bp_hiking"}])
	for i in 120:
		g.update(0.016)
	var cap1 := p.max_weight() - base
	g.actions.append([0, {"a": "wear", "id": "bp_school"}])
	for i in 120:
		g.update(0.016)
	var swapped: bool = p.wear.get("back", "") == "bp_school" and p.count("bp_hiking") == 1 and p.count("bp_school") == 0
	g.actions.append([0, {"a": "wear", "id": "pistol"}])      # не одежда
	g.actions.append([0, {"a": "wear", "id": "helmet"}])      # нет в инвентаре
	g.actions.append([0, {"a": "unwear", "slot": "nonsense"}])
	for i in 120:
		g.update(0.016)
	check("Одежда: рюкзак надевается, +вес, смена рюкзака возвращает прежний", cap1 >= 12.0 and swapped and not p.wear.has("head") and not p.wear.has("hand"),
		"+%.0f кг, слоты %s, инв %s" % [cap1, p.wear, p.inv])
	g.actions.append([0, {"a": "wear", "id": "leather"}])
	for i in 120:
		g.update(0.016)
	var d = JSON.parse_string(JSON.stringify(g.to_data()))
	var g2 := GameState.new(g.seed)
	g2.load_data(d)
	check("Одежда сохраняется и загружается", g2.players[0].wear == p.wear, "%s → %s" % [p.wear, g2.players[0].wear])
	g.actions.append([0, {"a": "unwear", "slot": "back"}])
	for i in 120:
		g.update(0.016)
	var total := p.count("bp_hiking") + p.count("bp_school") + (1 if p.wear.has("back") else 0)
	check("Одежда: снять — вещь возвращается в инвентарь, ничего не теряется и не множится", not p.wear.has("back") and total == 2, "рюкзаков %d" % total)
	# защита: в кожанке укусов в руки/торс заметно меньше
	var bites := [0, 0]
	for k in 2:
		var q := g.join_player("w%d" % k, "t")
		q.wear = {"torso": "leather"} if k == 1 else {}
		for n in 3000:
			var z := GameState.Zombie.new(50000 + n)
			q.body = Body.new()
			g._attack_player(z, q)
			for part in [Body.TORSO, Body.L_FORE, Body.R_FORE]:
				if not q.body.parts[part].wounds.is_empty():
					bites[k] += 1
	check("Одежда защищает: в кожаной куртке меньше ран на торсе и руках", bites[1] < bites[0] * 0.8, "без куртки %d, в куртке %d" % [bites[0], bites[1]])
