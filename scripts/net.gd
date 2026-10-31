extends Node
## Сеть на двоих через ENet. Хост считает игру, клиент шлёт ввод и получает снимки.

const PORT := 7777

var inbox: Array = []
var connected := false
var status := ""
var is_host := false
var peer_id := 0

func _ready() -> void:
	multiplayer.peer_connected.connect(_on_peer_connected)
	multiplayer.peer_disconnected.connect(_on_peer_disconnected)
	multiplayer.connected_to_server.connect(func(): connected = true; status = "Подключено")
	multiplayer.connection_failed.connect(func(): status = "Не удалось подключиться"; _lost())
	multiplayer.server_disconnected.connect(func(): status = "Соединение потеряно"; _lost())

func host() -> void:
	close()
	var p := ENetMultiplayerPeer.new()
	var err := p.create_server(PORT, 1)
	if err != OK:
		status = "Ошибка сервера: %d" % err
		return
	multiplayer.multiplayer_peer = p
	is_host = true
	status = "Ждём второго игрока…"

func join(ip: String) -> void:
	close()
	var p := ENetMultiplayerPeer.new()
	var err := p.create_client(ip, PORT)
	if err != OK:
		status = "Не удалось подключиться"
		return
	multiplayer.multiplayer_peer = p
	is_host = false
	status = "Подключение к %s…" % ip

func close() -> void:
	if multiplayer.multiplayer_peer != null and not (multiplayer.multiplayer_peer is OfflineMultiplayerPeer):
		multiplayer.multiplayer_peer.close()
	multiplayer.multiplayer_peer = OfflineMultiplayerPeer.new()
	connected = false
	is_host = false
	peer_id = 0
	inbox.clear()

func active() -> bool:
	return not (multiplayer.multiplayer_peer is OfflineMultiplayerPeer)

func _on_peer_connected(id: int) -> void:
	if is_host:
		if connected:
			multiplayer.multiplayer_peer.disconnect_peer(id)
			return
		peer_id = id
		connected = true
		status = "Подключено"

func _on_peer_disconnected(id: int) -> void:
	if is_host and id == peer_id:
		connected = false
		peer_id = 0
		inbox.append({"t": "disconnect"})

func _lost() -> void:
	connected = false
	inbox.append({"t": "disconnect"})

func send(msg: Dictionary) -> void:
	if not connected:
		return
	if is_host:
		_recv.rpc_id(peer_id, msg)
	else:
		_recv.rpc_id(1, msg)

@rpc("any_peer", "reliable")
func _recv(msg: Dictionary) -> void:
	inbox.append(msg)

static func local_ip() -> String:
	var best := ""
	for a in IP.get_local_addresses():
		if a.contains(":") or a.begins_with("127."):
			continue
		if a.begins_with("192.168."):
			return a
		if best == "" or a.begins_with("10.") or a.begins_with("172."):
			best = a
	return best if best != "" else "?"
