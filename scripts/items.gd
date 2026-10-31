class_name Items
extends RefCounted

enum Kind { FOOD, DRINK, MED, MELEE, GUN, AMMO, MATERIAL }

static var DEFS: Dictionary = _build()

static func _d(name: String, kind: int, w: float, color: String, extra: Dictionary = {}) -> Dictionary:
	var d := {"name": name, "kind": kind, "w": w, "color": Color(color),
		"hunger": 0.0, "thirst": 0.0, "heal": 0.0, "dmg": 0.0, "range": 0.0, "cd": 0.0, "noise": 0.0}
	for k in extra:
		d[k] = extra[k]
	return d

static func _build() -> Dictionary:
	var K := Kind
	return {
		"fists": _d("Кулаки", K.MELEE, 0.0, "#e0b090", {"dmg": 8.0, "range": 0.9, "cd": 0.55, "noise": 2.0}),
		"canned": _d("Консервы", K.FOOD, 0.6, "#b0b8c0", {"hunger": 35.0}),
		"chips": _d("Чипсы", K.FOOD, 0.2, "#e8c040", {"hunger": 15.0, "thirst": -5.0}),
		"bread": _d("Хлеб", K.FOOD, 0.3, "#c89048", {"hunger": 25.0}),
		"apple": _d("Яблоко", K.FOOD, 0.2, "#d03030", {"hunger": 10.0, "thirst": 5.0}),
		"water": _d("Бутылка воды", K.DRINK, 0.6, "#60a8e8", {"thirst": 45.0}),
		"soda": _d("Газировка", K.DRINK, 0.4, "#e04848", {"thirst": 25.0, "hunger": 5.0}),
		"bandage": _d("Бинт", K.MED, 0.1, "#f0f0f0", {"heal": 8.0}),
		"rag": _d("Тряпка", K.MED, 0.1, "#b8a888"),
		"disinfectant": _d("Дезинфицирующее", K.MED, 0.3, "#40b060"),
		"tweezers": _d("Пинцет", K.MED, 0.05, "#b0b0b8"),
		"pills": _d("Обезболивающее", K.MED, 0.1, "#80e080", {"heal": 15.0}),
		"knife": _d("Кухонный нож", K.MELEE, 0.4, "#c0c0c8", {"dmg": 22.0, "range": 1.0, "cd": 0.4, "noise": 2.0}),
		"bat": _d("Бейсбольная бита", K.MELEE, 1.5, "#b07840", {"dmg": 38.0, "range": 1.3, "cd": 0.7, "noise": 4.0}),
		"crowbar": _d("Лом", K.MELEE, 2.0, "#707078", {"dmg": 48.0, "range": 1.3, "cd": 0.85, "noise": 5.0}),
		"hammer": _d("Молоток", K.MELEE, 1.0, "#905030", {"dmg": 20.0, "range": 1.0, "cd": 0.55, "noise": 3.0}),
		"pistol": _d("Пистолет", K.GUN, 1.0, "#303034", {"dmg": 70.0, "range": 10.0, "cd": 0.45, "noise": 24.0}),
		"ammo": _d("Патроны 9мм", K.AMMO, 0.02, "#d0b040"),
		"planks": _d("Доски", K.MATERIAL, 1.0, "#a07040"),
	}

static func def(id: String) -> Dictionary:
	return DEFS.get(id, DEFS["fists"])

## Таблицы лута: [id, вес шанса, мин, макс]
static var LOOT: Dictionary = {
	"fridge": [["water", 5, 1, 2], ["soda", 5, 1, 2], ["apple", 4, 1, 3], ["bread", 3, 1, 1], ["canned", 2, 1, 1]],
	"counter": [["canned", 5, 1, 2], ["chips", 4, 1, 1], ["knife", 3, 1, 1], ["bread", 2, 1, 1], ["water", 2, 1, 1], ["disinfectant", 1, 1, 1], ["rag", 2, 1, 1]],
	"wardrobe": [["bat", 2, 1, 1], ["bandage", 2, 1, 1], ["rag", 5, 1, 3], ["planks", 2, 1, 2], ["hammer", 2, 1, 1], ["chips", 1, 1, 1]],
	"shelf": [["chips", 4, 1, 1], ["canned", 3, 1, 1], ["hammer", 2, 1, 1], ["planks", 3, 1, 3], ["crowbar", 1, 1, 1], ["pills", 2, 1, 1]],
	"med": [["bandage", 6, 1, 3], ["disinfectant", 4, 1, 1], ["tweezers", 3, 1, 1], ["pills", 3, 1, 2]],
	"store": [["canned", 6, 1, 3], ["chips", 6, 1, 3], ["water", 6, 1, 3], ["soda", 5, 1, 3], ["bread", 3, 1, 2]],
	"car": [["water", 4, 1, 1], ["chips", 3, 1, 1], ["rag", 3, 1, 2], ["crowbar", 2, 1, 1], ["bandage", 2, 1, 1], ["ammo", 1, 3, 8], ["soda", 3, 1, 1], ["planks", 1, 1, 1]],
	"police": [["pistol", 3, 1, 1], ["ammo", 6, 6, 15], ["bandage", 3, 1, 2], ["crowbar", 2, 1, 1]],
}

# ---------- инвентарь: массив словарей {"id", "n"} ----------
static func add_to(list: Array, id: String, n: int) -> void:
	for s in list:
		if s["id"] == id:
			s["n"] += n
			return
	list.append({"id": id, "n": n})

static func count(list: Array, id: String) -> int:
	for s in list:
		if s["id"] == id:
			return s["n"]
	return 0

static func take(list: Array, id: String, n: int = 1) -> bool:
	for i in list.size():
		var s: Dictionary = list[i]
		if s["id"] == id:
			if s["n"] < n:
				return false
			s["n"] -= n
			if s["n"] <= 0:
				list.remove_at(i)
			return true
	return false

static func weight(list: Array) -> float:
	var w := 0.0
	for s in list:
		w += def(s["id"])["w"] * s["n"]
	return w

static func copy(list: Array) -> Array:
	var out := []
	for s in list:
		out.append({"id": s["id"], "n": int(s["n"])})
	return out
