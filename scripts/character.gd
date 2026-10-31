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

var skel: Skeleton3D
var mi: MeshInstance3D
var eyes: MeshInstance3D
var weapon_mi: MeshInstance3D
var cur_weapon := ""
var zombie := false

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
		var em := BoxMesh.new()
		em.size = Vector3(0.11, 0.022, 0.012)
		eyes.mesh = em
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
	if eyes != null:
		eyes.visible = angry and not lying
	var sw := sin(phase) * 28.0 * moving
	var lean := 3.0 + moving * 4.0
	if cl:
		lean = arc * (85.0 if zombie else 62.0)
	elif lying:
		lean = 0.0
	elif zombie:
		lean = 13.0
	elif sneak:
		lean = 22.0
	var crouch := 0.12 if (sneak and not lying and not cl) else 0.0
	# корень: лёжа / подъём при перелезании / присед
	if lying:
		skel.set_bone_pose_position(B.ROOT, Vector3(0, 0.13, 0.75))
		_rot(B.ROOT, -90)
	else:
		skel.set_bone_pose_position(B.ROOT, Vector3(0, (arc * (0.62 if zombie else 0.55) if cl else 0.0) - crouch, 0))
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
		if lying:
			thigh = s * 4.0
			knee = 4.0
		_rot(bi, thigh, s * 2.0)
		_rot(bi + 1, knee)
		_rot(bi + 2, -knee * 0.4)
	# туловище и голова
	_rot(B.SPINE, lean, (sin(phase * 0.5) * 5.0 + 3.0) if (zombie and not lying) else 0.0)
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
		_rot(bi2, sh, side)
		_rot(bi2 + 1, el)
