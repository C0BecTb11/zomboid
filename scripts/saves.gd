class_name Saves
extends RefCounted

const PATH := "user://save.json"

static func exists() -> bool:
	return FileAccess.file_exists(PATH)

static func save(g: GameState) -> void:
	var tmp := PATH + ".tmp"
	var f := FileAccess.open(tmp, FileAccess.WRITE)
	if f == null:
		return
	f.store_string(JSON.stringify(g.to_data()))
	f.close()
	DirAccess.rename_absolute(ProjectSettings.globalize_path(tmp), ProjectSettings.globalize_path(PATH))

static func load_game() -> GameState:
	if not exists():
		return null
	var f := FileAccess.open(PATH, FileAccess.READ)
	if f == null:
		return null
	var d = JSON.parse_string(f.get_as_text())
	f.close()
	if not (d is Dictionary):
		return null
	var g := GameState.new(int(d.get("seed", 0)))
	g.load_data(d)
	var alive := false
	for p in g.players:
		if not p.dead:
			alive = true
	return g if alive else null

static func delete() -> void:
	if exists():
		DirAccess.remove_absolute(ProjectSettings.globalize_path(PATH))
