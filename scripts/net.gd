extends Node
## Сеть на двоих через ENet + поиск миров в локальной сети.
## Канал 0 — надёжный (события), канал 1 — ненадёжный (позиции, 20 раз в секунду).

const PORT := 7777
const DISCOVERY_PORT := 7778      # хост слушает запросы «кто здесь?»
const REPLY_PORT := 7779          # клиент слушает ответы и объявления
const MAGIC := "ZMB1"

var inbox: Array = []
var connected := false
var status := ""
var is_host := false
var peer_id := 0

# ---------- профиль ----------
var uid := ""
var nick := ""

# ---------- объявление мира (хост) ----------
var adv_info: Callable           # () -> Dictionary
var adv_sock: PacketPeerUDP
var adv_t := 0.0

# ---------- поиск миров (клиент) ----------
var scan_sock: PacketPeerUDP
var scan_t := 0.0
var servers := {}                # ip → {info, seen}

func _ready() -> void:
	_load_profile()
	multiplayer.peer_connected.connect(_on_peer_connected)
	multiplayer.peer_disconnected.connect(_on_peer_disconnected)
	multiplayer.connected_to_server.connect(_on_connected)
	multiplayer.connection_failed.connect(func(): status = "Не удалось подключиться"; _lost())
	multiplayer.server_disconnected.connect(func(): status = "Соединение потеряно"; _lost())

func _load_profile() -> void:
	var cfg := ConfigFile.new()
	cfg.load("user://settings.cfg")
	uid = cfg.get_value("profile", "uid", "")
	nick = cfg.get_value("profile", "nick", "")
	if uid == "":
		var c := Crypto.new()
		uid = c.generate_random_bytes(12).hex_encode()
	if nick == "":
		nick = "Выживший-%s" % uid.substr(0, 4).to_upper()
	save_profile()

func save_profile() -> void:
	var cfg := ConfigFile.new()
	cfg.load("user://settings.cfg")
	cfg.set_value("profile", "uid", uid)
	cfg.set_value("profile", "nick", nick)
	cfg.save("user://settings.cfg")

# ================== ENet ==================
func host(info: Callable) -> void:
	close()
	var p := ENetMultiplayerPeer.new()
	var err := p.create_server(PORT, 1, 2)
	if err != OK:
		status = "Ошибка сервера: %d" % err
		return
	multiplayer.multiplayer_peer = p
	is_host = true
	status = "Ждём друга…"
	adv_info = info
	adv_sock = PacketPeerUDP.new()
	adv_sock.set_broadcast_enabled(true)
	if adv_sock.bind(DISCOVERY_PORT) != OK:
		adv_sock = null

func join(ip: String) -> void:
	stop_scan()
	close()
	var p := ENetMultiplayerPeer.new()
	var err := p.create_client(ip, PORT, 2)
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
	if adv_sock != null:
		adv_sock.close()
		adv_sock = null

func _on_connected() -> void:
	connected = true
	status = "Подключено"
	send({"t": "hello", "uid": uid, "nick": nick})

func _on_peer_connected(id: int) -> void:
	if is_host:
		if connected:
			multiplayer.multiplayer_peer.disconnect_peer(id)
			return
		peer_id = id
		connected = true
		status = "Друг подключается…"

func _on_peer_disconnected(id: int) -> void:
	if is_host and id == peer_id:
		connected = false
		peer_id = 0
		inbox.append({"t": "disconnect"})

func _lost() -> void:
	connected = false
	inbox.append({"t": "disconnect"})

func _target() -> int:
	return peer_id if is_host else 1

## Надёжно: события, действия, вход
func send(msg: Dictionary) -> void:
	if connected:
		_recv_rel.rpc_id(_target(), msg)

## Быстро, без гарантии: позиции и ввод. Старые пакеты отбрасываются
func send_fast(msg: Dictionary) -> void:
	if connected:
		_recv_fast.rpc_id(_target(), msg)

@rpc("any_peer", "call_remote", "reliable", 0)
func _recv_rel(msg: Dictionary) -> void:
	inbox.append(msg)

@rpc("any_peer", "call_remote", "unreliable_ordered", 1)
func _recv_fast(msg: Dictionary) -> void:
	inbox.append(msg)

# ================== ПОИСК МИРОВ ==================
func start_scan() -> void:
	stop_scan()
	servers.clear()
	scan_sock = PacketPeerUDP.new()
	scan_sock.set_broadcast_enabled(true)
	if scan_sock.bind(REPLY_PORT) != OK:
		scan_sock.bind(0)
	scan_t = 0.0

func stop_scan() -> void:
	if scan_sock != null:
		scan_sock.close()
		scan_sock = null

func _process(dt: float) -> void:
	# хост: отвечает на запросы и раз в секунду объявляет мир
	if adv_sock != null and adv_info.is_valid():
		while adv_sock.get_available_packet_count() > 0:
			var pkt := adv_sock.get_packet().get_string_from_utf8()
			if pkt.begins_with(MAGIC + "?"):
				adv_sock.set_dest_address(adv_sock.get_packet_ip(), adv_sock.get_packet_port())
				adv_sock.put_packet((MAGIC + "!" + JSON.stringify(adv_info.call())).to_utf8_buffer())
		adv_t -= dt
		if adv_t <= 0.0:
			adv_t = 1.0
			var data := (MAGIC + "!" + JSON.stringify(adv_info.call())).to_utf8_buffer()
			adv_sock.set_dest_address("255.255.255.255", REPLY_PORT)
			adv_sock.put_packet(data)
	# клиент: собирает ответы, раз в 2 секунды опрашивает всю подсеть
	if scan_sock != null:
		while scan_sock.get_available_packet_count() > 0:
			var raw := scan_sock.get_packet().get_string_from_utf8()
			var ip := scan_sock.get_packet_ip()
			if raw.begins_with(MAGIC + "!"):
				var info = JSON.parse_string(raw.substr(MAGIC.length() + 1))
				if info is Dictionary:
					servers[ip] = {"info": info, "seen": Time.get_ticks_msec()}
		for ip in servers.keys():
			if Time.get_ticks_msec() - servers[ip]["seen"] > 6000:
				servers.erase(ip)
		scan_t -= dt
		if scan_t <= 0.0:
			scan_t = 2.0
			_probe()

func _probe() -> void:
	var q := (MAGIC + "?").to_utf8_buffer()
	var targets := ["255.255.255.255", "127.0.0.1"]
	for a in IP.get_local_addresses():
		if a.contains(":") or a.begins_with("127."):
			continue
		var parts := a.split(".")
		if parts.size() != 4:
			continue
		var base := "%s.%s.%s." % [parts[0], parts[1], parts[2]]
		targets.append(base + "255")
		for i in range(1, 255):
			targets.append(base + str(i))
	for t in targets:
		scan_sock.set_dest_address(t, DISCOVERY_PORT)
		scan_sock.put_packet(q)

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
