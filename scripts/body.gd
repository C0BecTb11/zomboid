class_name Body
extends RefCounted

const HEAD := 0
const NECK := 1
const TORSO := 2
const GROIN := 3
const L_UPPER := 4
const L_FORE := 5
const L_HAND := 6
const R_UPPER := 7
const R_FORE := 8
const R_HAND := 9
const L_THIGH := 10
const L_SHIN := 11
const L_FOOT := 12
const R_THIGH := 13
const R_SHIN := 14
const R_FOOT := 15
const COUNT := 16

const NAMES := ["Голова", "Шея", "Торс", "Пах",
	"Левое плечо", "Левое предплечье", "Левая кисть",
	"Правое плечо", "Правое предплечье", "Правая кисть",
	"Левое бедро", "Левая голень", "Левая стопа",
	"Правое бедро", "Правая голень", "Правая стопа"]
const ARMS := [4, 5, 6, 7, 8, 9]
const LEGS := [10, 11, 12, 13, 14, 15]
const ZOMBIE_WEIGHTS := [3, 2, 10, 2, 8, 12, 10, 8, 12, 10, 4, 4, 1, 4, 4, 1]

const SCRATCH := 0
const LACERATION := 1
const BITE := 2
const GLASS := 3
const WOUND_NAMES := ["Царапина", "Рваная рана", "Укус", "Порез со стеклом"]
const HEAL_TIME := [240.0, 600.0, 800.0, 500.0]
const BLEED_TIME := [45.0, 160.0, 240.0, 200.0]
const BLEED_RATE := [0.12, 0.3, 0.45, 0.3]
const ZOMBIE_INFECT := [0.05, 0.15, 0.6, 0.0]

class Wound:
	var type := 0
	var heal := 0.0
	var bleed := 0.0
	var glass := false
	var infection := 0.0
	var disinfected := false
	func _init(tp: int = 0) -> void:
		type = tp
		heal = Body.HEAL_TIME[tp]
		bleed = Body.BLEED_TIME[tp]
		glass = tp == Body.GLASS
	func infected() -> bool: return infection >= 1.0
	func bleeding() -> bool: return bleed > 0.0

class Part:
	var wounds: Array = []
	var bandage := 0          # 0 — нет, 1 — бинт, 2 — тряпка
	var bandage_age := 0.0
	var bruise := 0.0
	func dirty() -> bool: return bandage != 0 and bandage_age > (300.0 if bandage == 1 else 160.0)
	func bleeding() -> bool:
		if bandage != 0:
			return false
		for w in wounds:
			if w.bleeding():
				return true
		return false
	func has_glass() -> bool:
		for w in wounds:
			if w.glass:
				return true
		return false
	func infected() -> bool:
		for w in wounds:
			if w.infected():
				return true
		return false
	func ok() -> bool: return wounds.is_empty() and bruise <= 0.0

var parts: Array = []

func _init() -> void:
	for i in COUNT:
		parts.append(Part.new())

static func random_zombie_target(rnd: RandomNumberGenerator) -> int:
	var total := 0
	for v in ZOMBIE_WEIGHTS:
		total += v
	var r := rnd.randi() % total
	for i in ZOMBIE_WEIGHTS.size():
		r -= ZOMBIE_WEIGHTS[i]
		if r < 0:
			return i
	return TORSO

func bleeding() -> bool:
	for p: Part in parts:
		if p.bleeding():
			return true
	return false

func wound_infected() -> bool:
	for p: Part in parts:
		if p.infected():
			return true
	return false

func any_glass() -> bool:
	for p: Part in parts:
		if p.has_glass():
			return true
	return false

func any_hurt() -> bool:
	for p: Part in parts:
		if not p.ok():
			return true
	return false

func arm_penalty() -> float:
	var n := 0.0
	for i in ARMS:
		var p: Part = parts[i]
		n += p.wounds.size() * 0.08 + (0.1 if p.has_glass() else 0.0)
	return clampf(1.0 - n, 0.5, 1.0)

func leg_penalty() -> float:
	var n := 0.0
	for i in LEGS:
		var p: Part = parts[i]
		n += p.wounds.size() * 0.07 + (0.12 if p.has_glass() else 0.0) + (0.03 if p.bruise > 0.0 else 0.0)
	return clampf(1.0 - n, 0.55, 1.0)

## Обновление ран, возвращает потерю здоровья
func update(dt: float) -> float:
	var loss := 0.0
	for p: Part in parts:
		if p.bruise > 0.0:
			p.bruise -= dt
		var bleeding_here := 0
		var i := p.wounds.size() - 1
		while i >= 0:
			var w: Wound = p.wounds[i]
			if w.bleed > 0.0:
				w.bleed -= dt * (3.0 if p.bandage != 0 else 1.0)
				if p.bandage == 0:
					loss += BLEED_RATE[w.type] * dt
				else:
					bleeding_here += 1
			if not w.disinfected and not w.infected() and (p.bandage == 0 or p.dirty()):
				w.infection += dt * (0.004 if p.dirty() else 0.0025)
			if w.infected():
				loss += 0.04 * dt
			var rate := 1.0 if (p.bandage != 0 and not p.dirty()) else 0.45
			if w.infected():
				rate *= 0.15
			if w.glass:
				rate = 0.0
			w.heal -= dt * rate
			if w.heal <= 0.0 and w.bleed <= 0.0:
				p.wounds.remove_at(i)
			i -= 1
		if p.bandage != 0:
			p.bandage_age += dt * (1.0 + bleeding_here * 2.0)
			if p.wounds.is_empty() and p.bandage_age > 400.0:
				p.bandage = 0
				p.bandage_age = 0.0
	return loss

func to_data() -> Array:
	var a := []
	for p: Part in parts:
		var ws := []
		for w in p.wounds:
			ws.append([w.type, snappedf(w.heal, 0.1), snappedf(w.bleed, 0.1), w.glass, snappedf(w.infection, 0.01), w.disinfected])
		a.append([p.bandage, snappedf(p.bandage_age, 0.1), snappedf(p.bruise, 0.1), ws])
	return a

func from_data(a) -> void:
	for p: Part in parts:
		p.wounds.clear()
		p.bandage = 0
		p.bandage_age = 0.0
		p.bruise = 0.0
	if a == null or not (a is Array):
		return
	for i in mini(a.size(), parts.size()):
		var e: Array = a[i]
		var p: Part = parts[i]
		p.bandage = int(e[0])
		p.bandage_age = float(e[1])
		p.bruise = float(e[2])
		for we in e[3]:
			var w := Wound.new(int(we[0]))
			w.heal = float(we[1])
			w.bleed = float(we[2])
			w.glass = bool(we[3])
			w.infection = float(we[4])
			w.disinfected = bool(we[5])
			p.wounds.append(w)
