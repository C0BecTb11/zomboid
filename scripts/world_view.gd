class_name WorldView
extends Node3D
## 3D-представление игры: строит город, ставит камеру и свет, анимирует людей и эффекты.

const W = preload("res://scripts/world.gd")
const VIS_R := 26

var world: GameWorld
var wm: WorldMesh
var mat_world: ShaderMaterial
var mat_fx: StandardMaterial3D
var mat_glow: StandardMaterial3D
var mesher := Mesher.new()

var chunk_nodes: Array = []
var chunk_dirty := PackedByteArray()
var roof_nodes: Array = []
var door_nodes := {}          # клетка → MeshInstance3D
var door_angle := {}
var door_mesh: ArrayMesh
var players := {}             # id → CharModel
var zombies := {}             # id → CharModel
var corpse_nodes: Array = []
var corpse_count := -1
var corpse_last := []
var walk := {}                # id → [фаза, x, y, скорость]

var cam: Camera3D
var sun: DirectionalLight3D
var env: Environment
var flashlight: SpotLight3D
var lamp_lights: Array = []
var flash_light: OmniLight3D
var target_node: MeshInstance3D
var shot_nodes: Array = []

var vis := PackedFloat32Array()
var vis_target := PackedByteArray()
var vis_bytes := PackedByteArray()
var vis_img: Image
var vis_tex: ImageTexture
var vis_t := 0.0
var inside_building := -1

var particles: Array = []     # [pos, vel, life, max, color, size]
var pm: MultiMeshInstance3D
var blood: Array = []         # [x, y(высота), z, rot, size]
var bm: MultiMeshInstance3D
var blood_dirty := false
var flashes: Array = []       # [x, z, life]

var cam_yaw := 35.0
var cam_x := NAN
var cam_z := 0.0
var shake := 0.0
var hurt_flash := 0.0
var vibrate := 0
var target := -1

func setup(w: GameWorld) -> void:
	world = w
	wm = WorldMesh.new(w)
	mat_world = ShaderMaterial.new()
	mat_world.shader = load("res://shaders/world.gdshader")
	mat_fx = StandardMaterial3D.new()
	mat_fx.vertex_color_use_as_albedo = true
	mat_fx.vertex_color_is_srgb = true
	mat_glow = StandardMaterial3D.new()
	mat_glow.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	mat_glow.albedo_color = Color(1.0, 0.92, 0.55)
	# окружение
	var we := WorldEnvironment.new()
	env = Environment.new()
	env.background_mode = Environment.BG_COLOR
	env.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	env.tonemap_mode = Environment.TONE_MAPPER_FILMIC
	env.tonemap_exposure = 1.05
	env.fog_enabled = true
	env.fog_density = 0.012
	we.environment = env
	add_child(we)
	sun = DirectionalLight3D.new()
	sun.shadow_enabled = true
	sun.directional_shadow_mode = DirectionalLight3D.SHADOW_ORTHOGONAL
	sun.directional_shadow_max_distance = 34.0
	sun.shadow_bias = 0.04
	sun.shadow_normal_bias = 1.0
	add_child(sun)
	cam = Camera3D.new()
	cam.fov = 32.0
	cam.near = 1.0
	cam.far = 140.0
	add_child(cam)
	cam.make_current()
	flashlight = SpotLight3D.new()
	flashlight.spot_range = 14.0
	flashlight.spot_angle = 24.0
	flashlight.spot_angle_attenuation = 0.6
	flashlight.light_color = Color(1.0, 0.93, 0.78)
	flashlight.shadow_enabled = false
	add_child(flashlight)
	for i in 6:
		var o := OmniLight3D.new()
		o.omni_range = 7.5
		o.light_color = Color(1.0, 0.72, 0.42)
		o.visible = false
		add_child(o)
		lamp_lights.append(o)
	flash_light = OmniLight3D.new()
	flash_light.omni_range = 7.0
	flash_light.light_color = Color(1.0, 0.8, 0.45)
	flash_light.light_energy = 2.5
	flash_light.visible = false
	add_child(flash_light)
	# видимость
	vis.resize(w.w * w.h)
	vis_target.resize(w.w * w.h)
	vis_bytes.resize(w.w * w.h)
	vis_img = Image.create_from_data(w.w, w.h, false, Image.FORMAT_L8, vis_bytes)
	vis_tex = ImageTexture.create_from_image(vis_img)
	mat_world.set_shader_parameter("vis_tex", vis_tex)
	mat_world.set_shader_parameter("map_size", float(w.w))
	# чанки
	for i in wm.chunks_x * wm.chunks_y:
		var n := MeshInstance3D.new()
		n.material_override = mat_world
		add_child(n)
		chunk_nodes.append(n)
	chunk_dirty.resize(chunk_nodes.size())
	chunk_dirty.fill(1)
	# крыши
	for bi in w.buildings.size():
		wm.build_roof(mesher, bi)
		var r := MeshInstance3D.new()
		r.mesh = mesher.commit()
		r.material_override = mat_world
		add_child(r)
		roof_nodes.append(r)
	# двери
	WorldMesh.build_door(mesher)
	door_mesh = mesher.commit()
	for i in w.tiles.size():
		var tt := w.t_at(i)
		if tt == W.DOOR or tt == W.DOOR_OPEN:
			_make_door(i)
	# частицы и кровь
	pm = MultiMeshInstance3D.new()
	var mm := MultiMesh.new()
	mm.transform_format = MultiMesh.TRANSFORM_3D
	mm.use_colors = true
	mm.mesh = BoxMesh.new()
	mm.instance_count = 500
	mm.visible_instance_count = 0
	pm.multimesh = mm
	pm.material_override = mat_fx
	pm.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	add_child(pm)
	bm = MultiMeshInstance3D.new()
	var mb := MultiMesh.new()
	mb.transform_format = MultiMesh.TRANSFORM_3D
	mb.use_colors = true
	var bq := BoxMesh.new()
	bq.size = Vector3(1, 0.004, 0.75)
	mb.mesh = bq
	mb.instance_count = 240
	mb.visible_instance_count = 0
	bm.multimesh = mb
	bm.material_override = mat_fx
	bm.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	add_child(bm)
	# подсветка цели
	target_node = MeshInstance3D.new()
	mesher.clear()
	var th := 0.05
	var fc := Color(1, 1, 1)
	mesher.aabb(0.05, 0, 0.05, 0.95, 0.03, 0.05 + th, fc)
	mesher.aabb(0.05, 0, 0.95 - th, 0.95, 0.03, 0.95, fc)
	mesher.aabb(0.05, 0, 0.05, 0.05 + th, 0.03, 0.95, fc)
	mesher.aabb(0.95 - th, 0, 0.05, 0.95, 0.03, 0.95, fc)
	target_node.mesh = mesher.commit()
	var tm := StandardMaterial3D.new()
	tm.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	target_node.material_override = tm
	target_node.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	add_child(target_node)
	for i in 4:
		var s := MeshInstance3D.new()
		var sm := BoxMesh.new()
		sm.size = Vector3(0.03, 0.03, 1.0)
		s.mesh = sm
		s.material_override = mat_glow
		s.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		s.visible = false
		add_child(s)
		shot_nodes.append(s)

func _make_door(i: int) -> void:
	var n := MeshInstance3D.new()
	n.mesh = door_mesh
	n.material_override = mat_world
	var x := i % world.w
	var y := i / world.w
	if world.along_y(x, y):
		n.position = Vector3(x + 0.08, 0.1, y + 0.5)
		n.set_meta("base", 0.0)
	else:
		n.position = Vector3(x + 0.5, 0.1, y + 0.08)
		n.set_meta("base", -90.0)
	add_child(n)
	door_nodes[i] = n

func _mark_dirty(i: int) -> void:
	var x := i % world.w
	var y := i / world.w
	for d in [Vector2i(0, 0), Vector2i(1, 0), Vector2i(-1, 0), Vector2i(0, 1), Vector2i(0, -1)]:
		var nx: int = x + d.x
		var ny: int = y + d.y
		if not world.inside(nx, ny):
			continue
		chunk_dirty[(ny / WorldMesh.CHUNK) * wm.chunks_x + nx / WorldMesh.CHUNK] = 1

# ================== ОБНОВЛЕНИЕ ==================
func update(dt: float, g: GameState, me: GameState.Player, local_pid: int) -> void:
	for i in world.vis_dirty:
		_mark_dirty(i)
	world.vis_dirty.clear()
	for ev in world.events:
		_on_tile_event(ev[0], ev[1], ev[2])
	world.events.clear()
	for f in g.fx:
		_on_fx(f, local_pid, me)
	g.fx.clear()
	# перестроить не более 2 чанков за кадр
	var built := 0
	for i in chunk_dirty.size():
		if chunk_dirty[i] == 1 and built < 2:
			wm.build_chunk(mesher, i % wm.chunks_x, i / wm.chunks_x)
			chunk_nodes[i].mesh = mesher.commit()
			chunk_dirty[i] = 0
			built += 1
	# двери
	for k in door_angle.keys():
		var tt := world.t_at(k)
		var tgt := 90.0 if tt == W.DOOR_OPEN else 0.0
		if tt != W.DOOR and tt != W.DOOR_OPEN:
			door_angle.erase(k)
			continue
		var v: float = door_angle[k]
		var step := dt * 400.0
		if absf(tgt - v) <= step:
			door_angle.erase(k)
		else:
			door_angle[k] = v + (step if tgt > v else -step)
	for k in door_nodes:
		var n: MeshInstance3D = door_nodes[k]
		var tt2 := world.t_at(k)
		n.visible = tt2 == W.DOOR or tt2 == W.DOOR_OPEN
		var ang: float = door_angle.get(k, 90.0 if tt2 == W.DOOR_OPEN else 0.0)
		n.rotation.y = deg_to_rad(n.get_meta("base") + ang)
	# частицы
	var i := particles.size() - 1
	while i >= 0:
		var p: Array = particles[i]
		p[2] -= dt
		if p[2] <= 0.0:
			particles.remove_at(i)
		else:
			p[0] += p[1] * dt
			p[1].y -= 9.0 * dt
			if p[0].y < 0.12:
				p[0].y = 0.12
				p[1] = Vector3(p[1].x * 0.6, 0, p[1].z * 0.6)
		i -= 1
	var j := flashes.size() - 1
	while j >= 0:
		flashes[j][2] -= dt
		if flashes[j][2] <= 0.0:
			flashes.remove_at(j)
		j -= 1
	# видимость
	vis_t -= dt
	if vis_t <= 0.0:
		vis_t = 0.12
		_compute_vis(g, me)
	var kk := minf(1.0, dt * 7.0)
	var px := floori(me.rpos.x)
	var py := floori(me.rpos.y)
	for y in range(maxi(0, py - VIS_R), mini(world.h, py + VIS_R + 1)):
		for x in range(maxi(0, px - VIS_R), mini(world.w, px + VIS_R + 1)):
			var vi := y * world.w + x
			var nv: float = vis[vi] + (vis_target[vi] - vis[vi]) * kk
			vis[vi] = nv
			vis_bytes[vi] = int(nv * 255.0)
	vis_img.set_data(world.w, world.h, false, Image.FORMAT_L8, vis_bytes)
	vis_tex.update(vis_img)
	var cpx := clampi(px, 0, world.w - 1)
	var cpy := clampi(py, 0, world.h - 1)
	var b: int = world.building_of[world.idx(cpx, cpy)]
	inside_building = b if (b >= 0 and world.t(cpx, cpy) != W.WALL) else -1
	# камера
	var tx := me.rpos.x + me.face.x * 0.6
	var tz := me.rpos.y + me.face.y * 0.6
	if is_nan(cam_x) or Vector2(tx - cam_x, tz - cam_z).length() > 6.0:
		cam_x = tx
		cam_z = tz
	var ck := minf(1.0, dt * 5.0)
	cam_x += (tx - cam_x) * ck
	cam_z += (tz - cam_z) * ck
	shake = maxf(0.0, shake - dt * 2.5)
	hurt_flash = maxf(0.0, hurt_flash - dt * 1.4)
	for p: GameState.Player in g.players:
		_walk_update(p.id, p.rpos, dt)
	for z: GameState.Zombie in g.zombies:
		_walk_update(100000 + z.id, z.rpos, dt)
	if walk.size() > 600:
		walk.clear()
	_place_scene(g, me)

func _walk_update(id: int, pos: Vector2, dt: float) -> void:
	if not walk.has(id):
		walk[id] = [randf() * 6.0, pos, 0.0]
	var st: Array = walk[id]
	var d: float = pos.distance_to(st[1])
	if d < 1.0:
		st[0] += d * 6.5
	st[2] = st[2] * 0.85 + (d / dt if dt > 0.0 else 0.0) * 0.15
	st[1] = pos

## Видимость лучами из глаз игрока
func _compute_vis(g: GameState, me: GameState.Player) -> void:
	vis_target.fill(0)
	var o := me.rpos
	var px := floori(o.x)
	var py := floori(o.y)
	if me.dead:
		for y in range(maxi(0, py - 20), mini(world.h, py + 21)):
			for x in range(maxi(0, px - 20), mini(world.w, px + 21)):
				vis_target[y * world.w + x] = 1
		return
	var R := 24.0 - 8.0 * g.darkness()
	for y in range(py - 1, py + 2):
		for x in range(px - 1, px + 2):
			if world.inside(x, y):
				vis_target[world.idx(x, y)] = 1
	var rays := 240
	for a in rays:
		var ang := TAU * a / rays
		var dir := Vector2(cos(ang), sin(ang))
		var maxr := R if dir.dot(me.face) > -0.3 else 2.2
		var r := 0.4
		while r < maxr:
			var c := o + dir * r
			var cx := floori(c.x)
			var cy := floori(c.y)
			if not world.inside(cx, cy):
				break
			vis_target[cy * world.w + cx] = 1
			if world.opaque(cx, cy):
				break
			r += 0.45

func _on_tile_event(i: int, from: int, to: int) -> void:
	var c := Vector3(i % world.w + 0.5, 0, i / world.w + 0.5)
	_mark_dirty(i)
	if (from == W.DOOR and to == W.DOOR_OPEN) or (from == W.DOOR_OPEN and to == W.DOOR):
		if not door_angle.has(i):
			door_angle[i] = 90.0 if from == W.DOOR_OPEN else 0.0
	if (to == W.DOOR or to == W.DOOR_OPEN) and not door_nodes.has(i):
		_make_door(i)
	if from == W.WINDOW and to == W.WINDOW_BROKEN:
		_burst(c + Vector3(0, 1.4, 0), 26, Color(0.75, 0.9, 0.97), 0.05, 3.4, 1.6)
	if W.is_door_like(from) and to == W.DOOR_BROKEN:
		_burst(c + Vector3(0, 1.0, 0), 22, Color(0.66, 0.48, 0.28), 0.07, 2.8, 1.8)

func _burst(at: Vector3, n: int, col: Color, size: float, speed: float, life: float, dir = null, spread: float = 0.9) -> void:
	for k in n:
		var a: float = randf() * TAU if dir == null else float(dir) + (randf() - 0.5) * 2.0 * spread
		var sp := speed * (0.3 + randf())
		particles.append([at, Vector3(cos(a) * sp, 1.0 + randf() * 2.5, sin(a) * sp), life * (0.6 + randf() * 0.6), life * 1.2, col, size * (0.6 + randf())])
	if particles.size() > 500:
		particles = particles.slice(particles.size() - 500)

func _ground_at(x: float, z: float) -> float:
	var tt := world.t(floori(x), floori(z))
	if tt == W.SIDEWALK:
		return 0.12
	if W.is_floor(tt) or W.is_door_like(tt) or W.is_window_like(tt):
		return 0.1
	return 0.0

func _add_blood(x: float, z: float, size: float) -> void:
	var bx := x + (randf() - 0.5) * 0.3
	var bz := z + (randf() - 0.5) * 0.3
	blood.append([bx, _ground_at(bx, bz) + 0.006 + randf() * 0.003, bz, randf() * TAU, size])
	if blood.size() > 220:
		blood.pop_front()
	blood_dirty = true

func _on_fx(f: Array, local_pid: int, me: GameState.Player) -> void:
	var x: float = f[1]
	var z: float = f[2]
	match int(f[0]):
		1:
			_burst(Vector3(x, 1.2, z), 14, Color(0.55, 0.05, 0.05), 0.04, 2.6, 0.7, f[3], 0.6)
			if randf() < 0.6:
				_add_blood(x + cos(f[3]) * 0.4, z + sin(f[3]) * 0.4, 0.35)
		2:
			_burst(Vector3(x, 0.8, z), 26, Color(0.48, 0.05, 0.05), 0.05, 2.2, 0.9)
			_add_blood(x, z, 0.8)
		3:
			_burst(Vector3(x, 1.2, z), 10, Color(0.63, 0.08, 0.08), 0.04, 2.0, 0.6)
			if randf() < 0.6:
				_add_blood(x, z, 0.4)
			if int(f[3]) == local_pid:
				hurt_flash = 1.0
				shake = maxf(shake, 0.5)
				vibrate = 60
		4:
			flashes.append([x, z, 0.1])
			_burst(Vector3(x, 1.2, z), 5, Color(1.0, 0.88, 0.45), 0.025, 4.0, 0.2, atan2(me.face.y, me.face.x), 0.3)
			if int(f[3]) == local_pid:
				shake = maxf(shake, 0.35)
				vibrate = 25

# ================== РАССТАНОВКА ==================
const SHIRTS := [0x6A5A48, 0xB0B0A6, 0x4A7A4A, 0x2A2A2E, 0x5A6A3A, 0x2E4A7A]
const PANTS := [0x3A3A44, 0x5A5040, 0x2E3A2E, 0x1E1E22, 0x4A5230, 0x1E2A44]
const ZSKIN := [0x8E9E7A, 0x9AA088, 0x86967A, 0xA0A68E]
const HAIRS := [0x3A2A1A, 0x8A8A84, 0x1A1A1A, 0x6A4A2A]

static func _zcols(id: int, dark: float = 1.0) -> Array:
	var k := id % 6
	return [WorldMesh.c(SHIRTS[k]).darkened(1.0 - dark), WorldMesh.c(PANTS[k]).darkened(1.0 - dark), WorldMesh.c(ZSKIN[id % 4]).darkened(1.0 - dark), WorldMesh.c(HAIRS[id % 4])]

static func _pcols(id: int) -> Array:
	if id == 0:
		return [WorldMesh.c(0x3A6EA5), WorldMesh.c(0x2E3440), WorldMesh.c(0xE0B090), WorldMesh.c(0x4A3020)]
	return [WorldMesh.c(0x4A9A5A), WorldMesh.c(0x3A3A44), WorldMesh.c(0xD8A47E), WorldMesh.c(0x2A2018)]

func _vis_at(p: Vector2) -> float:
	var ix := floori(p.x)
	var iz := floori(p.y)
	return vis[world.idx(ix, iz)] if world.inside(ix, iz) else 0.0

func _place_scene(g: GameState, me: GameState.Player) -> void:
	var dark := g.darkness()
	var hh := g.hour_f()
	# ---------- камера ----------
	var pitch := deg_to_rad(54.0)
	var yaw := deg_to_rad(cam_yaw)
	var D := 15.5
	var sh := shake * shake * 0.25
	var t := Vector3(cam_x + (randf() - 0.5) * sh, 0.6, cam_z + (randf() - 0.5) * sh)
	var eye := t + Vector3(sin(yaw) * cos(pitch) * D, sin(pitch) * D, cos(yaw) * cos(pitch) * D)
	cam.position = eye
	cam.look_at(t, Vector3.UP)
	# ---------- солнце, небо ----------
	var sd := Vector3(0.35, -0.85, -0.4)
	if hh >= 5.5 and hh <= 20.5:
		var az := PI * (hh - 6.0) / 14.0
		var el := maxf(0.0, sin(az))
		sd = Vector3(-cos(az) * 0.85, -(0.3 + 0.7 * el), -0.45)
	sd = sd.normalized()
	sun.look_at_from_position(t - sd * 40.0, t, Vector3.UP if absf(sd.y) < 0.99 else Vector3.FORWARD)
	var day_c := Color(1.0, 0.95, 0.86)
	var dusk_c := Color(1.0, 0.6, 0.36)
	var night_c := Color(0.42, 0.52, 0.85)
	var day_a := Color(0.56, 0.59, 0.66)
	var dusk_a := Color(0.34, 0.28, 0.33)
	var night_a := Color(0.11, 0.13, 0.21)
	var warm := 0.0
	if hh >= 5.5 and hh <= 20.5:
		var el2 := maxf(0.0, sin(PI * (hh - 6.0) / 14.0))
		warm = clampf(pow(1.0 - el2, 3.0), 0.0, 1.0) * 0.6
	var sc: Color
	var ac: Color
	if dark < 0.5:
		var k := maxf(dark * 2.0, warm)
		sc = day_c.lerp(dusk_c * 0.75, k)
		ac = day_a.lerp(dusk_a, k)
	else:
		var k2 := (dark - 0.5) * 2.0
		sc = (dusk_c * 0.75).lerp(night_c * 0.32, k2)
		ac = dusk_a.lerp(night_a, k2)
	sun.light_color = Color(sc.r, sc.g, sc.b)
	sun.light_energy = 1.25 * (1.0 - dark * 0.5)
	env.ambient_light_color = Color(ac.r, ac.g, ac.b)
	env.ambient_light_energy = 1.0
	var fog := Color(0.6, 0.64, 0.66).lerp(Color(0.015, 0.02, 0.035), dark)
	env.background_color = fog
	env.fog_light_color = fog
	env.fog_density = 0.004 + dark * 0.008
	# ---------- шейдер мира ----------
	mat_world.set_shader_parameter("player_pos", Vector3(me.rpos.x, 1.0, me.rpos.y))
	mat_world.set_shader_parameter("cam_back", Vector2(sin(yaw), cos(yaw)))
	mat_world.set_shader_parameter("cut", 0.0 if me.dead else 1.0)
	mat_world.set_shader_parameter("night", dark)
	# ---------- фонарик ----------
	var fl := smoothstep(0.2, 0.6, dark) if not me.dead else 0.0
	flashlight.visible = fl > 0.01
	if flashlight.visible:
		flashlight.light_energy = 7.0 * fl
		var fp := Vector3(me.rpos.x + me.face.x * 0.25, 1.35, me.rpos.y + me.face.y * 0.25)
		flashlight.look_at_from_position(fp, fp + Vector3(me.face.x, -0.3, me.face.y), Vector3.UP)
	# ---------- фонари ----------
	var lk := smoothstep(0.15, 0.5, dark)
	var near_l: Array = []
	if lk > 0.01:
		for l in wm.lamps:
			if absf(l[2] - t.x) < 20.0 and absf(l[3] - t.z) < 20.0:
				near_l.append(l)
		near_l.sort_custom(func(a, b): return Vector2(a[2] - t.x, a[3] - t.z).length() < Vector2(b[2] - t.x, b[3] - t.z).length())
	for i in lamp_lights.size():
		var o: OmniLight3D = lamp_lights[i]
		if i < near_l.size():
			o.visible = true
			o.position = Vector3(near_l[i][2], 2.7, near_l[i][3])
			o.light_energy = 0.9 * lk
		else:
			o.visible = false
	flash_light.visible = flashes.size() > 0
	if flash_light.visible:
		flash_light.position = Vector3(flashes[0][0], 1.3, flashes[0][1])
	# ---------- крыши ----------
	for bi in roof_nodes.size():
		roof_nodes[bi].visible = bi != inside_building
	# ---------- игроки ----------
	var alive := {}
	for p: GameState.Player in g.players:
		if p.dead or not p.connected:
			continue
		alive[p.id] = true
		if not players.has(p.id):
			var m := CharModel.new()
			m.setup(_pcols(p.id), false, false, p.id == 1)
			add_child(m)
			players[p.id] = m
		var cm: CharModel = players[p.id]
		var st: Array = walk.get(p.id, [0.0, p.rpos, 0.0])
		cm.position = Vector3(p.rpos.x, _ground_at(p.rpos.x, p.rpos.y), p.rpos.y)
		cm.rotation.y = atan2(p.face.x, p.face.y)
		cm.pose(st[0], 1.0 if st[2] > 0.3 else 0.0, p.attack_anim, p.weapon_id(), false, p.sneaking, false, p.climb)
	for id in players.keys():
		if not alive.has(id):
			players[id].queue_free()
			players.erase(id)
	# ---------- зомби ----------
	var seen := {}
	for z: GameState.Zombie in g.zombies:
		var d := Vector2(z.rpos.x - t.x, z.rpos.y - t.z)
		if absf(d.x) > VIS_R or absf(d.y) > VIS_R:
			continue
		var v := _vis_at(z.rpos)
		if v < 0.2 and z.climb < 0.0:
			continue
		seen[z.id] = true
		if not zombies.has(z.id):
			var zm := CharModel.new()
			zm.setup(_zcols(z.id), true, z.id % 2 == 0, false)
			add_child(zm)
			zombies[z.id] = zm
		var m2: CharModel = zombies[z.id]
		var st2: Array = walk.get(100000 + z.id, [0.0, z.rpos, 0.0])
		m2.position = Vector3(z.rpos.x, _ground_at(z.rpos.x, z.rpos.y), z.rpos.y)
		m2.rotation.y = atan2(z.face.x, z.face.y)
		var s := 0.95 + (z.id % 5) * 0.025
		m2.scale = Vector3(s, s, s)
		m2.pose(st2[0], 0.75 if st2[2] > 0.15 else 0.15, 0.0, "", z.down > 0.0, false, z.chase > 0.0, z.climb)
	for id in zombies.keys():
		if not seen.has(id):
			zombies[id].queue_free()
			zombies.erase(id)
	# ---------- трупы ----------
	var cl: Array = g.corpses
	if cl.size() != corpse_count or (cl.size() > 0 and cl[cl.size() - 1] != corpse_last):
		corpse_count = cl.size()
		corpse_last = cl[cl.size() - 1] if cl.size() > 0 else []
		for n in corpse_nodes:
			n.queue_free()
		corpse_nodes.clear()
		for c in cl:
			var seed := int(c[0] * 13 + c[1] * 7) & 0x7FFFFFFF
			var cm2 := CharModel.new()
			cm2.setup(_zcols(seed) if c[2] else _pcols(0), c[2], false, false, true)
			add_child(cm2)
			cm2.position = Vector3(c[0], _ground_at(c[0], c[1]) + 0.004, c[1])
			cm2.rotation.y = deg_to_rad(seed % 360)
			cm2.pose(0, 0, 0, "", true, false, false, -1.0)
			corpse_nodes.append(cm2)
			if blood.size() < 220:
				blood.append([c[0], _ground_at(c[0], c[1]) + 0.004, c[1] + 0.3, deg_to_rad(seed % 360), 1.0])
				blood_dirty = true
	# ---------- кровь ----------
	if blood_dirty:
		blood_dirty = false
		var mb: MultiMesh = bm.multimesh
		mb.visible_instance_count = blood.size()
		for bi in blood.size():
			var b: Array = blood[bi]
			var tr := Transform3D(Basis(Vector3.UP, b[3]).scaled(Vector3(b[4], 1, b[4])), Vector3(b[0], b[1], b[2]))
			mb.set_instance_transform(bi, tr)
			mb.set_instance_color(bi, Color(0.29, 0.03, 0.03))
	# ---------- частицы ----------
	var mp: MultiMesh = pm.multimesh
	mp.visible_instance_count = particles.size()
	for pi in particles.size():
		var p: Array = particles[pi]
		var k3: float = clampf(p[2] / p[3], 0.3, 1.0) * p[5]
		mp.set_instance_transform(pi, Transform3D(Basis.IDENTITY.scaled(Vector3(k3, k3, k3)), p[0]))
		mp.set_instance_color(pi, p[4])
	# ---------- выстрелы ----------
	for si in shot_nodes.size():
		var sn: MeshInstance3D = shot_nodes[si]
		if si < g.shots.size():
			var s2: Array = g.shots[si]
			var a := Vector3(s2[0], 1.2, s2[1])
			var b2 := Vector3(s2[2], 1.2, s2[3])
			sn.visible = true
			sn.position = (a + b2) * 0.5
			sn.look_at(b2, Vector3.UP)
			sn.scale = Vector3(1, 1, a.distance_to(b2))
		else:
			sn.visible = false
	# ---------- цель ----------
	target_node.visible = target >= 0
	if target >= 0:
		var tx2 := target % world.w
		var tz2 := target / world.w
		var sx := 1.0
		var sz := 1.0
		match world.t_at(target):
			W.CAR: sx = 2.0
			W.CAR_V: sz = 2.0
		target_node.position = Vector3(tx2, _ground_at(tx2 + 0.5, tz2 + 0.5) + 0.02, tz2)
		target_node.scale = Vector3(sx, 1, sz)
		var pulse := 0.5 + 0.5 * sin(Time.get_ticks_msec() / 180.0)
		(target_node.material_override as StandardMaterial3D).albedo_color = Color(0.72, 0.6, 0.25).lerp(Color(1.0, 0.94, 0.7), pulse)

func project(p: Vector3) -> Vector2:
	if cam.is_position_behind(p):
		return Vector2(-1000, -1000)
	return cam.unproject_position(p)

## Направление джойстика на экране → направление в мире с учётом поворота камеры
func screen_to_world(m: Vector2) -> Vector2:
	var a := deg_to_rad(cam_yaw)
	var c := cos(a)
	var s := sin(a)
	return Vector2(c * m.x + s * m.y, -s * m.x + c * m.y)
