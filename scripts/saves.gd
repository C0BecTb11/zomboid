class_name Saves
extends RefCounted
## Сохранение мира: запись через временный файл, резервная копия предыдущего удачного сохранения,
## проверка формата при загрузке.

const PATH := "user://save.json"
const BACKUP := "user://save.bak.json"
static var _main_ok := false      # основной файл уже проверен/записан нами — повторно не разбираем

static func exists() -> bool:
	return FileAccess.file_exists(PATH) or FileAccess.file_exists(BACKUP)

static func save(g: GameState) -> void:
	var text := JSON.stringify(g.to_data())
	var tmp := PATH + ".tmp"
	var f := FileAccess.open(tmp, FileAccess.WRITE)
	if f == null:
		return
	f.store_string(text)
	f.close()
	# убедиться, что файл записан целиком, прежде чем заменять сохранение
	if FileAccess.get_file_as_string(tmp).length() != text.length():
		return
	if _main_ok or _read(PATH) != null:
		DirAccess.copy_absolute(ProjectSettings.globalize_path(PATH), ProjectSettings.globalize_path(BACKUP))
	_main_ok = DirAccess.rename_absolute(ProjectSettings.globalize_path(tmp), ProjectSettings.globalize_path(PATH)) == OK

static func _read(path: String):
	if not FileAccess.file_exists(path):
		return null
	var d = JSON.parse_string(FileAccess.get_file_as_string(path))
	return d if GameState.valid_save(d) else null

static func load_game() -> GameState:
	for path in [PATH, BACKUP]:
		var d = _read(path)
		if d == null:
			continue
		var g := GameState.new(int(d.get("seed", 0)))
		g.load_data(d)
		var alive := false
		for p in g.players:
			if not p.dead:
				alive = true
		if alive:
			return g
	return null

static func delete() -> void:
	_main_ok = false
	for path in [PATH, BACKUP]:
		if FileAccess.file_exists(path):
			DirAccess.remove_absolute(ProjectSettings.globalize_path(path))
