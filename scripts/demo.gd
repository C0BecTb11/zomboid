class_name Demo
extends RefCounted
## Демо-сцены для автоматических скриншотов.

static func create(kind: String) -> GameState:
	var g := GameState.new_game(12345)
	var p: GameState.Player = g.players[0]
	g.time = 11 * 60.0
	Items.add_to(p.inv, "bat", 1)
	p.eq = "bat"
	match kind:
		"inside", "loot", "body", "inv":
			_into_house(g, p)
		"night":
			g.time = 23 * 60.0
		"evening":
			g.time = 19 * 60.0 + 50.0
		"climb":
			_climb(g, p)
	if kind == "zombies" or kind == "night":
		for k in 7:
			var a := k * 0.9
			var z := GameState.Zombie.new(9000 + k)
			z.pos = p.pos + Vector2(cos(a), sin(a)) * (2.5 + k * 0.5)
			if not g.world.circle_blocked(z.pos.x, z.pos.y, GameState.ZOMBIE_R):
				z.rpos = z.pos
				z.face = (p.pos - z.pos).normalized()
				z.chase = 5.0
				g.zombies.append(z)
	if kind == "body" or kind == "inv":
		g.wound(p, Body.L_FORE, Body.BITE)
		g.wound(p, Body.R_HAND, Body.GLASS)
		g.wound(p, Body.TORSO, Body.SCRATCH)
		p.body.parts[Body.L_SHIN].bruise = 100.0
		g.wound(p, Body.R_THIGH, Body.LACERATION)
		p.body.parts[Body.R_THIGH].bandage = 1
		p.infection = 0.0
		for id in ["bandage", "rag", "disinfectant", "tweezers", "canned", "water", "pistol", "ammo", "planks", "hammer", "apple", "chips"]:
			Items.add_to(p.inv, id, 12 if id == "ammo" else 2)
	return g

static func _into_house(g: GameState, p: GameState.Player) -> void:
	var w := g.world
	var best := -1
	var bd := 1e9
	for y in range(1, w.h - 1):
		for x in range(1, w.w - 1):
			if w.t(x, y) != GameWorld.TILEFLOOR:
				continue
			var furn := false
			for k in 4:
				if GameWorld.is_furniture(w.t(x + GameWorld.DX4[k], y + GameWorld.DY4[k])):
					furn = true
			if not furn:
				continue
			var d := Vector2(x, y).distance_to(p.pos)
			if d < bd:
				bd = d
				best = w.idx(x, y)
	if best >= 0:
		p.pos = Vector2(best % w.w + 0.5, best / w.w + 0.5)
		p.rpos = p.pos
	var keep := []
	for z in g.zombies:
		if z.pos.distance_to(p.pos) >= 10.0:
			keep.append(z)
	g.zombies = keep

static func _climb(g: GameState, p: GameState.Player) -> void:
	var w := g.world
	var best := -1
	var bd := 1e9
	for i in w.tiles.size():
		if w.t_at(i) == GameWorld.WINDOW:
			var d := Vector2(i % w.w, i / w.w).distance_to(p.pos)
			if d < bd:
				bd = d
				best = i
	if best < 0:
		return
	w.set_idx(best, GameWorld.WINDOW_BROKEN)
	w.events.clear()
	var bx := best % w.w
	var by := best / w.w
	var out: Vector2
	if w.along_y(bx, by):
		out = Vector2(bx + 0.5, by - 0.5) if GameWorld.is_floor(w.t(bx, by + 1)) else Vector2(bx + 0.5, by + 1.5)
	else:
		out = Vector2(bx - 0.5, by + 0.5) if GameWorld.is_floor(w.t(bx + 1, by)) else Vector2(bx + 1.5, by + 0.5)
	p.pos = out + Vector2(1.2, 0.3)
	p.rpos = p.pos
	var keep := []
	for z in g.zombies:
		if z.pos.distance_to(p.pos) >= 12.0:
			keep.append(z)
	g.zombies = keep
	var z := GameState.Zombie.new(9100)
	var tg := w.climb_target(best, out.x, out.y)
	z.climb = 0.45
	z.cl_cell = best
	z.cl_from = out
	z.cl_to = tg
	z.face = (tg - out).normalized()
	w.climb_occ[best] = z.id
	z.pos = out.lerp(tg, 0.45)
	z.rpos = z.pos
	g.zombies.append(z)
	g.freeze = true
