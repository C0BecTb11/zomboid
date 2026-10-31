class_name GameState
extends RefCounted

const PLAYER_R := 0.3
const ZOMBIE_R := 0.3
const MAX_WEIGHT := 15.0
const MINUTES_PER_SEC := 1.0

class Player:
	var id := 0
	var pos := Vector2.ZERO
	var face := Vector2(1, 0)
	var hp := 100.0
	var hunger := 10.0
	var thirst := 10.0
	var stamina := 100.0
	var infection := 0.0
	var dead := false
	var kills := 0
	var eq := ""
	var inv: Array = []
	var body := Body.new()
	var move := Vector2.ZERO
	var run := false
	var cooldown := 0.0
	var connected := true
	var attack_anim := 0.0
	var sneaking := false
	var ta_act = null          # действие со временем (Dictionary) или null
	var ta_name := ""
	var ta_time := 0.0
	var ta_dur := 0.0
	var climb := -1.0
	var cl_from := Vector2.ZERO
	var cl_to := Vector2.ZERO
	var rpos := Vector2.ZERO   # позиция для отрисовки
	var name := ""
	var remote := false        # движение считает телефон друга (хост только принимает)
	var force_seq := 0         # хост сдвинул игрока сам — клиент должен принять позицию
	var move_budget := 1.0     # сколько клеток друг может пройти до следующего пакета
	var last_in_ms := 0
	var atk_seq := 0           # номер удара: по нему второй телефон запускает анимацию ровно один раз
	var atk_kind := 0          # 0 — оружие/кулак, 1 — добивание ногой
	var stomp_t := -1.0        # до удара стопой о землю
	var stomp_z := -1
	var wear := {}             # слот → id надетой вещи (в inv её нет)
	var snaps: Array = []      # снимки [время, x, y, fx, fy, climb] для плавной отрисовки чужого игрока
	var rface := Vector2(1, 0)
	var rclimb := -1.0
	func _init(pid: int = 0) -> void:
		id = pid
	func weapon() -> Dictionary: return Items.def(eq if eq != "" else "fists")
	func weapon_id() -> String: return eq if eq != "" else "fists"
	## Надетое весит втрое меньше (как в PZ): одежда на теле не тянет руки
	func weight() -> float:
		var w := Items.weight(inv)
		for sl in wear:
			w += Items.def(wear[sl])["w"] * 0.3
		return w
	## Сколько можно нести без замедления: рюкзак добавляет
	func max_weight() -> float:
		return MAX_WEIGHT + (Items.def(wear["back"])["cap"] if wear.has("back") else 0.0)
	## Насколько закрыта часть тела (0..1): лучшая из надетых вещей
	func protection(part: int) -> float:
		var best := 0.0
		for sl in wear:
			if part in Items.SLOT_PARTS.get(sl, []):
				best = maxf(best, Items.def(wear[sl])["prot"])
		return best
	func count(item: String) -> int: return Items.count(inv, item)
	func take(item: String, n: int = 1) -> bool:
		var ok := Items.take(inv, item, n)
		if ok and Items.count(inv, item) == 0 and eq == item:
			eq = ""
		return ok
	func bleeding() -> bool: return body.bleeding()
	func ta_progress() -> float: return clampf(ta_time / ta_dur, 0.0, 1.0) if ta_dur > 0.0 else 0.0

## Часы отправителя: какое мгновение его мира показывать сейчас, чтобы всегда было два снимка для плавного перехода
class NetClock:
	var off := INF        # (наше время − время отправителя) для самого быстрого пакета
	var jit := 25.0       # разброс прихода пакетов, мс
	var gap := 50.0       # как часто отправитель шлёт снимки (на слабом телефоне — реже)
	var last_ts := -1.0
	func sample(ts: float, now: float) -> void:
		if last_ts >= 0.0 and ts > last_ts:
			var g := ts - last_ts
			gap = lerpf(gap, g, 0.3 if g > gap else 0.05)
		last_ts = maxf(last_ts, ts)
		var s := now - ts
		if off == INF or s < off:
			off = s
		else:
			off += (s - off) * 0.003       # медленно следуем за уходом часов
		var dev := s - off
		jit = lerpf(jit, dev, 0.15 if dev > jit else 0.01)
	func delay() -> float:
		return clampf(maxf(60.0, gap * 1.3) + jit * 1.6, 75.0, 450.0)
	func render_time(now: float) -> float:
		return 0.0 if off == INF else now - off - delay()

class Zombie:
	var id := 0
	var pos := Vector2.ZERO
	var face := Vector2(0, 1)
	var hp := 100.0
	var chase := 0.0
	var target := Vector2(NAN, NAN)
	var wander_t := 0.0
	var wander := Vector2.ZERO
	var sense_t := 0.0
	var attack_cd := 0.0
	var stagger := 0.0
	var bash_t := 0.0
	var speed := 1.0
	var down := 0.0
	var climb := -1.0
	var cl_cell := -1
	var cl_from := Vector2.ZERO
	var cl_to := Vector2.ZERO
	var rpos := Vector2.ZERO
	var snaps: Array = []
	var rface := Vector2(0, 1)
	var rclimb := -1.0
	func _init(zid: int = 0) -> void:
		id = zid
	func skin() -> int: return id % 6

var seed := 0
var world := GameWorld.new()
var players: Array = []
var zombies: Array = []
var corpses: Array = []        # [x, y, zombie]
var time := 8 * 60.0
var rnd := RandomNumberGenerator.new()
var next_zid := 1
var flow_t := 0.0
var is_replica := false
var freeze := false
var actions: Array = []        # [pid, action Dictionary]
var messages := {}             # pid → Array[String]
var shots: Array = []          # [x1, y1, x2, y2, жизнь]
var fx: Array = []             # [тип, x, y, параметр] — 1 кровь, 2 смерть зомби, 3 ранен игрок, 4 выстрел
var fx_net: Array = []
var uids := {}                 # постоянный ID телефона друга → номер персонажа

func _init(s: int = 0) -> void:
	seed = s
	rnd.seed = s ^ Time.get_ticks_usec()

static func new_game(s: int) -> GameState:
	var g := GameState.new(s)
	var start := g.world.generate(s)
	var p := Player.new(0)
	p.pos = start
	p.rpos = start
	p.inv = [{"id": "water", "n": 1}, {"id": "chips", "n": 1}, {"id": "bandage", "n": 1}]
	p.wear = {"legs": "jeans"}
	g.players.append(p)
	g.spawn_zombies(65, start)
	g.world.compute_buildings()
	return g

func day() -> int: return int(time / 1440.0) + 1
func hour_f() -> float: return fmod(time, 1440.0) / 60.0
func clock() -> String:
	var m := int(fmod(time, 1440.0))
	return "%02d:%02d" % [m / 60, m % 60]

## 0 — светло, 1 — ночь
func darkness() -> float:
	var hh := hour_f()
	if hh < 5.0: return 1.0
	if hh < 7.0: return 1.0 - (hh - 5.0) / 2.0
	if hh < 19.0: return 0.0
	if hh < 21.5: return (hh - 19.0) / 2.5
	return 1.0

func player(pid: int) -> Player:
	for p: Player in players:
		if p.id == pid:
			return p
	return null

func msg(pid: int, text: String) -> void:
	if not messages.has(pid):
		messages[pid] = []
	messages[pid].append(text)

func add_fx(tp: int, x: float, y: float, a: float) -> void:
	var f := [tp, x, y, a]
	fx.append(f)
	fx_net.append(f)
	if fx_net.size() > 60:
		fx_net.pop_front()

func spawn_zombies(n: int, avoid: Vector2) -> void:
	var tries := 0
	var made := 0
	while made < n and tries < n * 50:
		tries += 1
		var p := Vector2(rnd.randi() % world.w + 0.5, rnd.randi() % world.h + 0.5)
		if p.distance_to(avoid) < 16.0 or world.circle_blocked(p.x, p.y, ZOMBIE_R):
			continue
		var near_player := false
		for pl: Player in players:
			if not pl.dead and pl.pos.distance_to(p) < 16.0:
				near_player = true
		if near_player:
			continue
		var z := Zombie.new(next_zid)
		next_zid += 1
		z.pos = p
		z.rpos = p
		z.speed = 0.85 + rnd.randf() * 0.45
		z.hp = 80.0 + rnd.randf() * 50.0
		zombies.append(z)
		made += 1

## Друг подключился: тот же телефон получает своего прежнего персонажа
func join_player(uid: String, pname: String) -> Player:
	var pid := -1
	if uids.has(uid):
		pid = int(uids[uid])
	else:
		pid = 1
		for p in players:
			pid = maxi(pid, p.id + 1)
		uids[uid] = pid
	var p := player(pid)
	if p != null and p.dead:
		players.erase(p)
		p = null
	if p == null:
		p = _spawn_friend(pid)
	p.connected = true
	p.remote = true
	p.name = pname
	p.move = Vector2.ZERO
	p.force_seq += 1
	p.snaps.clear()
	return p

func _spawn_friend(pid: int) -> Player:
	var host: Player = players[0] if players.size() > 0 else null
	var p := Player.new(pid)
	p.pos = (host.pos if host else Vector2(50, 50)) + Vector2(0.6, 0)
	if world.circle_blocked(p.pos.x, p.pos.y, PLAYER_R):
		p.pos = host.pos if host else Vector2(50, 50)
	p.rpos = p.pos
	p.inv = [{"id": "water", "n": 1}, {"id": "bandage", "n": 1}]
	p.wear = {"legs": "jeans"}
	players.append(p)
	return p

## Хозяин мира погиб, а персонаж друга жив: мир не удаляется, хозяин начинает новым персонажем рядом с другом
func revive_host() -> bool:
	var h := player(0)
	if h != null and not h.dead:
		return false
	var near := Vector2(50, 50) if h == null else h.pos
	for q in players:
		if q.id != 0 and not q.dead:
			near = q.pos
			break
	if h != null:
		players.erase(h)
	var p := Player.new(0)
	p.pos = unstick(near + Vector2(-0.6, 0), PLAYER_R)
	p.rpos = p.pos
	p.inv = [{"id": "water", "n": 1}, {"id": "bandage", "n": 1}]
	p.wear = {"legs": "jeans"}
	players.insert(0, p)
	return true

func leave_player(pid: int) -> void:
	var p := player(pid)
	if p == null:
		return
	p.connected = false
	p.move = Vector2.ZERO
	p.ta_act = null
	if p.climb >= 0.0:
		p.pos = p.cl_to
		p.climb = -1.0
		_release_climb(p)

# ================== СИМУЛЯЦИЯ ==================
func update(dt: float) -> void:
	if is_replica or freeze:
		return
	while actions.size() > 0:
		var a: Array = actions.pop_front()
		var p := player(a[0])
		if p != null and not p.dead:
			_do_action(p, a[1])
	time += dt * MINUTES_PER_SEC
	for p: Player in players:
		_update_player(p, dt)
	flow_t -= dt
	if flow_t <= 0.0:
		flow_t = 0.5
		var src := []
		for p: Player in players:
			if not p.dead and p.connected:
				src.append(Vector2i(floori(p.pos.x), floori(p.pos.y)))
		world.compute_flow(src)
	for z: Zombie in zombies:
		_update_zombie(z, dt)
	_separate()
	var i := zombies.size() - 1
	while i >= 0:
		var z: Zombie = zombies[i]
		if z.hp <= 0.0:
			if z.climb >= 0.0 and world.climb_occ.get(z.cl_cell, -999) == z.id:
				world.climb_occ.erase(z.cl_cell)
			zombies.remove_at(i)
			corpses.append([z.pos.x, z.pos.y, true, snappedf(atan2(z.face.x, z.face.y), 0.01), z.id])
			if corpses.size() > 80:
				corpses.pop_front()
		i -= 1
	_age_shots(dt)
	if players.size() > 0 and zombies.size() < 60 and rnd.randf() < dt * 0.025:
		spawn_zombies(1, players[0].pos)

func _age_shots(dt: float) -> void:
	var i := shots.size() - 1
	while i >= 0:
		shots[i][4] -= dt
		if shots[i][4] <= 0.0:
			shots.remove_at(i)
		i -= 1

func tick_replica(dt: float) -> void:
	_age_shots(dt)
	for p in players:
		p.attack_anim = maxf(0.0, p.attack_anim - dt * anim_rate(p))
		p.cooldown -= dt

## Скорость анимации удара: добивание ногой дольше взмаха
static func anim_rate(p: Player) -> float:
	return 2.2 if p.atk_kind == 1 else 4.0

## Каким будет удар: ногой по лежачему или оружием (одинаково считают хост и телефон друга)
func attack_kind(p: Player) -> Array:
	var w := p.weapon()
	if w["kind"] == Items.Kind.GUN:
		return [0, null]
	var reach: float = w["range"] + ZOMBIE_R
	var downed: Zombie = null
	var dd := INF
	for z: Zombie in zombies:
		var d: float = z.pos.distance_to(p.pos)
		if z.down > 0.0 and d < 1.2 and d < dd and _reachable(p, z):
			dd = d
			downed = z
	for z: Zombie in zombies:
		if z.down <= 0.0 and z.pos.distance_to(p.pos) < reach and _in_front(p, z):
			return [0, null]
	return [1, downed] if downed != null else [0, null]

## Телефон друга: анимация своего удара сразу, не дожидаясь хоста
func predict_attack(p: Player) -> void:
	if p.dead or p.climb >= 0.0 or p.cooldown > 0.0:
		return
	var k := attack_kind(p)
	p.cooldown = p.weapon()["cd"]
	p.atk_kind = k[0]
	p.attack_anim = 1.0
	if k[1] != null:
		p.face = (k[1].pos - p.pos).normalized()

## Снимок для плавной отрисовки (время отправителя, мс)
static func push_snap(e, ts: float, pos: Vector2, face: Vector2, climb: float) -> void:
	var sn: Array = e.snaps
	if sn.size() > 0 and ts <= sn[sn.size() - 1][0]:
		return
	sn.append([ts, pos.x, pos.y, face.x, face.y, climb])
	if sn.size() > 30:
		sn.pop_front()

## Положение на момент t (время отправителя): между двумя снимками, без забегания вперёд
static func sample_snaps(e, t: float) -> void:
	var sn: Array = e.snaps
	if sn.is_empty():
		e.rpos = e.pos
		e.rface = e.face
		e.rclimb = e.climb
		return
	var last: Array = sn[sn.size() - 1]
	if t >= last[0]:
		e.rpos = Vector2(last[1], last[2])
		e.rface = Vector2(last[3], last[4])
		e.rclimb = last[5]
		return
	var i := sn.size() - 2
	while i > 0 and sn[i][0] > t:
		i -= 1
	var a: Array = sn[i]
	var b: Array = sn[i + 1]
	var f := clampf((t - a[0]) / maxf(1.0, b[0] - a[0]), 0.0, 1.0)
	var pa := Vector2(a[1], a[2])
	var pb := Vector2(b[1], b[2])
	e.rpos = pb if pa.distance_to(pb) > 3.0 else pa.lerp(pb, f)
	var fa := Vector2(a[3], a[4])
	var fb := Vector2(b[3], b[4])
	e.rface = Vector2.from_angle(lerp_angle(fa.angle(), fb.angle(), f)) if fa.length() > 0.01 and fb.length() > 0.01 else fb
	if a[5] >= 0.0 and b[5] >= 0.0:
		e.rclimb = lerpf(a[5], b[5], f)
	else:
		e.rclimb = b[5] if f > 0.5 else a[5]

func _cell_at(p: Vector2) -> int:
	return world.idx(clampi(floori(p.x), 0, world.w - 1), clampi(floori(p.y), 0, world.h - 1))

func move_speed(p: Player, mag: float) -> Array:
	var heavy := p.weight() > p.max_weight()
	var running := p.run and p.stamina > 1.0 and mag > 0.1
	p.sneaking = not running and mag >= 0.1 and mag <= 0.6
	var speed := 1.25 if p.sneaking else 2.3
	if running:
		speed = 3.9
	if heavy:
		speed *= 0.6
		running = false
	if p.stamina < 10.0:
		speed *= 0.8
	speed *= p.body.leg_penalty()
	return [speed, running]

## Перемещение игрока. authoritative=false — предсказание на телефоне друга (без ран и шума)
func move_player(p: Player, dt: float, authoritative: bool) -> Array:
	var mag := p.move.length()
	var ms := move_speed(p, mag)
	var speed: float = ms[0]
	var running: bool = ms[1]
	if p.climb >= 0.0:
		p.climb += dt / 1.3
		p.pos = p.cl_from.lerp(p.cl_to, minf(p.climb, 1.0))
		if p.climb >= 1.0:
			p.climb = -1.0
			_release_climb(p)
		return [false, 0.0]
	if mag > 0.1:
		var n := p.move / maxf(1.0, mag)
		p.face = p.move / mag
		var before := p.pos
		p.pos = _move_circle(p.pos, n * speed * dt, PLAYER_R)
		if p.pos.distance_to(before) < speed * dt * 0.5:
			_try_climb(p, n, authoritative)
	return [running, mag]

func _release_climb(p: Player) -> void:
	for k in world.climb_occ.keys():
		if world.climb_occ[k] == -1 - p.id:
			world.climb_occ.erase(k)

func _update_player(p: Player, dt: float) -> void:
	if p.dead or not p.connected:
		return
	p.cooldown -= dt
	p.attack_anim = maxf(0.0, p.attack_anim - dt * anim_rate(p))
	_tick_stomp(p, dt)
	var running := false
	var mag := 0.0
	if p.remote and p.climb >= 0.0:
		# перелезание друга хост ведёт сам: нельзя «зависнуть» в окне неуязвимым
		p.climb += dt / 1.3
		p.pos = p.cl_from.lerp(p.cl_to, minf(p.climb, 1.0))
		if p.climb >= 1.0:
			p.climb = -1.0
			_release_climb(p)
			push_player(p, p.cl_to)
	elif p.remote:
		# позицию прислал телефон друга; здесь только последствия движения
		mag = p.move.length() if p.climb < 0.0 else 0.0
		running = move_speed(p, mag)[1] if mag > 0.1 else false
	else:
		var r := move_player(p, dt, true)
		running = r[0]
		mag = r[1]
	if p.climb >= 0.0:
		_finish_player(p, dt, false, 0.0)
		return
	if mag > 0.1:
		if running:
			p.stamina -= dt * 12.0
			if rnd.randf() < dt * 1.2:
				noise(p.pos, 6.0, p)
		elif not p.sneaking and rnd.randf() < dt * 0.5:
			noise(p.pos, 2.5, p)
		if p.ta_act != null:
			p.ta_act = null
			msg(p.id, "Прервано")
	if p.ta_act != null:
		p.ta_time += dt
		if p.ta_time >= p.ta_dur:
			var a = p.ta_act
			p.ta_act = null
			_perform(p, a)
	_finish_player(p, dt, running, mag)

## Ввод с телефона друга: позиция, взгляд, перелезание. Всё проверяется — подделку хост откатывает
func remote_input(p: Player, o: Dictionary) -> void:
	p.move = Vector2(float(o.get("mx", 0.0)), float(o.get("my", 0.0))).limit_length(1.0)
	p.run = bool(o.get("run", false))
	if p.dead:
		return
	var now := Time.get_ticks_msec()
	var dt_in := 0.1 if p.last_in_ms == 0 else clampf((now - p.last_in_ms) / 1000.0, 0.0, 1.0)
	p.last_in_ms = now
	p.move_budget = minf(p.move_budget + dt_in * 3.9 * 1.25, 2.5)
	if int(o.get("fs", -1)) != p.force_seq:
		return    # друг ещё не получил наш принудительный сдвиг
	var np := Vector2(float(o.get("x", p.pos.x)), float(o.get("y", p.pos.y)))
	var fc := Vector2(float(o.get("fx", p.face.x)), float(o.get("fy", p.face.y)))
	if fc.length() > 0.01:
		p.face = fc.normalized()
	var cl := float(o.get("cl", -1.0))
	if p.climb >= 0.0:
		# лезет в окно: позиция только на отрезке перелезания
		if cl < 0.0:
			p.climb = -1.0
			_release_climb(p)
			if np.distance_to(p.cl_to) < 0.4 and not world.circle_blocked(np.x, np.y, PLAYER_R - 0.05):
				p.pos = np
			else:
				push_player(p, p.cl_to)
			p.move_budget = 1.0
			return
		return      # ход перелезания ведёт хост сам (см. _update_player)
	if cl >= 0.0:
		_remote_start_climb(p, o)
		return
	var d := np.distance_to(p.pos)
	if d > p.move_budget + 0.25 or world.circle_blocked(np.x, np.y, PLAYER_R - 0.05) or not _path_free(p.pos, np):
		push_player(p, p.pos)       # подделка или рассинхрон — вернуть друга туда, где он был
		return
	p.move_budget -= d
	p.pos = np

## Можно ли пройти от a до b, не проходя сквозь стены и закрытые двери (шагами по 0.15)
func _path_free(a: Vector2, b: Vector2) -> bool:
	var d := b - a
	var steps := ceili(d.length() / 0.15)
	if steps == 0:
		return true
	var st := d / steps
	var c := a
	for i in steps:
		var n := _move_circle(c, st, PLAYER_R - 0.05)
		if n.distance_to(c + st) > 0.1:
			return false
		c = n
	return true

func _remote_start_climb(p: Player, o: Dictionary) -> void:
	var to := Vector2(float(o.get("ctx", p.pos.x)), float(o.get("cty", p.pos.y)))
	var mid := (p.pos + to) * 0.5
	var wc := world.idx(clampi(floori(mid.x), 0, world.w - 1), clampi(floori(mid.y), 0, world.h - 1))
	var ok := world.t_at(wc) == GameWorld.WINDOW_BROKEN and world.barr_at(wc) == 0 and not world.climb_occ.has(wc)
	if ok:
		var c := Vector2(wc % world.w + 0.5, wc / world.w + 0.5)
		var tg := world.climb_target(wc, p.pos.x, p.pos.y)
		ok = c.distance_to(p.pos) < 1.15 and tg.distance_to(to) < 0.3 and not world.circle_blocked(tg.x, tg.y, PLAYER_R)
	if not ok:
		push_player(p, p.pos)
		return
	p.climb = 0.0
	p.cl_from = p.pos
	p.cl_to = to
	world.climb_occ[wc] = -1 - p.id
	p.ta_act = null
	if rnd.randf() < 0.25:
		var part: int = [Body.L_HAND, Body.R_HAND, Body.L_FORE, Body.R_FORE, Body.L_SHIN, Body.R_SHIN, Body.L_THIGH, Body.R_THIGH][rnd.randi() % 8]
		wound(p, part, Body.GLASS)
		msg(p.id, "Порезался о стекло: " + Body.NAMES[part].to_lower())
	noise(p.pos, 4.0, p)

func push_player(p: Player, to: Vector2) -> void:
	p.pos = to
	p.force_seq += 1

func _try_climb(p: Player, n: Vector2, authoritative: bool = true) -> void:
	var wx := floori(p.pos.x + n.x * 0.6)
	var wy := floori(p.pos.y + n.y * 0.6)
	if not world.inside(wx, wy):
		return
	var wc := world.idx(wx, wy)
	if world.t_at(wc) != GameWorld.WINDOW_BROKEN or world.barr_at(wc) != 0 or world.climb_occ.has(wc):
		return
	if Vector2(wx + 0.5, wy + 0.5).distance_to(p.pos) > 1.05:
		return
	var tg := world.climb_target(wc, p.pos.x, p.pos.y)
	if world.circle_blocked(tg.x, tg.y, PLAYER_R):
		return
	p.climb = 0.0
	p.cl_from = p.pos
	p.cl_to = tg
	p.face = (tg - p.pos).normalized()
	if not authoritative:
		return
	world.climb_occ[wc] = -1 - p.id
	p.ta_act = null
	if rnd.randf() < 0.25:
		var part: int = [Body.L_HAND, Body.R_HAND, Body.L_FORE, Body.R_FORE, Body.L_SHIN, Body.R_SHIN, Body.L_THIGH, Body.R_THIGH][rnd.randi() % 8]
		wound(p, part, Body.GLASS)
		msg(p.id, "Порезался о стекло: " + Body.NAMES[part].to_lower())
	noise(p.pos, 4.0, p)

func _finish_player(p: Player, dt: float, running: bool, mag: float) -> void:
	if not running:
		p.stamina = minf(100.0, p.stamina + dt * (6.0 if mag > 0.1 else 12.0))
	p.hunger = minf(100.0, p.hunger + dt * 0.035)
	p.thirst = minf(100.0, p.thirst + dt * 0.052)
	if p.hunger >= 90.0 or p.thirst >= 90.0:
		p.hp -= dt * 0.4
	elif p.hunger < 60.0 and p.thirst < 60.0 and not p.bleeding() and p.infection <= 0.0 and not p.body.wound_infected():
		p.hp = minf(100.0, p.hp + dt * 0.08)
	p.hp -= p.body.update(dt)
	if p.infection > 0.0:
		var before := p.infection
		p.infection += dt
		if p.infection > 900.0:
			p.hp -= dt * 0.25
		if before < 420.0 and p.infection >= 420.0:
			msg(p.id, "Тебя знобит. Рана даёт о себе знать…")
	if p.hp <= 0.0:
		p.hp = 0.0
		p.dead = true
		if p.climb >= 0.0:
			p.climb = -1.0
			_release_climb(p)      # окно освобождается — иначе в него больше никто не пролезет
		corpses.append([p.pos.x, p.pos.y, false, snappedf(atan2(p.face.x, p.face.y), 0.01), -1 - p.id])
		msg(p.id, "Ты погиб. Прожито дней: %d" % (day() - 1))

func _move_circle(pos: Vector2, d: Vector2, r: float) -> Vector2:
	if world.circle_blocked(pos.x, pos.y, r):
		# оказался внутри препятствия — выталкиваем в ближайшее свободное место, сквозь стены не пускаем
		return unstick(pos, r)
	var n := pos
	if not world.circle_blocked(n.x + d.x, n.y, r):
		n.x += d.x
	if not world.circle_blocked(n.x, n.y + d.y, r):
		n.y += d.y
	return n

## Ближайшая свободная точка (кольцами до 1.5 клетки)
func unstick(pos: Vector2, r: float) -> Vector2:
	for ring in range(1, 16):
		var dist := ring * 0.1
		for k in 16:
			var c := pos + Vector2.from_angle(TAU * k / 16.0) * dist
			if not world.circle_blocked(c.x, c.y, r):
				return c
	return pos

func noise(at: Vector2, radius: float, src: Player) -> void:
	for z: Zombie in zombies:
		var d: float = z.pos.distance_to(at)
		if d > radius:
			continue
		if src != null and d < radius * 0.6:
			z.chase = maxf(z.chase, 6.0)
		z.target = at + Vector2(rnd.randf() - 0.5, rnd.randf() - 0.5) * 2.0

func _nearest_player(at: Vector2) -> Player:
	var best: Player = null
	var bd := INF
	for p: Player in players:
		if p.dead or not p.connected:
			continue
		var d: float = p.pos.distance_to(at)
		if d < bd:
			bd = d
			best = p
	return best

func _update_zombie(z: Zombie, dt: float) -> void:
	z.attack_cd -= dt
	if z.climb >= 0.0:
		z.climb += dt / 2.4
		z.pos = z.cl_from.lerp(z.cl_to, minf(z.climb, 1.0))
		if z.climb >= 1.0:
			z.climb = -1.0
			if world.climb_occ.get(z.cl_cell, -999) == z.id:
				world.climb_occ.erase(z.cl_cell)
			if world.circle_blocked(z.pos.x, z.pos.y, ZOMBIE_R):
				z.pos = unstick(z.pos, ZOMBIE_R)
			z.down = 1.3 + rnd.randf()
		return
	if z.down > 0.0:
		z.down -= dt
		return
	if z.stagger > 0.0:
		z.stagger -= dt
		return
	var near := _nearest_player(z.pos)
	var nd: float = near.pos.distance_to(z.pos) if near else 999.0
	z.sense_t -= dt
	if z.sense_t <= 0.0 and near != null and nd < 22.0:
		z.sense_t = 0.3
		var sight := (11.0 - 6.0 * darkness()) * (0.5 if near.sneaking else 1.0)
		if nd < sight and world.los(z.pos.x, z.pos.y, near.pos.x, near.pos.y):
			var dot := (near.pos - z.pos).dot(z.face) / maxf(nd, 0.01)
			if dot > -0.2 or nd < 3.0 or z.chase > 0.0:
				z.chase = 10.0
	var speed := 0.9 * z.speed
	var g := Vector2.ZERO
	if z.chase > 0.0 and near != null:
		z.chase -= dt
		speed = 1.25 * z.speed
		if nd < 1.6 and world.los(z.pos.x, z.pos.y, near.pos.x, near.pos.y):
			g = near.pos - z.pos
		else:
			var cx := floori(z.pos.x)
			var cy := floori(z.pos.y)
			var best: int = world.flow[world.idx(clampi(cx, 0, world.w - 1), clampi(cy, 0, world.h - 1))]
			var bi := -1
			for k in 4:
				var nx: int = cx + GameWorld.DX4[k]
				var ny: int = cy + GameWorld.DY4[k]
				if not world.inside(nx, ny):
					continue
				var f: int = world.flow[world.idx(nx, ny)]
				if f < best:
					best = f
					bi = world.idx(nx, ny)
			if bi >= 0:
				var bp := Vector2(bi % world.w + 0.5, bi / world.w + 0.5)
				if world.t_at(bi) == GameWorld.WINDOW_BROKEN and world.barr_at(bi) == 0:
					if bp.distance_to(z.pos) < 1.05:
						z.face = (bp - z.pos).normalized()
						var tg := world.climb_target(bi, z.pos.x, z.pos.y)
						if not world.climb_occ.has(bi):
							world.climb_occ[bi] = z.id
							z.cl_cell = bi
							z.climb = 0.0
							z.cl_from = z.pos
							z.cl_to = tg
							z.face = (tg - z.pos).normalized()
						return
				elif world.bashable(bi) and bp.distance_to(z.pos) < 1.05:
					_bash(z, bi, dt)
					z.face = (bp - z.pos).normalized()
					return
				g = bp - z.pos
			else:
				g = near.pos - z.pos
		if nd < 0.8 and z.attack_cd <= 0.0:
			_attack_player(z, near)
			near.ta_act = null
	elif not is_nan(z.target.x):
		g = z.target - z.pos
		if g.length() < 0.6:
			z.target = Vector2(NAN, NAN)
	else:
		if nd > 40.0 and rnd.randf() > 0.3:
			return
		z.wander_t -= dt
		if z.wander_t <= 0.0:
			z.wander_t = 2.0 + rnd.randf() * 5.0
			if rnd.randf() < 0.35:
				z.wander = Vector2.ZERO
			else:
				z.wander = Vector2.from_angle(rnd.randf() * TAU)
		g = z.wander
		speed = 0.35 * z.speed
	if g.length() > 0.01:
		z.face = g.normalized()
		var before := z.pos
		z.pos = _move_circle(z.pos, z.face * speed * dt, ZOMBIE_R)
		if z.pos == before and z.chase <= 0.0:
			z.wander_t = 0.0

func _bash(z: Zombie, cell: int, dt: float) -> void:
	z.bash_t -= dt
	if z.bash_t > 0.0:
		return
	z.bash_t = 1.4
	var hp: float = world.tile_hp.get(cell, 60.0) - (6.0 + rnd.randf() * 6.0)
	noise(Vector2(cell % world.w + 0.5, cell / world.w + 0.5), 7.0, null)
	if hp > 0.0:
		world.tile_hp[cell] = hp
		return
	var b := world.barr_at(cell)
	if b > 0:
		if b == 1:
			world.barr.erase(cell)
		else:
			world.barr[cell] = b - 1
		world.tile_hp[cell] = 80.0
		world.vis_dirty[cell] = true
		world.cost_dirty = true
	else:
		match world.t_at(cell):
			GameWorld.DOOR: world.set_idx(cell, GameWorld.DOOR_BROKEN)
			GameWorld.WINDOW: world.set_idx(cell, GameWorld.WINDOW_BROKEN)
		world.tile_hp.erase(cell)
	world.dirty_tiles[cell] = true

func _attack_player(z: Zombie, p: Player) -> void:
	z.attack_cd = 1.3
	if rnd.randf() > 0.55:
		return
	var part := Body.random_zombie_target(rnd)
	add_fx(3, p.pos.x, p.pos.y, p.id)
	var pname: String = Body.NAMES[part].to_lower()
	var r := rnd.randf()
	# одежда: укус или царапина не прошли сквозь ткань — только ушиб
	if r >= 0.45 and rnd.randf() < p.protection(part):
		p.hp -= 3.0
		p.body.parts[part].bruise = 120.0
		msg(p.id, "Одежда спасла: " + pname)
		return
	if r < 0.45:
		p.hp -= 3.0 + rnd.randf() * 4.0
		p.body.parts[part].bruise = 120.0
		msg(p.id, "Ушиб: " + pname)
	elif r < 0.75:
		p.hp -= 5.0
		wound(p, part, Body.SCRATCH)
		msg(p.id, "Царапина: " + pname)
	elif r < 0.90:
		p.hp -= 8.0
		wound(p, part, Body.LACERATION)
		msg(p.id, "Рваная рана: " + pname)
	else:
		p.hp -= 12.0
		wound(p, part, Body.BITE)
		msg(p.id, "УКУС: " + pname + "!")

func wound(p: Player, part: int, tp: int) -> void:
	p.body.parts[part].wounds.append(Body.Wound.new(tp))
	p.body.parts[part].bandage_age += 60.0
	if p.infection <= 0.0 and rnd.randf() < Body.ZOMBIE_INFECT[tp]:
		p.infection = 0.01

func _separate() -> void:
	# сортировка по X и проход окном — вместо перебора всех пар
	zombies.sort_custom(func(a: Zombie, b: Zombie) -> bool: return a.pos.x < b.pos.x)
	var n := zombies.size()
	for i in n:
		var a: Zombie = zombies[i]
		for j in range(i + 1, n):
			var b: Zombie = zombies[j]
			var d := b.pos - a.pos
			if d.x > 0.6:
				break
			if absf(d.y) > 0.6:
				continue
			var l := d.length()
			if l < 0.55 and l > 0.0001:
				var push := d / l * ((0.55 - l) * 0.5)
				if a.climb < 0.0 and not world.circle_blocked(a.pos.x - push.x, a.pos.y - push.y, ZOMBIE_R):
					a.pos -= push
				if b.climb < 0.0 and not world.circle_blocked(b.pos.x + push.x, b.pos.y + push.y, ZOMBIE_R):
					b.pos += push

# ================== ДЕЙСТВИЯ ==================
func _timing(p: Player, a: Dictionary) -> Array:
	match a["a"]:
		"interact":
			var c: int = a["c"]
			var tt := world.t_at(c) if c >= 0 and c < world.tiles.size() else -1
			if GameWorld.is_container(tt) and not world.containers.has(c):
				return [0.9, "Обыск"]
		"barr":
			return [2.6, "Заколачиваю"]
		"wear":
			return [1.2 if Items.def(a["id"])["slot"] in ["torso", "legs", "back"] else 0.6, "Надеваю"]
		"unwear":
			return [0.8, "Снимаю"]
		"treat":
			match a["w"]:
				"bandage", "rag": return [2.2, "Перевязка"]
				"disinfect": return [1.4, "Обработка"]
				"glass": return [3.0, "Достаю стекло"]
		"use":
			var k: int = Items.def(a["id"])["kind"]
			if k == Items.Kind.FOOD: return [1.6, "Ем"]
			if k == Items.Kind.DRINK: return [1.2, "Пью"]
			if k == Items.Kind.MED: return [0.8, "Таблетки"] if a["id"] == "pills" else [2.2, "Лечение"]
	return [0.0, ""]

## Действие должно иметь правильные поля нужных типов, иначе отбрасывается
static func valid_action(a) -> bool:
	if not (a is Dictionary) or not (a.get("a") is String):
		return false
	match a["a"]:
		"attack":
			return true
		"interact", "barr":
			return a.get("c") is int or a.get("c") is float
		"use", "drop", "wear":
			return a.get("id") is String and Items.DEFS.has(a["id"])
		"unwear":
			return a.get("slot") in Items.SLOTS
		"take", "put":
			return (a.get("c") is int or a.get("c") is float) and a.get("id") is String and Items.DEFS.has(a["id"])
		"treat":
			return (a.get("part") is int or a.get("part") is float) and a.get("w") in ["bandage", "rag", "unbandage", "disinfect", "glass"]
	return false

func _do_action(p: Player, a: Dictionary) -> void:
	if not valid_action(a):
		return
	for k in ["c", "part"]:
		if a.has(k):
			a[k] = int(a[k])
	if a["a"] == "attack":
		p.ta_act = null
	if p.climb >= 0.0:
		return
	var tm := _timing(p, a)
	if tm[0] > 0.0:
		if p.ta_act != null:
			return
		p.ta_act = a
		p.ta_dur = tm[0]
		p.ta_time = 0.0
		p.ta_name = tm[1]
		return
	_perform(p, a)

func _perform(p: Player, a: Dictionary) -> void:
	match a["a"]:
		"attack": _attack(p)
		"interact": _interact(p, a["c"])
		"barr": _barricade(p, a["c"])
		"use": _use(p, a["id"])
		"wear": _wear(p, a["id"])
		"unwear": _unwear(p, a["slot"])
		"treat": treat(p, a["part"], a["w"])
		"drop":
			var n := p.count(a["id"])
			if n <= 0:
				return
			p.take(a["id"], n)
			var cell := _cell_at(p.pos)
			if not world.containers.has(cell):
				world.containers[cell] = []
			Items.add_to(world.containers[cell], a["id"], n)
			world.dirty_cont[cell] = true
			world.vis_dirty[cell] = true
		"take":
			var c: int = a["c"]
			if not near_cell(p, c, 1.8) or not world.containers.has(c):
				return
			var list: Array = world.containers[c]
			var n := Items.count(list, a["id"])
			if n <= 0:
				return
			Items.take(list, a["id"], n)
			Items.add_to(p.inv, a["id"], n)
			world.dirty_cont[c] = true
			world.vis_dirty[c] = true
			if p.weight() > p.max_weight():
				msg(p.id, "Слишком тяжело — ты замедлился")
		"put":
			var c2: int = a["c"]
			if not near_cell(p, c2, 1.8):
				return
			var n2 := p.count(a["id"])
			if n2 <= 0:
				return
			p.take(a["id"], n2)
			if not world.containers.has(c2):
				world.containers[c2] = []
			Items.add_to(world.containers[c2], a["id"], n2)
			world.dirty_cont[c2] = true
			world.vis_dirty[c2] = true

func near_cell(p: Player, cell: int, dist: float) -> bool:
	if cell < 0 or cell >= world.tiles.size():
		return false
	var c := Vector2(cell % world.w + 0.5, cell / world.w + 0.5)
	var tt := world.t_at(cell)
	if tt == GameWorld.CAR:
		c.x += 0.5
	elif tt == GameWorld.CAR_V:
		c.y += 0.5
	if GameWorld.is_car(tt):
		return c.distance_to(p.pos) < dist + 0.9
	return c.distance_to(p.pos) < dist

func _attack(p: Player) -> void:
	if p.cooldown > 0.0:
		return
	var w := p.weapon()
	p.cooldown = w["cd"]
	p.attack_anim = 1.0
	p.atk_kind = 0
	p.atk_seq = (p.atk_seq + 1) % 100000
	if w["kind"] == Items.Kind.GUN:
		if not p.take("ammo", 1):
			msg(p.id, "Нет патронов")
			p.cooldown = 0.3
			return
		var target: Zombie = null
		var best := INF
		for z: Zombie in zombies:
			var d: float = z.pos.distance_to(p.pos)
			if d >= w["range"] or not world.los(p.pos.x, p.pos.y, z.pos.x, z.pos.y, true):
				continue
			var score := d - 3.0 * (z.pos - p.pos).dot(p.face) / maxf(d, 0.01)
			if score < best:
				best = score
				target = z
		var e: Vector2 = p.pos + p.face * w["range"]
		if target != null:
			var d2: float = target.pos.distance_to(p.pos)
			p.face = (target.pos - p.pos).normalized()
			e = target.pos
			if rnd.randf() < 0.85 - d2 * 0.02:
				_hit_zombie(p, target, w["dmg"] * (0.8 + rnd.randf() * 0.4))
		shots.append([p.pos.x + p.face.x * 0.4, p.pos.y + p.face.y * 0.4, e.x, e.y, 0.08])
		add_fx(4, p.pos.x + p.face.x * 0.55, p.pos.y + p.face.y * 0.55, p.id)
		noise(p.pos, w["noise"], p)
		return
	p.stamina = maxf(0.0, p.stamina - 4.0)
	var reach: float = w["range"] + ZOMBIE_R
	# добить лежачего
	var downed: Zombie = null
	var dd := INF
	for z: Zombie in zombies:
		var d: float = z.pos.distance_to(p.pos)
		if z.down > 0.0 and d < 1.2 and d < dd and _reachable(p, z):
			dd = d
			downed = z
	var standing_front := false
	for z: Zombie in zombies:
		if z.down <= 0.0 and z.pos.distance_to(p.pos) < reach and _in_front(p, z):
			standing_front = true
	if downed != null and not standing_front:
		# добивание ногой: замах, урон в момент удара стопой
		p.face = (downed.pos - p.pos).normalized()
		p.atk_kind = 1
		p.stomp_t = STOMP_HIT
		p.stomp_z = downed.id
		p.cooldown = maxf(p.cooldown, 0.5)
		return
	var tz: Zombie = null
	var bd := INF
	for z: Zombie in zombies:
		var d: float = z.pos.distance_to(p.pos)
		if z.down <= 0.0 and d < reach and _in_front(p, z) and d < bd and _reachable(p, z):
			bd = d
			tz = z
	if tz == null:
		for z: Zombie in zombies:
			var d: float = z.pos.distance_to(p.pos)
			if z.down <= 0.0 and d < reach and d < bd and _reachable(p, z):
				bd = d
				tz = z
	if tz != null:
		p.face = (tz.pos - p.pos).normalized()
		var weak := 0.6 if p.stamina < 10.0 else 1.0
		_hit_zombie(p, tz, w["dmg"] * weak * p.body.arm_penalty() * (0.8 + rnd.randf() * 0.4))
		tz.pos = _move_circle(tz.pos, p.face * 0.35, ZOMBIE_R)
		tz.stagger = 0.45
		var kd := 0.3 if p.weapon_id() == "fists" else (0.35 if w["dmg"] >= 35.0 else 0.12)
		if tz.hp > 0.0 and rnd.randf() < kd * weak:
			tz.down = 2.2 + rnd.randf() * 1.5
			tz.stagger = 0.0
	noise(p.pos, w["noise"], p)

const STOMP_HIT := 0.24     # через сколько секунд после начала нога бьёт в землю

func _tick_stomp(p: Player, dt: float) -> void:
	if p.stomp_t < 0.0:
		return
	p.stomp_t -= dt
	if p.stomp_t > 0.0:
		return
	p.stomp_t = -1.0
	for z: Zombie in zombies:
		if z.id == p.stomp_z and z.hp > 0.0 and z.pos.distance_to(p.pos) < 1.5:
			var dmg := maxf(28.0, p.weapon()["dmg"] * 1.6) * p.body.leg_penalty()
			_hit_zombie(p, z, dmg)
			z.down = maxf(z.down, 0.6)
			noise(p.pos, 3.0, p)
			break

func _in_front(p: Player, z: Zombie) -> bool:
	var d: float = z.pos.distance_to(p.pos)
	return (z.pos - p.pos).dot(p.face) / maxf(d, 0.01) > 0.35

func _reachable(p: Player, z: Zombie) -> bool:
	return world.los(p.pos.x, p.pos.y, z.pos.x, z.pos.y)

func _hit_zombie(p: Player, z: Zombie, dmg: float) -> void:
	z.hp -= dmg
	z.chase = 10.0
	add_fx(1, z.pos.x, z.pos.y, (z.pos - p.pos).angle())
	if z.hp <= 0.0:
		p.kills += 1
		add_fx(2, z.pos.x, z.pos.y, 0.0)

func _interact(p: Player, cell: int) -> void:
	if not near_cell(p, cell, 1.8):
		return
	var tt := world.t_at(cell)
	if world.barr_at(cell) > 0:
		msg(p.id, "Заколочено")
	elif tt == GameWorld.DOOR:
		world.set_idx(cell, GameWorld.DOOR_OPEN)
		noise(p.pos, 3.0, p)
	elif tt == GameWorld.DOOR_OPEN:
		var c := Vector2(cell % world.w + 0.5, cell / world.w + 0.5)
		# стоишь в проёме — шаг назад на свою сторону
		if absf(p.pos.x - c.x) < 0.5 + PLAYER_R and absf(p.pos.y - c.y) < 0.5 + PLAYER_R:
			var ay := world.along_y(cell % world.w, cell / world.w)
			var np := p.pos
			if ay:
				np = Vector2(clampf(p.pos.x, c.x - 0.5 + PLAYER_R + 0.02, c.x + 0.5 - PLAYER_R - 0.02), c.y + (-0.82 if p.pos.y < c.y else 0.82))
			else:
				np = Vector2(c.x + (-0.82 if p.pos.x < c.x else 0.82), clampf(p.pos.y, c.y - 0.5 + PLAYER_R + 0.02, c.y + 0.5 - PLAYER_R - 0.02))
			if not world.circle_blocked(np.x, np.y, PLAYER_R):
				push_player(p, np)
		var blocked := false
		for o: Player in players:
			if not o.dead and absf(o.pos.x - c.x) < 0.6 and absf(o.pos.y - c.y) < 0.6:
				blocked = true
		for z: Zombie in zombies:
			if absf(z.pos.x - c.x) < 0.55 and absf(z.pos.y - c.y) < 0.55:
				blocked = true
		if blocked:
			msg(p.id, "Что-то мешает закрыть")
		else:
			world.set_idx(cell, GameWorld.DOOR)
			noise(p.pos, 3.0, p)
	elif tt == GameWorld.WINDOW:
		world.set_idx(cell, GameWorld.WINDOW_BROKEN)
		world.tile_hp.erase(cell)
		noise(p.pos, 12.0, p)
		if p.eq == "" and not p.wear.has("hands") and rnd.randf() < 0.5:
			wound(p, Body.R_HAND if rnd.randi() % 2 == 0 else Body.R_FORE, Body.GLASS)
			msg(p.id, "Разбил окно рукой — в ране стекло")
		else:
			msg(p.id, "Ты разбил окно")
	elif GameWorld.is_container(tt) or world.containers.has(cell):
		world.open_container(cell, rnd)

func can_barricade(p: Player, cell: int) -> bool:
	if cell < 0:
		return false
	var tt := world.t_at(cell)
	var ok := tt == GameWorld.DOOR or tt == GameWorld.DOOR_BROKEN or GameWorld.is_window_like(tt)
	return ok and world.barr_at(cell) < 3 and p.count("hammer") > 0 and p.count("planks") > 0

func _barricade(p: Player, cell: int) -> void:
	if not near_cell(p, cell, 1.8) or not can_barricade(p, cell):
		return
	var c := Vector2(cell % world.w + 0.5, cell / world.w + 0.5)
	var lim := 0.5 + PLAYER_R
	for o: Player in players:
		if not o.dead and absf(o.pos.x - c.x) < lim and absf(o.pos.y - c.y) < lim:
			msg(p.id, "Отойди от проёма")
			return
	for z: Zombie in zombies:
		if absf(z.pos.x - c.x) < 0.5 + ZOMBIE_R and absf(z.pos.y - c.y) < 0.5 + ZOMBIE_R:
			msg(p.id, "В проёме зомби")
			return
	p.take("planks", 1)
	world.barr[cell] = world.barr_at(cell) + 1
	world.cost_dirty = true
	world.tile_hp[cell] = 80.0
	world.dirty_tiles[cell] = true
	world.vis_dirty[cell] = true
	noise(p.pos, 9.0, p)
	msg(p.id, "Баррикада: %d/3" % world.barr_at(cell))

func _need_bandage(bp: Body.Part) -> int:
	if bp.bleeding(): return 3
	if not bp.wounds.is_empty() and bp.bandage == 0: return 2
	if bp.dirty() and not bp.wounds.is_empty(): return 1
	return 0

## Надеть: вещь уходит из рук в слот, прежняя вещь этого слота — обратно в инвентарь
func _wear(p: Player, id: String) -> void:
	var d := Items.def(id)
	if d["kind"] != Items.Kind.WEAR or p.count(id) <= 0:
		return
	var sl: String = d["slot"]
	if not p.take(id):
		return
	if p.wear.has(sl):
		Items.add_to(p.inv, p.wear[sl], 1)
	p.wear[sl] = id
	msg(p.id, "Надето: " + d["name"].to_lower())

func _unwear(p: Player, sl: String) -> void:
	if not p.wear.has(sl):
		return
	var id: String = p.wear[sl]
	p.wear.erase(sl)
	Items.add_to(p.inv, id, 1)
	if sl == "back" and p.weight() > p.max_weight():
		msg(p.id, "Без рюкзака тяжело — ты замедлился")

func _use(p: Player, id: String) -> void:
	var d := Items.def(id)
	if p.count(id) <= 0:
		return
	match d["kind"]:
		Items.Kind.FOOD, Items.Kind.DRINK:
			p.take(id)
			p.hunger = clampf(p.hunger - d["hunger"], 0.0, 100.0)
			p.thirst = clampf(p.thirst - d["thirst"], 0.0, 100.0)
		Items.Kind.MED:
			match id:
				"bandage", "rag":
					var best := -1
					var bs := 0
					for k in Body.COUNT:
						var s := _need_bandage(p.body.parts[k])
						if s > bs:
							bs = s
							best = k
					if best < 0:
						msg(p.id, "Перевязывать нечего")
					else:
						treat(p, best, id)
				"disinfectant":
					var part := -1
					for k in Body.COUNT:
						for w in p.body.parts[k].wounds:
							if not w.disinfected:
								part = k
					if part < 0:
						msg(p.id, "Нет ран для обработки")
					else:
						treat(p, part, "disinfect")
				"tweezers":
					var part2 := -1
					for k in Body.COUNT:
						if p.body.parts[k].has_glass():
							part2 = k
					if part2 < 0:
						msg(p.id, "Осколков нет")
					else:
						treat(p, part2, "glass")
				_:
					p.take(id)
					p.hp = minf(100.0, p.hp + d["heal"])
					msg(p.id, "Стало легче")
		Items.Kind.MELEE, Items.Kind.GUN:
			p.eq = "" if p.eq == id else id
		Items.Kind.WEAR:
			_wear(p, id)

func treat(p: Player, part: int, what: String) -> void:
	if part < 0 or part >= Body.COUNT:
		return
	var bp: Body.Part = p.body.parts[part]
	var pname: String = Body.NAMES[part].to_lower()
	match what:
		"bandage", "rag":
			if bp.wounds.is_empty():
				msg(p.id, "Здесь нечего перевязывать")
				return
			if bp.bandage != 0 and not bp.dirty():
				msg(p.id, "Повязка ещё чистая")
				return
			if not p.take(what):
				msg(p.id, "Нет: " + Items.def(what)["name"])
				return
			if bp.has_glass():
				msg(p.id, "Сначала стоило достать стекло…")
			bp.bandage = 1 if what == "bandage" else 2
			bp.bandage_age = 0.0
			msg(p.id, "Перевязано: " + pname)
		"unbandage":
			bp.bandage = 0
			bp.bandage_age = 0.0
		"disinfect":
			var need := false
			for w in bp.wounds:
				if not w.disinfected:
					need = true
			if not need:
				msg(p.id, "Обрабатывать нечего")
				return
			if not p.take("disinfectant"):
				msg(p.id, "Нужно дезинфицирующее средство")
				return
			for w in bp.wounds:
				w.disinfected = true
				w.infection = 0.0
			p.hp = maxf(1.0, p.hp - 1.0)
			msg(p.id, "Рана обработана: " + pname + ". Щиплет!")
		"glass":
			if p.count("tweezers") <= 0:
				msg(p.id, "Нужен пинцет")
				return
			for w in bp.wounds:
				if w.glass:
					w.glass = false
					w.bleed = maxf(w.bleed, 30.0)
					msg(p.id, "Осколок извлечён: " + pname)
					return
			msg(p.id, "Осколков нет: " + pname)

# ================== ЦЕЛИ ДЛЯ КНОПОК ==================
func find_interact(p: Player) -> int:
	var best := -1
	var bd := 9.0
	var px := floori(p.pos.x)
	var py := floori(p.pos.y)
	for y in range(py - 1, py + 2):
		for x in range(px - 1, px + 2):
			if not world.inside(x, y):
				continue
			var i := world.idx(x, y)
			var tt := world.t_at(i)
			if tt == GameWorld.CAR_TAIL:
				i = world.car_head(i)
				if i < 0:
					continue
				tt = world.t_at(i)
			var useful := GameWorld.is_container(tt) or tt == GameWorld.DOOR or tt == GameWorld.DOOR_OPEN or tt == GameWorld.WINDOW
			if not useful and world.containers.has(i) and not world.containers[i].is_empty():
				useful = true
			if not useful:
				continue
			var c := Vector2(x + 0.5, y + 0.5)
			var d := c.distance_to(p.pos)
			if d > 1.6:
				continue
			d -= 0.4 * (c - p.pos).dot(p.face) / maxf(d, 0.01)
			if d < bd:
				bd = d
				best = i
	return best

func find_barricade(p: Player) -> int:
	var best := -1
	var bd := 9.0
	var px := floori(p.pos.x)
	var py := floori(p.pos.y)
	for y in range(py - 1, py + 2):
		for x in range(px - 1, px + 2):
			if not world.inside(x, y):
				continue
			var i := world.idx(x, y)
			if not can_barricade(p, i):
				continue
			var d := Vector2(x + 0.5, y + 0.5).distance_to(p.pos)
			if d < 1.6 and d < bd:
				bd = d
				best = i
	return best

# ================== СОХРАНЕНИЕ ==================
func player_data(p: Player) -> Dictionary:
	return {"id": p.id, "x": snappedf(p.pos.x, 0.01), "y": snappedf(p.pos.y, 0.01), "fx": snappedf(p.face.x, 0.01), "fy": snappedf(p.face.y, 0.01),
		"hp": snappedf(p.hp, 0.01), "hu": snappedf(p.hunger, 0.01), "th": snappedf(p.thirst, 0.01), "st": snappedf(p.stamina, 0.01),
		"body": p.body.to_data(), "inf": snappedf(p.infection, 0.01), "dead": p.dead, "k": p.kills, "eq": p.eq,
		"inv": Items.copy(p.inv), "aa": snappedf(p.attack_anim, 0.01), "con": p.connected, "sn": p.sneaking,
		"cl": snappedf(p.climb, 0.01), "tn": p.ta_name if p.ta_act != null else "", "tp": snappedf(p.ta_progress(), 0.01),
		"nm": p.name, "fs": p.force_seq, "wr": p.wear.duplicate(),
		"cfx": snappedf(p.cl_from.x, 0.01), "cfy": snappedf(p.cl_from.y, 0.01), "ctx": snappedf(p.cl_to.x, 0.01), "cty": snappedf(p.cl_to.y, 0.01)}

func player_from(o: Dictionary, p: Player) -> void:
	p.pos = Vector2(float(o.get("x", p.pos.x)), float(o.get("y", p.pos.y)))
	p.face = Vector2(float(o.get("fx", 1.0)), float(o.get("fy", 0.0)))
	p.hp = float(o.get("hp", 100.0))
	p.hunger = float(o.get("hu", 10.0))
	p.thirst = float(o.get("th", 10.0))
	p.stamina = float(o.get("st", 100.0))
	p.body.from_data(o.get("body"))
	p.infection = float(o.get("inf", 0.0))
	p.dead = bool(o.get("dead", false))
	p.kills = int(o.get("k", 0))
	p.eq = o.get("eq", "")
	p.inv.clear()
	for s in o.get("inv", []):
		p.inv.append({"id": s["id"], "n": int(s["n"])})
	p.attack_anim = o.get("aa", 0.0)
	p.connected = o.get("con", true)
	p.sneaking = o.get("sn", false)
	p.climb = float(o.get("cl", -1.0))
	p.cl_from = Vector2(float(o.get("cfx", p.pos.x)), float(o.get("cfy", p.pos.y)))
	p.cl_to = Vector2(float(o.get("ctx", p.pos.x)), float(o.get("cty", p.pos.y)))
	p.name = o.get("nm", "")
	p.wear = {}
	var wr = o.get("wr", {})
	if wr is Dictionary:
		for sl in wr:
			var wid := str(wr[sl])
			if sl in Items.SLOTS and Items.DEFS.has(wid) and Items.def(wid)["slot"] == sl:
				p.wear[sl] = wid
	p.force_seq = int(o.get("fs", 0))
	var tn: String = o.get("tn", "")
	if tn != "":
		p.ta_name = tn
		p.ta_dur = 1.0
		p.ta_time = o.get("tp", 0.0)
		if p.ta_act == null:
			p.ta_act = {"a": "remote"}
	else:
		p.ta_act = null

func _cont_data(d: Dictionary) -> Dictionary:
	var o := {}
	for k in d:
		o[str(k)] = Items.copy(d[k])
	return o

func to_data() -> Dictionary:
	var thp := []
	for k in world.tile_hp:
		thp.append([k, world.tile_hp[k]])
	var ba := []
	for k in world.barr:
		ba.append([k, world.barr[k]])
	var zs := []
	for z: Zombie in zombies:
		zs.append([z.id, snappedf(z.pos.x, 0.01), snappedf(z.pos.y, 0.01), snappedf(z.hp, 0.1)])
	var ps := []
	for p: Player in players:
		ps.append(player_data(p))
	return {"v": SAVE_VERSION, "seed": seed, "time": time, "uids": uids.duplicate(), "tiles": Marshalls.raw_to_base64(world.tiles),
		"thp": thp, "barr": ba, "cont": _cont_data(world.containers), "z": zs, "p": ps, "corpses": corpses.duplicate(true)}

const SAVE_VERSION := 3

## Проверка формата: без карты мира загружать нечего
static func valid_save(o) -> bool:
	if not (o is Dictionary) or not (o.get("tiles") is String):
		return false
	return Marshalls.base64_to_raw(o["tiles"]).size() == 100 * 100

## Старые версии: v2 (до ID друзей) — недостающие поля заполняются по умолчанию
static func migrate(o: Dictionary) -> Dictionary:
	var v := int(o.get("v", 1))
	if v < 3:
		if not o.has("uids"):
			o["uids"] = {}
	o["v"] = SAVE_VERSION
	return o

func load_data(o: Dictionary) -> void:
	o = migrate(o)
	time = float(o.get("time", 8 * 60.0))
	uids = o.get("uids", {}).duplicate() if o.get("uids") is Dictionary else {}
	var t := Marshalls.base64_to_raw(str(o.get("tiles", "")))
	for i in mini(t.size(), world.tiles.size()):
		world.tiles[i] = t[i]
	world.compute_buildings()
	world.cost_dirty = true
	world.tile_hp.clear()
	for e in o.get("thp", []):
		world.tile_hp[int(e[0])] = float(e[1])
	world.barr.clear()
	for e in o.get("barr", []):
		world.barr[int(e[0])] = int(e[1])
	world.containers.clear()
	var co: Dictionary = o.get("cont", {})
	for k in co:
		var l := []
		for s in co[k]:
			l.append({"id": s["id"], "n": int(s["n"])})
		world.containers[int(k)] = l
	zombies.clear()
	for e in o.get("z", []):
		var z := Zombie.new(int(e[0]))
		z.pos = Vector2(e[1], e[2])
		z.rpos = z.pos
		z.hp = e[3]
		z.speed = 0.85 + rnd.randf() * 0.45
		zombies.append(z)
		next_zid = maxi(next_zid, z.id + 1)
	players.clear()
	for e in o.get("p", []):
		var p := Player.new(int(e["id"]))
		player_from(e, p)
		if p.climb >= 0.0 and not is_replica:
			# сохранились посреди окна — ставим туда, куда он лез
			if not world.circle_blocked(p.cl_to.x, p.cl_to.y, PLAYER_R):
				p.pos = p.cl_to
			elif world.circle_blocked(p.pos.x, p.pos.y, PLAYER_R):
				p.pos = unstick(p.cl_from, PLAYER_R)
			p.climb = -1.0
		p.rpos = p.pos
		players.append(p)
	corpses.clear()
	for c in o.get("corpses", []):
		var ang := float(c[3]) if c.size() > 3 else deg_to_rad((int(float(c[0]) * 13 + float(c[1]) * 7) & 0x7FFFFFFF) % 360)
		corpses.append([float(c[0]), float(c[1]), bool(c[2]), ang, int(c[4]) if c.size() > 4 else -100000])

# ================== СЕТЕВЫЕ СНИМКИ ==================
## Частый лёгкий снимок (ненадёжный канал): позиции рядом с другом
func state_for(pid: int) -> Dictionary:
	var me := player(pid)
	var c: Vector2 = me.pos if me != null else Vector2(50, 50)
	var ps := PackedFloat32Array()
	for p in players:
		if not p.connected and p.id != 0:
			continue
		ps.append_array([p.id, p.pos.x, p.pos.y, p.face.x, p.face.y, float(p.atk_seq * 2 + p.atk_kind), 1.0 if p.sneaking else 0.0, p.climb, p.force_seq, p.hp, p.stamina])
	var zs := PackedFloat32Array()
	for z in zombies:
		if absf(z.pos.x - c.x) > 30.0 or absf(z.pos.y - c.y) > 30.0:
			continue
		zs.append_array([z.id, z.pos.x, z.pos.y, z.face.x, z.face.y, (1.0 if z.chase > 0.0 else 0.0) + (2.0 if z.down > 0.0 else 0.0), z.climb])
	var o := {"t": "st", "time": time, "ts": Time.get_ticks_msec(), "p": ps, "z": zs}
	if shots.size() > 0:
		o["sh"] = shots.duplicate(true)
	return o

## Редкий надёжный снимок: изменения мира, полные данные игроков, сообщения, эффекты
func events_for(pid: int, full_players: bool, corpses_changed: bool) -> Dictionary:
	var o := {"t": "ev"}
	if full_players:
		var ps := []
		for p in players:
			if p.connected or p.id == 0:
				ps.append(player_data(p))
		o["p"] = ps
	if fx_net.size() > 0:
		o["fx"] = fx_net.duplicate(true)
		fx_net.clear()
	if world.dirty_tiles.size() > 0:
		var a := []
		for i in world.dirty_tiles:
			a.append([i, world.t_at(i), world.barr_at(i)])
		o["tc"] = a
		world.dirty_tiles.clear()
	if world.dirty_cont.size() > 0:
		var co := {}
		for i in world.dirty_cont:
			co[str(i)] = Items.copy(world.containers.get(i, []))
		o["cc"] = co
		world.dirty_cont.clear()
	if corpses_changed:
		o["cp"] = corpses.slice(maxi(0, corpses.size() - 80))
	var m := pop_messages(pid)
	if m.size() > 0:
		o["m"] = m
	return o

func pop_messages(pid: int) -> Array:
	var m: Array = messages.get(pid, [])
	messages.erase(pid)
	return m

## Клиент: принять лёгкий снимок. Свою позицию не трогаем — её считает этот телефон
func apply_state(o: Dictionary, local_pid: int) -> void:
	time = o["time"]
	var ts := float(o.get("ts", 0))
	var ps: PackedFloat32Array = o["p"]
	var i := 0
	while i + 10 < ps.size():
		var pid := int(ps[i])
		var p := player(pid)
		if p == null:
			p = Player.new(pid)
			p.pos = Vector2(ps[i + 1], ps[i + 2])
			p.rpos = p.pos
			players.append(p)
		p.connected = true
		p.hp = ps[i + 9]
		p.stamina = ps[i + 10]
		var fs := int(ps[i + 8])
		var ak := int(ps[i + 5])
		var seq := ak >> 1
		if seq != p.atk_seq:
			p.atk_seq = seq
			if pid != local_pid:
				# новый удар: анимация с начала, один раз
				p.atk_kind = ak & 1
				p.attack_anim = 1.0
		if pid != local_pid:
			p.force_seq = fs
			p.pos = Vector2(ps[i + 1], ps[i + 2])
			p.face = Vector2(ps[i + 3], ps[i + 4])
			p.sneaking = ps[i + 6] > 0.5
			p.climb = ps[i + 7]
			push_snap(p, ts, p.pos, p.face, p.climb)
		elif fs != p.force_seq:
			# хост сдвинул нас сам (дверь, откат)
			p.force_seq = fs
			p.pos = Vector2(ps[i + 1], ps[i + 2])
			p.face = Vector2(ps[i + 3], ps[i + 4])
			p.climb = ps[i + 7]
			p.rpos = p.pos
		i += 11
	var by_id := {}
	for z in zombies:
		by_id[z.id] = z
	var next := []
	var zs: PackedFloat32Array = o["z"]
	var k := 0
	while k + 6 < zs.size():
		var zid := int(zs[k])
		var z: Zombie = by_id.get(zid)
		if z == null:
			z = Zombie.new(zid)
			z.rpos = Vector2(zs[k + 1], zs[k + 2])
		z.pos = Vector2(zs[k + 1], zs[k + 2])
		z.face = Vector2(zs[k + 3], zs[k + 4])
		var fl := int(zs[k + 5])
		z.chase = 1.0 if fl & 1 else 0.0
		z.down = 1.0 if fl & 2 else 0.0
		z.climb = zs[k + 6]
		push_snap(z, ts, z.pos, z.face, z.climb)
		next.append(z)
		k += 7
	zombies = next
	for s in o.get("sh", []):
		var dup := false
		for e in shots:
			if is_equal_approx(e[2], s[2]) and is_equal_approx(e[3], s[3]):
				dup = true
		if not dup:
			shots.append([s[0], s[1], s[2], s[3], 0.08])

## Клиент: принять события
func apply_events(o: Dictionary, local_pid: int) -> void:
	for e in o.get("p", []):
		var pid := int(e["id"])
		var p := player(pid)
		if p == null:
			p = Player.new(pid)
			p.pos = Vector2(e["x"], e["y"])
			p.rpos = p.pos
			players.append(p)
		var fresh := p.snaps.is_empty() and pid != local_pid
		var keep_pos := p.pos
		var keep_face := p.face
		var keep_climb := p.climb
		var keep_fs := p.force_seq
		var keep_aa := p.attack_anim
		var keep_sn := p.sneaking
		var keep_from := p.cl_from
		var keep_to := p.cl_to
		player_from(e, p)
		# движение и удары приходят быстрым каналом: надёжный пакет старше и не должен их откатывать
		p.attack_anim = keep_aa
		if not fresh and not (pid == local_pid and int(e.get("fs", 0)) != keep_fs):
			p.pos = keep_pos
			p.face = keep_face
			p.climb = keep_climb
			p.force_seq = keep_fs
			p.sneaking = keep_sn
			if pid == local_pid:
				p.cl_from = keep_from
				p.cl_to = keep_to
	for e in o.get("tc", []):
		var i := int(e[0])
		var nt := int(e[1])
		if world.t_at(i) != nt:
			world.events.append([i, world.t_at(i), nt])
			world.tiles[i] = nt
			world.cost_dirty = true
		var b := int(e[2])
		if b > 0:
			world.barr[i] = b
		else:
			world.barr.erase(i)
		world.vis_dirty[i] = true
	var cc: Dictionary = o.get("cc", {})
	for k in cc:
		var l := []
		for s in cc[k]:
			l.append({"id": s["id"], "n": int(s["n"])})
		world.containers[int(k)] = l
		world.vis_dirty[int(k)] = true
	for e in o.get("fx", []):
		fx.append(e)
	if o.has("cp"):
		corpses = o["cp"]
