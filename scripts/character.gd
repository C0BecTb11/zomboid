class_name CharModel
extends Node3D
## Человек/зомби: один меш со скелетом, части тела привязаны к костям.

enum B { ROOT, HIPS, THIGH_L, SHIN_L, FOOT_L, THIGH_R, SHIN_R, FOOT_R, SPINE, HEAD, UARM_L, FARM_L, HAND_L, UARM_R, FARM_R, HAND_R }

const BONES := [
	["root", -1, Vector3.ZERO],
	["hips", 0, Vector3(0, 0.93, 0)],
	["thigh_l", 1, Vector3(0.095, 0, 0)],
	["shin_l", 2, Vector3(0, -0.45, 0)],
	["foot_l", 3, Vector3(0, -0.43, 0)],
	["thigh_r", 1, Vector3(-0.095, 0, 0)],
	["shin_r", 5, Vector3(0, -0.45, 0)],
	["foot_r", 6, Vector3(0, -0.43, 0)],
	["spine", 1, Vector3(0, -0.03, 0)],
	["head", 8, Vector3(0, 0.71, 0.01)],
	["uarm_l", 8, Vector3(0.205, 0.43, 0)],
	["farm_l", 10, Vector3(0, -0.29, 0)],
	["hand_l", 11, Vector3(0, -0.26, 0)],
	["uarm_r", 8, Vector3(-0.205, 0.43, 0)],
	["farm_r", 13, Vector3(0, -0.29, 0)],
	["hand_r", 14, Vector3(0, -0.26, 0)],
]

static var _mesh_cache := {}
static var _weapon_cache := {}
static var _mat: StandardMaterial3D
static var _mat_corpse: StandardMaterial3D
static var _mat_eyes: StandardMaterial3D
static var _eye_mesh: BoxMesh

var skel: Skeleton3D
var mi: MeshInstance3D
var eyes: MeshInstance3D
var weapon_mi: MeshInstance3D
var cur_weapon := ""
var zombie := false
var fall := -1.0      # 0..1 — падение на спину (0 стоит, 1 лежит); -1 — брать из lying
var atk_kind := 0     # 1 — добивание ногой

static func _materials() -> void:
	if _mat != null:
		return
	_mat = StandardMaterial3D.new()
	_mat.vertex_color_use_as_albedo = true
	_mat.vertex_color_is_srgb = true
	_mat.roughness = 0.85
	_mat_corpse = _mat.duplicate()
	_mat_corpse.albedo_color = Color(0.55, 0.52, 0.5)
	_mat_eyes = StandardMaterial3D.new()
	_mat_eyes.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	_mat_eyes.albedo_color = Color(1.0, 0.15, 0.08)

static func _global(bi: int) -> Vector3:
	var o := Vector3.ZERO
	var i := bi
	while i >= 0:
		o += BONES[i][2]
		i = BONES[i][1]
	return o

## Модель зомби становится трупом (тот же меш — без скачка позы)
func make_corpse() -> void:
	if mi != null:
		mi.material_override = _mat_corpse
	if eyes != null:
		eyes.visible = false

func setup(cols: Array, is_zombie: bool, stain: bool, long_hair: bool, corpse: bool = false) -> void:
	_materials()
	zombie = is_zombie
	skel = Skeleton3D.new()
	add_child(skel)
	for b in BONES:
		var i := skel.add_bone(b[0])
		if b[1] >= 0:
			skel.set_bone_parent(i, b[1])
		skel.set_bone_rest(i, Transform3D(Basis.IDENTITY, b[2]))
	skel.reset_bone_poses()
	var key := "%s|%s|%s|%s|%s|%s|%s" % [cols[0].to_html(), cols[1].to_html(), cols[2].to_html(), cols[3].to_html(), is_zombie, stain, long_hair]
	if not _mesh_cache.has(key):
		_mesh_cache[key] = _build_mesh(cols, is_zombie, stain, long_hair)
	mi = MeshInstance3D.new()
	mi.mesh = _mesh_cache[key]
	mi.material_override = _mat_corpse if corpse else _mat
	skel.add_child(mi)
	mi.skeleton = NodePath("..")
	if is_zombie and not corpse:
		var ba := BoneAttachment3D.new()
		ba.bone_name = "head"
		skel.add_child(ba)
		eyes = MeshInstance3D.new()
		if _eye_mesh == null:
			_eye_mesh = BoxMesh.new()
			_eye_mesh.size = Vector3(0.11, 0.022, 0.012)
		eyes.mesh = _eye_mesh
		eyes.material_override = _mat_eyes
		eyes.position = Vector3(0, 0.02, 0.104)
		eyes.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		eyes.visible = false
		ba.add_child(eyes)
	if not is_zombie:
		var hb := BoneAttachment3D.new()
		hb.bone_name = "hand_r"
		skel.add_child(hb)
		weapon_mi = MeshInstance3D.new()
		weapon_mi.material_override = _mat
		hb.add_child(weapon_mi)

static func _build_mesh(cols: Array, is_zombie: bool, stain: bool, long_hair: bool) -> ArrayMesh:
	var m := Mesher.new()
	m.skinned = true
	var shirt: Color = cols[0]
	var pant: Color = cols[1]
	var skin: Color = cols[2]
	var hair: Color = cols[3]
	var shoe := Color8(0x24, 0x20, 0x1C)
	var skin_dk := skin.darkened(0.18)
	var at := func(bi: int) -> void:
		m.bone = bi
		m.xf = Transform3D(Basis.IDENTITY, _global(bi))
	# ноги
	for s in [B.THIGH_L, B.THIGH_R]:
		at.call(s)
		m.tube(-0.45, 0, 0.056, 0.078, pant)
		m.ellipsoid(Vector3.ZERO, Vector3(0.08, 0.06, 0.08), pant, 4, 8)
		at.call(s + 1)
		m.ellipsoid(Vector3.ZERO, Vector3(0.058, 0.058, 0.058), pant, 4, 8)
		m.tube(-0.43, 0, 0.045, 0.058, pant)
		at.call(s + 2)
		m.ellipsoid(Vector3(0, -0.035, 0.055), Vector3(0.058, 0.045, 0.12), shoe, 4, 8)
	# туловище
	at.call(B.SPINE)
	m.ellipsoid(Vector3(0, 0.05, 0), Vector3(0.165, 0.11, 0.11), pant, 5, 10)
	m.tube(0.06, 0.1, 0.155, 0.155, pant.darkened(0.45), 10, 1.1, 0.74, false)
	m.tube(0.1, 0.47, 0.145, 0.188, shirt, 10, 1.12, 0.7, false)
	m.ellipsoid(Vector3(0, 0.47, 0), Vector3(0.21, 0.06, 0.13), shirt, 4, 10)
	if stain:
		m.ellipsoid(Vector3(0.05, 0.28, 0.11), Vector3(0.08, 0.1, 0.03), Color8(0x5A, 0x0E, 0x0E), 3, 6)
		m.ellipsoid(Vector3(-0.09, 0.16, 0.1), Vector3(0.05, 0.05, 0.03), Color8(0x4A, 0x0A, 0x0A), 3, 6)
	if is_zombie:
		m.ellipsoid(Vector3(0.1, 0.35, -0.1), Vector3(0.06, 0.07, 0.03), skin_dk, 3, 6)
	m.tube(0.5, 0.6, 0.05, 0.045, skin, 8)
	# голова
	at.call(B.HEAD)
	m.ellipsoid(Vector3.ZERO, Vector3(0.1, 0.122, 0.112), skin, 6, 12)
	m.ellipsoid(Vector3(0, -0.075, 0.035), Vector3(0.075, 0.05, 0.075), skin, 3, 8)
	m.ellipsoid(Vector3(0, 0.02, -0.005), Vector3(0.108, 0.13, 0.12), hair, 4, 12, 0.0, 0.48)
	m.ellipsoid(Vector3(0, -0.01, -0.05), Vector3(0.106, 0.115, 0.08), hair, 4, 10, 0.2, 0.75)
	if long_hair:
		m.ellipsoid(Vector3(0, -0.12, -0.07), Vector3(0.1, 0.16, 0.06), hair, 4, 8)
	m.ellipsoid(Vector3(0.1, -0.005, 0), Vector3(0.018, 0.032, 0.022), skin_dk, 2, 6)
	m.ellipsoid(Vector3(-0.1, -0.005, 0), Vector3(0.018, 0.032, 0.022), skin_dk, 2, 6)
	m.box(Vector3(0, -0.015, 0.115), Vector3(0.026, 0.045, 0.03), skin_dk)
	var eye := Color8(0xD8, 0xD0, 0xA0) if is_zombie else Color8(0x1A, 0x1A, 0x1A)
	m.box(Vector3(-0.038, 0.02, 0.103), Vector3(0.026, 0.018, 0.012), eye)
	m.box(Vector3(0.038, 0.02, 0.103), Vector3(0.026, 0.018, 0.012), eye)
	m.box(Vector3(-0.038, 0.045, 0.1), Vector3(0.035, 0.01, 0.012), hair.darkened(0.2))
	m.box(Vector3(0.038, 0.045, 0.1), Vector3(0.035, 0.01, 0.012), hair.darkened(0.2))
	if is_zombie:
		m.box(Vector3(0, -0.06, 0.1), Vector3(0.05, 0.022, 0.012), Color8(0x3A, 0x0C, 0x0C))
	else:
		m.box(Vector3(0, -0.06, 0.1), Vector3(0.04, 0.008, 0.012), Color8(0x8A, 0x4A, 0x40))
	# руки
	for s in [B.UARM_L, B.UARM_R]:
		at.call(s)
		m.ellipsoid(Vector3.ZERO, Vector3(0.065, 0.065, 0.065), shirt, 4, 8)
		m.tube(-0.29, 0, 0.042, 0.056, shirt)
		at.call(s + 1)
		var arm_skin := skin_dk if is_zombie else skin
		m.ellipsoid(Vector3.ZERO, Vector3(0.045, 0.045, 0.045), arm_skin, 3, 8)
		m.tube(-0.26, 0, 0.034, 0.044, arm_skin)
		at.call(s + 2)
		m.ellipsoid(Vector3(0, -0.045, 0.005), Vector3(0.038, 0.055, 0.03), skin, 4, 8)
	return m.commit()

static func _weapon_mesh(id: String) -> ArrayMesh:
	if _weapon_cache.has(id):
		return _weapon_cache[id]
	var m := Mesher.new()
	var d := Items.def(id)
	var col: Color = d["color"]
	m.translate(Vector3(0, -0.05, 0))
	if d["kind"] == Items.Kind.GUN:
		m.box(Vector3(0, -0.02, 0.04), Vector3(0.045, 0.1, 0.06), Color8(0x26, 0x26, 0x2A))
		m.box(Vector3(0, 0.03, 0.1), Vector3(0.05, 0.06, 0.2), Color8(0x2E, 0x2E, 0x34))
	else:
		var length: float = 0.25 + d["range"] * 0.45
		match id:
			"bat":
				m.translate(Vector3(0, 0, -0.04))
				m.rot_x(90)
				m.tube(0, length, 0.022, 0.045, col, 8)
				m.tube(-0.05, 0, 0.025, 0.022, Color8(0x2A, 0x2A, 0x2A), 6)
			"knife":
				m.box(Vector3(0, 0, 0.04), Vector3(0.03, 0.03, 0.1), Color8(0x1E, 0x1E, 0x1E))
				m.box(Vector3(0, 0, 0.17), Vector3(0.01, 0.04, 0.16), col)
			"hammer":
				m.box(Vector3(0, 0, length / 2 - 0.05), Vector3(0.035, 0.035, length), Color8(0x9A, 0x6A, 0x3A))
				m.box(Vector3(0, 0, length - 0.06), Vector3(0.06, 0.16, 0.06), Color8(0x7A, 0x7E, 0x86))
			"crowbar":
				m.box(Vector3(0, 0, length / 2 - 0.05), Vector3(0.03, 0.03, length), col)
				m.box(Vector3(0, 0.05, length - 0.07), Vector3(0.03, 0.12, 0.03), col)
			_:
				m.box(Vector3(0, 0, length / 2 - 0.05), Vector3(0.04, 0.04, length), col)
	var mesh := m.commit()
	_weapon_cache[id] = mesh
	return mesh

func _rot(bi: int, x: float, z: float = 0.0, order_zx: bool = false) -> void:
	var b: Basis
	if order_zx:
		b = Basis(Vector3.BACK, deg_to_rad(z)) * Basis(Vector3.RIGHT, deg_to_rad(x))
	else:
		b = Basis(Vector3.RIGHT, deg_to_rad(x)) * Basis(Vector3.BACK, deg_to_rad(z))
	skel.set_bone_pose_rotation(bi, b.get_rotation_quaternion())

## Поза на кадр
func pose(phase: float, moving: float, attack: float, weapon_id: String, lying: bool, sneak: bool, angry: bool, climb: float) -> void:
	var cl := climb >= 0.0
	var cp := clampf(climb, 0.0, 1.0) if cl else 0.0
	var arc := sin(PI * (minf(cp / 0.8, 1.0) if zombie else cp)) if cl else 0.0
	var gun: bool = Items.def(weapon_id)["kind"] == Items.Kind.GUN and weapon_id != "fists"
	var holding := weapon_id != "" and weapon_id != "fists"
	if weapon_mi != null and cur_weapon != weapon_id:
		cur_weapon = weapon_id
		weapon_mi.mesh = _weapon_mesh(weapon_id) if holding else null
	var ly := fall if fall >= 0.0 else (1.0 if lying else 0.0)
	if ly > 0.0:
		cl = false
		arc = 0.0
	lying = ly >= 0.999
	if eyes != null:
		eyes.visible = angry and ly < 0.5
	# добивание ногой: u 0→1 за время удара
	var stomp := atk_kind == 1 and attack > 0.001 and not zombie and ly <= 0.0 and not cl
	var u := 1.0 - attack
	var lift := 0.0      # колено вверх (замах)
	var drive := 0.0     # нога вниз в тело
	if stomp:
		lift = smoothstep(0.0, 0.42, u) * (1.0 - smoothstep(0.46, 0.56, u))
		drive = smoothstep(0.46, 0.56, u) * (1.0 - smoothstep(0.78, 1.0, u))
		moving *= 0.2
	var sw := sin(phase) * 28.0 * moving
	var lean := 3.0 + moving * 4.0
	if cl:
		lean = arc * (85.0 if zombie else 62.0)
	elif zombie:
		lean = 13.0
	elif sneak:
		lean = 22.0
	if stomp:
		lean = lean - lift * 8.0 + drive * 16.0
	lean = lerpf(lean, 0.0, ly)
	var crouch := 0.12 if (sneak and not cl) else 0.0
	crouch = crouch * (1.0 - ly) + drive * 0.06
	# корень: падение и лёжа / подъём при перелезании / присед
	if ly > 0.0:
		var e := ly * ly                     # падает с ускорением
		var sl := smoothstep(0.0, 1.0, ly)
		skel.set_bone_pose_position(B.ROOT, Vector3(0, 0.13 * sl + 0.12 * sin(PI * ly), 0.75 * sl))
		_rot(B.ROOT, -90.0 * e)
	else:
		# при добивании корпус подаётся вперёд, нога достаёт до тела
		skel.set_bone_pose_position(B.ROOT, Vector3(0, (arc * (0.62 if zombie else 0.55) if cl else 0.0) - crouch, 0.2 * drive + 0.05 * lift))
		_rot(B.ROOT, 0)
	# ноги
	var legs := [[B.THIGH_L, 1.0], [B.THIGH_R, -1.0]]
	for lg in legs:
		var bi: int = lg[0]
		var s: float = lg[1]
		var thigh := -sw * s
		var knee := moving * (6.0 + 34.0 * maxf(0.0, cos(phase) * s))
		if sneak and not cl:
			thigh -= 28.0
			knee += 40.0
		if zombie and not lying and not cl and s > 0.0:
			knee += 10.0
		if cl:
			var lead := s > 0.0
			var k := sin(PI * minf(cp * 1.6, 1.0)) if lead else sin(PI * clampf(cp * 1.6 - 0.6, 0.0, 1.0))
			thigh = -75.0 * k - arc * 20.0
			knee = -thigh * 0.9
		if stomp:
			if s < 0.0:
				# правая: колено к груди, затем удар стопой вниз-вперёд
				thigh = lerpf(-5.0, -82.0, lift)
				knee = lerpf(knee, 95.0, lift)
				thigh = lerpf(thigh, -38.0, drive)
				knee = lerpf(knee, 6.0, drive)
			else:
				thigh = 8.0 * lift + 6.0 * drive
				knee = 14.0 * lift + 22.0 * drive
		if ly > 0.0:
			thigh = lerpf(thigh, s * 4.0, ly) - 30.0 * sin(PI * ly)
			knee = lerpf(knee, 4.0, ly) + 45.0 * sin(PI * ly)
		_rot(bi, thigh, s * 2.0)
		_rot(bi + 1, knee)
		_rot(bi + 2, -knee * 0.4)
	# туловище и голова
	_rot(B.SPINE, lean, ((sin(phase * 0.5) * 5.0 + 3.0) * (1.0 - ly)) if zombie else 0.0)
	if zombie:
		_rot(B.HEAD, 10.0, sin(phase * 0.7) * 8.0 + 14.0, true)
	else:
		_rot(B.HEAD, -lean * 0.4)
	# руки: s = +1 левая, -1 правая
	var arms := [[B.UARM_L, 1.0], [B.UARM_R, -1.0]]
	for ar in arms:
		var bi2: int = ar[0]
		var s2: float = ar[1]
		var sh := 0.0
		var el := 0.0
		var side := s2 * 6.0
		if lying:
			sh = -170.0 + s2 * 8.0
			el = -5.0
		elif stomp:
			# руки в стороны для равновесия
			var bal := maxf(lift, drive)
			sh = -18.0 - 22.0 * bal
			el = -25.0 - 20.0 * bal
			side = s2 * (8.0 + 30.0 * bal)
		elif cl:
			sh = -70.0 - arc * 30.0 + s2 * 6.0
			el = -15.0
		elif zombie:
			sh = -80.0 + sin(phase + s2) * 9.0 + (-8.0 if s2 > 0 else 0.0)
			el = -12.0
			side = s2 * 3.0
		elif gun:
			sh = (-84.0 - attack * 22.0) if s2 < 0 else -78.0
			el = -4.0 if s2 < 0 else -12.0
			side = -26.0 if s2 > 0 else -4.0
		elif s2 < 0 and attack > 0.01:
			sh = -35.0 - 115.0 * attack
			el = -20.0 - 30.0 * attack
		elif s2 < 0 and holding:
			sh = -20.0 + sw * 0.25
			el = -55.0
		else:
			sh = sw * s2 * 0.9 + (-20.0 if sneak else 0.0)
			el = -12.0 - moving * 10.0
		if ly > 0.0 and not lying:
			# взмах руками при падении
			sh = lerpf(sh, -170.0 + s2 * 8.0, ly) - 40.0 * sin(PI * ly)
			el = lerpf(el, -5.0, ly)
			side = lerpf(side, s2 * 30.0, sin(PI * ly))
		_rot(bi2, sh, side)
		_rot(bi2 + 1, el)

# ================== ОДЕЖДА И СНАРЯЖЕНИЕ ==================
static var _wear_cache := {}
var wear_nodes := {}          # слот → MeshInstance3D
var wear_ids := {}            # слот → id (что сейчас показано)

## Показать надетое: меши «натянуты» на тот же скелет и двигаются вместе с телом
func set_wear(wear: Dictionary) -> void:
	if skel == null:
		return
	for sl in wear_nodes.keys():
		if not wear.has(sl) or wear[sl] != wear_ids.get(sl, ""):
			wear_nodes[sl].queue_free()
			wear_nodes.erase(sl)
			wear_ids.erase(sl)
	for sl in wear:
		if wear_nodes.has(sl):
			continue
		var id: String = wear[sl]
		if not _wear_cache.has(id):
			_wear_cache[id] = _build_wear(id)
		var wm := MeshInstance3D.new()
		wm.mesh = _wear_cache[id]
		wm.material_override = mi.material_override if mi != null else _mat
		skel.add_child(wm)
		wm.skeleton = NodePath("..")
		wear_nodes[sl] = wm
		wear_ids[sl] = id

static func _build_wear(id: String) -> ArrayMesh:
	var m := Mesher.new()
	m.skinned = true
	var col: Color = Items.def(id)["color"]
	var dk := col.darkened(0.35)
	var lt := col.lightened(0.15)
	var metal := Color8(0xB8, 0xB8, 0xB0)
	var webbing := Color8(0x26, 0x26, 0x24)
	var at := func(bi: int) -> void:
		m.bone = bi
		m.xf = Transform3D(Basis.IDENTITY, _global(bi))
	match id:
		# ---------- голова ----------
		"cap":
			at.call(B.HEAD)
			m.ellipsoid(Vector3(0, 0.03, -0.004), Vector3(0.114, 0.128, 0.124), col, 4, 12, 0.0, 0.42)
			m.ellipsoid(Vector3(0, 0.155, 0), Vector3(0.016, 0.01, 0.016), dk, 2, 6)
			m.push()
			m.translate(Vector3(0, 0.055, 0.135))
			m.rot_x(-8)
			m.box(Vector3.ZERO, Vector3(0.17, 0.014, 0.11), dk, true)
			m.pop()
		"beanie":
			at.call(B.HEAD)
			m.ellipsoid(Vector3(0, 0.035, -0.006), Vector3(0.118, 0.138, 0.128), col, 5, 12, 0.0, 0.46)
			m.tube(0.015, 0.05, 0.12, 0.118, dk, 12, 1.0, 1.08, false)
			m.ellipsoid(Vector3(0, 0.17, -0.01), Vector3(0.035, 0.03, 0.035), lt, 3, 8)
		"helmet":
			at.call(B.HEAD)
			m.ellipsoid(Vector3(0, 0.0, -0.005), Vector3(0.152, 0.165, 0.158), col, 6, 14, 0.0, 0.64)
			m.push()
			m.translate(Vector3(0, 0.0, 0.118))
			m.box(Vector3.ZERO, Vector3(0.2, 0.085, 0.05), Color8(0x1A, 0x22, 0x2C), true)
			m.pop()
			m.box(Vector3(0, 0.1, 0.05), Vector3(0.04, 0.05, 0.2), Color8(0xC0, 0x30, 0x30))
		# ---------- тело ----------
		"hoodie", "leather", "mil_jacket":
			at.call(B.SPINE)
			m.tube(0.04, 0.49, 0.165, 0.2, col, 12, 1.12, 0.76, false)
			m.ellipsoid(Vector3(0, 0.48, 0), Vector3(0.225, 0.07, 0.15), col, 4, 12)
			m.tube(0.02, 0.07, 0.17, 0.168, dk, 12, 1.12, 0.76, false)
			m.box(Vector3(0, 0.27, 0.148), Vector3(0.012, 0.4, 0.012), metal if id == "leather" else dk)
			if id == "hoodie":
				m.ellipsoid(Vector3(0, 0.53, -0.1), Vector3(0.13, 0.08, 0.075), col, 4, 10)
				m.box(Vector3(0, 0.15, 0.142), Vector3(0.2, 0.1, 0.02), dk)
				m.box(Vector3(-0.03, 0.42, 0.15), Vector3(0.008, 0.1, 0.008), lt)
				m.box(Vector3(0.03, 0.42, 0.15), Vector3(0.008, 0.1, 0.008), lt)
			elif id == "leather":
				m.tube(0.47, 0.56, 0.085, 0.075, col, 10)
				for sx in [-1.0, 1.0]:
					m.push()
					m.translate(Vector3(sx * 0.06, 0.45, 0.13))
					m.rot_z(sx * 25.0)
					m.box(Vector3.ZERO, Vector3(0.07, 0.1, 0.02), dk)
					m.pop()
					m.box(Vector3(sx * 0.1, 0.13, 0.142), Vector3(0.07, 0.012, 0.02), dk)
			else:
				m.tube(0.47, 0.55, 0.08, 0.075, col, 10)
				for sx in [-1.0, 1.0]:
					m.box(Vector3(sx * 0.09, 0.36, 0.145), Vector3(0.09, 0.09, 0.025), dk)
					m.box(Vector3(sx * 0.09, 0.405, 0.15), Vector3(0.095, 0.02, 0.03), dk.darkened(0.2))
					m.box(Vector3(sx * 0.1, 0.14, 0.14), Vector3(0.1, 0.1, 0.025), dk)
				m.box(Vector3(-0.2, 0.42, 0.0), Vector3(0.012, 0.06, 0.06), Color8(0x30, 0x34, 0x24))
			for s in [B.UARM_L, B.UARM_R]:
				at.call(s)
				m.ellipsoid(Vector3.ZERO, Vector3(0.074, 0.072, 0.074), col, 4, 8)
				m.tube(-0.29, 0, 0.05, 0.064, col)
				at.call(s + 1)
				m.ellipsoid(Vector3.ZERO, Vector3(0.054, 0.052, 0.054), col, 3, 8)
				m.tube(-0.2, 0, 0.046, 0.053, col)
				m.tube(-0.235, -0.195, 0.044, 0.047, dk)
		# ---------- ноги ----------
		"jeans", "cargo":
			at.call(B.SPINE)
			m.ellipsoid(Vector3(0, 0.05, 0), Vector3(0.172, 0.117, 0.117), col, 5, 10, 0.25, 1.0)
			m.tube(0.05, 0.11, 0.163, 0.163, Color8(0x2A, 0x22, 0x1C), 10, 1.1, 0.74, false)
			m.box(Vector3(0, 0.08, 0.125), Vector3(0.05, 0.035, 0.012), metal)
			for s in [B.THIGH_L, B.THIGH_R]:
				var out := 1.0 if s == B.THIGH_L else -1.0
				at.call(s)
				m.tube(-0.45, 0, 0.062, 0.084, col)
				m.ellipsoid(Vector3.ZERO, Vector3(0.086, 0.066, 0.086), col, 4, 8)
				if id == "cargo":
					m.box(Vector3(out * 0.072, -0.24, 0.0), Vector3(0.03, 0.12, 0.1), dk)
					m.box(Vector3(out * 0.08, -0.18, 0.0), Vector3(0.03, 0.02, 0.105), dk.darkened(0.2))
				else:
					m.box(Vector3(out * 0.04, -0.05, -0.07), Vector3(0.06, 0.07, 0.012), dk)
				at.call(s + 1)
				m.ellipsoid(Vector3.ZERO, Vector3(0.064, 0.062, 0.064), col, 4, 8)
				m.tube(-0.38, 0, 0.054, 0.064, col)
				m.tube(-0.4, -0.37, 0.058, 0.056, dk)
		"boots":
			for s in [B.THIGH_L, B.THIGH_R]:
				at.call(s + 1)
				m.tube(-0.43, -0.28, 0.062, 0.066, col)
				m.tube(-0.3, -0.27, 0.068, 0.068, dk)
				at.call(s + 2)
				m.ellipsoid(Vector3(0, -0.03, 0.055), Vector3(0.066, 0.055, 0.13), col, 4, 8)
				m.box(Vector3(0, -0.075, 0.055), Vector3(0.12, 0.025, 0.25), Color8(0x1A, 0x16, 0x12), true)
				m.box(Vector3(0, 0.0, 0.11), Vector3(0.05, 0.01, 0.06), lt)
		"gloves":
			for s in [B.UARM_L, B.UARM_R]:
				at.call(s + 1)
				m.tube(-0.26, -0.19, 0.05, 0.046, dk)
				at.call(s + 2)
				m.ellipsoid(Vector3(0, -0.045, 0.006), Vector3(0.044, 0.061, 0.036), col, 4, 8)
		# ---------- рюкзаки: каркас, клапан, карманы, лямки через плечи ----------
		"bp_school":
			at.call(B.SPINE)
			_pack_straps(m, dk, metal, 0.05, false)
			m.push()
			m.translate(Vector3(0, 0, -0.2))
			m.tube(0.17, 0.43, 0.145, 0.14, col, 12, 1.0, 0.5)
			m.ellipsoid(Vector3(0, 0.43, 0), Vector3(0.14, 0.05, 0.07), col, 4, 12, 0.0, 0.5)
			m.ellipsoid(Vector3(0, 0.17, 0), Vector3(0.145, 0.03, 0.072), dk, 3, 12, 0.5, 1.0)
			# передний карман и молния
			m.tube(0.19, 0.31, 0.105, 0.1, Color8(0x55, 0x5A, 0x60), 10, 1.0, 0.32)
			m.translate(Vector3(0, 0, -0.035))
			m.box(Vector3(0, 0.31, 0.0), Vector3(0.17, 0.008, 0.012), metal)
			m.box(Vector3(0.06, 0.3, -0.005), Vector3(0.015, 0.03, 0.01), metal)
			# брелок
			m.box(Vector3(-0.07, 0.26, -0.012), Vector3(0.03, 0.04, 0.012), Color8(0xF0, 0xC8, 0x30))
			m.pop()
			m.box(Vector3(0, 0.46, -0.2), Vector3(0.05, 0.03, 0.01), dk)
		"bp_military":
			at.call(B.SPINE)
			_pack_straps(m, webbing, Color8(0x30, 0x30, 0x2C), 0.065, true)
			m.box(Vector3(0, 0.3, -0.22), Vector3(0.32, 0.36, 0.17), col, true)
			# клапан сверху
			m.box(Vector3(0, 0.47, -0.225), Vector3(0.33, 0.04, 0.185), dk)
			m.box(Vector3(0, 0.4, -0.315), Vector3(0.24, 0.12, 0.012), dk)
			for sx in [-0.07, 0.07]:
				m.box(Vector3(sx, 0.34, -0.322), Vector3(0.03, 0.05, 0.008), webbing)
			# стропы MOLLE
			for k in 4:
				m.box(Vector3(0, 0.12 + k * 0.05, -0.31), Vector3(0.3, 0.012, 0.01), webbing)
			# боковые подсумки
			for sx in [-1.0, 1.0]:
				m.box(Vector3(sx * 0.19, 0.24, -0.22), Vector3(0.07, 0.17, 0.13), dk, true)
				m.box(Vector3(sx * 0.19, 0.33, -0.22), Vector3(0.075, 0.025, 0.135), col)
		"bp_hiking":
			at.call(B.SPINE)
			_pack_straps(m, dk, Color8(0xE0, 0x9A, 0x20), 0.07, true)
			# поясной ремень
			m.tube(0.07, 0.12, 0.172, 0.172, dk, 12, 1.12, 0.76, false)
			m.box(Vector3(0, 0.095, 0.135), Vector3(0.05, 0.04, 0.02), Color8(0xE0, 0x9A, 0x20))
			m.push()
			m.translate(Vector3(0, 0, -0.23))
			m.tube(0.06, 0.6, 0.155, 0.15, col, 12, 1.0, 0.62)
			m.ellipsoid(Vector3(0, 0.06, 0), Vector3(0.155, 0.04, 0.096), dk, 3, 12, 0.5, 1.0)
			# верхний клапан, свисающий назад
			m.ellipsoid(Vector3(0, 0.61, -0.015), Vector3(0.16, 0.055, 0.11), dk, 4, 12)
			m.box(Vector3(0, 0.6, -0.12), Vector3(0.05, 0.02, 0.02), metal)
			# передний карман
			m.push()
			m.translate(Vector3(0, 0, -0.08))
			m.tube(0.12, 0.36, 0.11, 0.1, col.darkened(0.15), 10, 1.0, 0.3)
			m.pop()
			# стяжки
			for yy in [0.24, 0.44]:
				m.box(Vector3(0, yy, -0.003), Vector3(0.315, 0.018, 0.2), webbing)
				m.box(Vector3(0, yy, -0.1), Vector3(0.035, 0.03, 0.012), Color8(0xE0, 0x9A, 0x20))
			# боковые карманы с бутылкой
			m.tube(0.08, 0.25, 0.045, 0.045, dk, 8, 1.0, 1.0)
			m.push()
			m.translate(Vector3(0.155, 0, 0))
			m.tube(0.1, 0.26, 0.04, 0.042, dk, 8)
			m.tube(0.26, 0.33, 0.03, 0.02, Color8(0x9C, 0xD4, 0xF4), 8)
			m.pop()
			m.push()
			m.translate(Vector3(-0.155, 0, 0))
			m.tube(0.1, 0.26, 0.04, 0.042, dk, 8)
			m.pop()
			# скатка-коврик под клапаном
			m.push()
			m.translate(Vector3(0, 0.7, 0.0))
			m.rot_z(90)
			m.tube(-0.2, 0.2, 0.055, 0.055, Color8(0x3E, 0x8A, 0x4A), 10)
			m.box(Vector3(0, 0.12, 0), Vector3(0.12, 0.012, 0.12), webbing)
			m.box(Vector3(0, -0.12, 0), Vector3(0.12, 0.012, 0.12), webbing)
			m.pop()
			m.pop()
	return m.commit()

## Лямки: через плечи вниз по груди, грудная стяжка с пряжкой
static func _pack_straps(m: Mesher, col: Color, buckle: Color, wdt: float, chest: bool) -> void:
	for sx in [-1.0, 1.0]:
		var x: float = sx * 0.095
		m.box(Vector3(x, 0.5, -0.07), Vector3(wdt, 0.03, 0.18), col)
		m.push()
		m.translate(Vector3(x, 0.47, 0.06))
		m.rot_x(-35)
		m.box(Vector3.ZERO, Vector3(wdt, 0.08, 0.025), col)
		m.pop()
		m.push()
		m.translate(Vector3(x * 1.05, 0.31, 0.142))
		m.rot_x(5)
		m.box(Vector3.ZERO, Vector3(wdt, 0.28, 0.018), col)
		m.pop()
		m.box(Vector3(x * 1.05, 0.2, 0.15), Vector3(wdt * 0.8, 0.03, 0.012), buckle)
	if chest:
		m.box(Vector3(0, 0.36, 0.153), Vector3(0.21, 0.018, 0.01), col)
		m.box(Vector3(0, 0.36, 0.158), Vector3(0.045, 0.03, 0.01), buckle)
