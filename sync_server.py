import http.server
import socketserver
import socket
import threading
import time
import os
import json
import secrets
import urllib.parse
import urllib.request

PORT = 3000
DIRECTORY = "/app/applet"
SYNC_FILE = os.path.join(DIRECTORY, "sync_data.json")

BROKERS = [("broker.hivemq.com", 1883), ("broker.emqx.io", 1883)]
TOPIC_V34 = "kidsguard/eb6ecfcf/v34_p2p"
TOPIC_V33 = "kidsguard/eb6ecfcf/v33_p2p"

LIVE_LOCK = threading.Lock()
LIVE_STATE = {
    "brokerConnected": False,
    "activeBroker": "",
    "packetsReceived": 0,
    "lastPacketTs": 0,
    "childrenById": {},       # rawId -> dict
    "locksById": {},          # rawId -> {"locked": bool, "ts": int}
    "screensByCode": {},      # pairingCode -> {"activeAppName": str, "base64Bitmap": str, "ts": int, "childId": int}
    "screensById": {},        # rawId -> {"activeAppName": str, "base64Bitmap": str, "ts": int}
    "offlineById": {},        # rawId -> dict (activityLogs, locationPoints, screenshots)
    "appsByCode": {},         # pairingCode -> list of apps
    "rules": [],              # list of content rules
    "chats": [],              # list of chat messages
    "alerts": [],             # list of alerts
    "pcState": None,          # dict from PC_STATE
    "pcScreen": None,         # {"screen_b64": str, "ts": int, "pairing_code": str}
}

MQTT_SOCK_REF = {"sock": None, "lock": threading.Lock()}
BLOB_CACHE = {}


def _mqtt_rem_len(n):
    out = bytearray()
    while True:
        b = n % 128
        n //= 128
        if n > 0:
            b |= 0x80
        out.append(b)
        if n == 0:
            break
    return bytes(out)


def _mqtt_utf8(s):
    b = s.encode("utf-8")
    return bytes([(len(b) >> 8) & 0xFF, len(b) & 0xFF]) + b


def _mqtt_connect_pkt(cid):
    var = _mqtt_utf8("MQTT") + bytes([0x04, 0x02, 0x00, 0x3C]) + _mqtt_utf8(cid)
    return bytes([0x10]) + _mqtt_rem_len(len(var)) + var


def _mqtt_sub_pkt(pid, topic):
    var = bytes([(pid >> 8) & 0xFF, pid & 0xFF]) + _mqtt_utf8(topic) + bytes([0x00])
    return bytes([0x82]) + _mqtt_rem_len(len(var)) + var


def _mqtt_pub_pkt(topic, payload_str, retain=False):
    var = _mqtt_utf8(topic) + payload_str.encode("utf-8")
    hdr = 0x31 if retain else 0x30
    return bytes([hdr]) + _mqtt_rem_len(len(var)) + var


def _mqtt_read_exact(sock, n):
    buf = bytearray()
    while len(buf) < n:
        ch = sock.recv(n - len(buf))
        if not ch:
            raise EOFError("socket closed")
        buf.extend(ch)
    return bytes(buf)


def _mqtt_read_rem_len(sock):
    mult, val = 1, 0
    while True:
        b = _mqtt_read_exact(sock, 1)[0]
        val += (b & 127) * mult
        mult *= 128
        if (b & 128) == 0:
            return val


def publish_mqtt(topic, payload_obj, retain=False):
    s = MQTT_SOCK_REF.get("sock")
    if not s:
        return False
    payload_str = payload_obj if isinstance(payload_obj, str) else json.dumps(payload_obj, ensure_ascii=False)
    pkt = _mqtt_pub_pkt(topic, payload_str, retain=retain)
    with MQTT_SOCK_REF["lock"]:
        try:
            s.sendall(pkt)
            return True
        except Exception:
            return False


def download_blob(key):
    if not key:
        return ""
    if key in BLOB_CACHE:
        return BLOB_CACHE[key]
    try:
        req = urllib.request.Request(
            f"https://bytebin.lucko.me/{key}",
            headers={"User-Agent": "KidsGuardPro/3.3.5"}
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            data = resp.read().decode("utf-8", errors="ignore")
            if data:
                BLOB_CACHE[key] = data
                return data
    except Exception:
        pass
    return ""


def _handle_p2p_packet(topic, raw_json):
    if not raw_json or not raw_json.startswith("{"):
        return
    try:
        msg = json.loads(raw_json)
    except Exception:
        return

    now_ms = int(time.time() * 1000)
    mtype = str(msg.get("type", ""))
    code = str(msg.get("pairingCode") or msg.get("pairing_code") or "").strip()

    with LIVE_LOCK:
        LIVE_STATE["packetsReceived"] += 1
        LIVE_STATE["lastPacketTs"] = now_ms

        if mtype == "PC_STATE" or "/pc_state/" in topic:
            LIVE_STATE["pcState"] = msg
            return

        if mtype == "PC_SCREEN" or "/pc_screen/" in topic:
            b64 = str(msg.get("screen_b64", ""))
            if b64:
                LIVE_STATE["pcScreen"] = {
                    "screen_b64": b64,
                    "ts": int(msg.get("ts") or now_ms),
                    "pairing_code": code or "101-701"
                }
            return

        if mtype == "LOCK_STATE" or topic.startswith(f"{TOPIC_V34}/lock/"):
            cid = int(msg.get("childId") or msg.get("id") or 0)
            if cid > 0:
                locked = bool(msg.get("isDeviceLocked", False))
                ts = int(msg.get("timestamp") or msg.get("ts") or now_ms)
                # Ignore stale retained LOCK_STATE packets older than 20 seconds that try to lock
                if locked and abs(now_ms - ts) > 20000:
                    return
                prev = LIVE_STATE["locksById"].get(cid)
                if not prev or ts >= prev["ts"]:
                    LIVE_STATE["locksById"][cid] = {"locked": locked, "ts": ts, "pairingCode": code}
                if cid in LIVE_STATE["childrenById"]:
                    LIVE_STATE["childrenById"][cid]["isDeviceLocked"] = locked
            return

        if mtype == "CHILD_DELETE":
            cid = int(msg.get("id") or msg.get("childId") or 0)
            LIVE_STATE["childrenById"].pop(cid, None)
            return

        if mtype in ("CHILD_STATE", "CHILD_PATCH") or topic.startswith(f"{TOPIC_V34}/state/"):
            cid = int(msg.get("id") or msg.get("childId") or 0)
            if cid <= 0:
                return
            name = str(msg.get("name", "")).strip()
            if name.lower() == "test":
                return
            prev = LIVE_STATE["childrenById"].get(cid, {})
            merged = dict(prev)
            merged.update(msg)
            merged["id"] = cid
            if cid in LIVE_STATE["locksById"]:
                lock_info = LIVE_STATE["locksById"][cid]
                if lock_info["ts"] >= int(msg.get("ts") or 0):
                    merged["isDeviceLocked"] = lock_info["locked"]
            LIVE_STATE["childrenById"][cid] = merged

            # Check for duplicate child entries with the same pairingCode or same custom name
            norm_name = merged.get("name", "").strip().lower()
            is_custom_name = len(norm_name) >= 2 and norm_name not in ("ребёнок", "ребенок", "алиса", "максим", "child", "test")
            cur_code = str(merged.get("pairingCode") or "").strip()
            cur_ts = int(merged.get("ts") or 0)
            stale_ids = []
            for other_id, other_ch in list(LIVE_STATE["childrenById"].items()):
                if other_id == cid:
                    continue
                other_code = str(other_ch.get("pairingCode") or "").strip()
                other_name = str(other_ch.get("name") or "").strip().lower()
                same_code = bool(cur_code and other_code and cur_code == other_code)
                same_name = bool(is_custom_name and other_name == norm_name)
                if same_code or same_name:
                    other_ts = int(other_ch.get("ts") or 0)
                    if cur_ts >= other_ts:
                        stale_ids.append(other_id)
                    else:
                        stale_ids.append(cid)
            for sid in set(stale_ids):
                LIVE_STATE["childrenById"].pop(sid, None)
                threading.Thread(
                    target=publish_mqtt,
                    args=(f"{TOPIC_V34}/state/{sid}", {"type": "CHILD_DELETE", "id": sid, "ts": now_ms}, True),
                    daemon=True
                ).start()
            return

        if mtype in ("DIRECT_SCREEN_FRAME", "SCREEN_FRAME") or topic.startswith(f"{TOPIC_V34}/screen/"):
            try:
                tail_id = int(topic.rsplit("/", 1)[-1])
            except Exception:
                tail_id = 0
            cid = int(msg.get("childId") or tail_id or 0)
            app_name = str(msg.get("activeAppName") or "Экран телефона")
            b64 = str(msg.get("base64Bitmap") or "")
            blob_key = str(msg.get("blobKey") or "")
            ts = int(msg.get("timestamp") or msg.get("ts") or now_ms)
            if not b64 and blob_key:
                b64 = download_blob(blob_key)
            frame_obj = {
                "childId": cid,
                "pairingCode": code or "838-597",
                "activeAppName": app_name,
                "base64Bitmap": b64,
                "ts": ts
            }
            if b64:
                hist = LIVE_STATE.setdefault("screenHistory", [])
                if not hist or hist[0].get("base64Bitmap") != b64 or (ts - hist[0].get("ts", 0)) > 2000:
                    hist.insert(0, frame_obj)
                    LIVE_STATE["screenHistory"] = hist[:15]
            if cid > 0:
                prev_f = LIVE_STATE["screensById"].get(cid)
                if b64 or not prev_f:
                    if not b64 and prev_f:
                        frame_obj["base64Bitmap"] = prev_f.get("base64Bitmap", "")
                    LIVE_STATE["screensById"][cid] = frame_obj
            if code:
                prev_c = LIVE_STATE["screensByCode"].get(code)
                if b64 or not prev_c or ts >= prev_c.get("ts", 0):
                    if not b64 and prev_c:
                        frame_obj["base64Bitmap"] = prev_c.get("base64Bitmap", "")
                    LIVE_STATE["screensByCode"][code] = frame_obj
            return

        if mtype == "OFFLINE_HISTORY_BUNDLE":
            cid = int(msg.get("childId") or 0)
            if cid > 0:
                LIVE_STATE["offlineById"][cid] = msg
                scr_list = msg.get("screenshots") or []
                if scr_list and isinstance(scr_list, list):
                    latest_scr = scr_list[-1]
                    b64 = str(latest_scr.get("base64Jpeg") or latest_scr.get("base64Bitmap") or "")
                    if b64 and cid not in LIVE_STATE["screensById"]:
                        LIVE_STATE["screensById"][cid] = {
                            "childId": cid,
                            "pairingCode": code,
                            "activeAppName": str(latest_scr.get("activeAppName") or "Экран телефона"),
                            "base64Bitmap": b64,
                            "ts": int(latest_scr.get("timestamp") or now_ms)
                        }
            return

        if mtype == "APPS_LIST":
            apps = msg.get("apps")
            blob_key = str(msg.get("blobKey") or "")
            if not apps and blob_key:
                raw_apps = download_blob(blob_key)
                try:
                    apps = json.loads(raw_apps)
                except Exception:
                    apps = []
            if isinstance(apps, list) and apps:
                LIVE_STATE["appsByCode"][code or str(msg.get("childId") or "default")] = apps
            return

        if mtype == "RULES_LIST":
            rules = msg.get("rules")
            if isinstance(rules, list):
                LIVE_STATE["rules"] = rules
            return

        if mtype == "CHAT":
            mid = msg.get("id") or msg.get("timestamp") or now_ms
            if not any(c.get("id") == mid for c in LIVE_STATE["chats"]):
                LIVE_STATE["chats"].append(msg)
                LIVE_STATE["chats"] = sorted(LIVE_STATE["chats"], key=lambda x: x.get("timestamp", 0))[-50:]
            return

        if mtype == "ALERT":
            aid = msg.get("id") or msg.get("timestamp") or now_ms
            if not any(a.get("id") == aid for a in LIVE_STATE["alerts"]):
                LIVE_STATE["alerts"].insert(0, msg)
                LIVE_STATE["alerts"] = LIVE_STATE["alerts"][:50]
            return


def mqtt_bridge_worker():
    idx = 0
    while True:
        host, port = BROKERS[idx % len(BROKERS)]
        idx += 1
        sock = None
        try:
            sock = socket.create_connection((host, port), timeout=8)
            sock.settimeout(3.0)
            cid = f"kg_web_console_{secrets.token_hex(4)}"
            sock.sendall(_mqtt_connect_pkt(cid))
            hdr = _mqtt_read_exact(sock, 1)[0]
            rl = _mqtt_read_rem_len(sock)
            if rl > 0:
                _mqtt_read_exact(sock, rl)
            if (hdr & 0xF0) != 0x20:
                raise RuntimeError("CONNACK rejected")

            with MQTT_SOCK_REF["lock"]:
                MQTT_SOCK_REF["sock"] = sock
            with LIVE_LOCK:
                LIVE_STATE["brokerConnected"] = True
                LIVE_STATE["activeBroker"] = f"{host}:{port}"

            sock.sendall(_mqtt_sub_pkt(1, f"{TOPIC_V34}/#"))
            sock.sendall(_mqtt_sub_pkt(2, f"{TOPIC_V33}/#"))

            # Request fresh screen frame & sync on connect from both PC and Phone
            publish_mqtt(f"{TOPIC_V33}/pc_cmd/101-701", {"type": "PC_CMD", "pairing_code": "101-701", "action": "/api/sync", "data": {}, "ts": int(time.time() * 1000)})
            publish_mqtt(f"{TOPIC_V33}/pc_cmd/101-701", {"type": "PC_CMD", "pairing_code": "101-701", "action": "/api/screen", "data": {}, "ts": int(time.time() * 1000)})
            for aid in (1, 2, 8):
                publish_mqtt(f"{TOPIC_V34}/cmd/{aid}", {"type": "CMD", "childId": aid, "pairingCode": "838-597", "command": "START_LIVE_STREAM", "payload": "", "timestamp": int(time.time() * 1000)})
                publish_mqtt(f"{TOPIC_V34}/cmd/{aid}", {"type": "CMD", "childId": aid, "pairingCode": "838-597", "command": "TAKE_SCREENSHOT", "payload": "", "timestamp": int(time.time() * 1000)})

            last_ping = time.time()
            while True:
                if time.time() - last_ping >= 25.0:
                    last_ping = time.time()
                    with MQTT_SOCK_REF["lock"]:
                        sock.sendall(bytes([0xC0, 0x00]))
                try:
                    h = _mqtt_read_exact(sock, 1)[0]
                    rem = _mqtt_read_rem_len(sock)
                    body = _mqtt_read_exact(sock, rem) if rem > 0 else b""
                    ptype = h & 0xF0
                    if ptype == 0x30 and len(body) >= 2:
                        tlen = (body[0] << 8) | body[1]
                        topic = body[2:2 + tlen].decode("utf-8", "ignore")
                        pos = 2 + tlen
                        if ((h >> 1) & 0x03) > 0:
                            pos += 2
                        raw_msg = body[pos:].decode("utf-8", "ignore")
                        _handle_p2p_packet(topic, raw_msg)
                except socket.timeout:
                    continue
        except Exception:
            time.sleep(2)
        finally:
            with MQTT_SOCK_REF["lock"]:
                if MQTT_SOCK_REF.get("sock") is sock:
                    MQTT_SOCK_REF["sock"] = None
            with LIVE_LOCK:
                LIVE_STATE["brokerConnected"] = False
            if sock:
                try:
                    sock.close()
                except Exception:
                    pass


def build_live_snapshot():
    with LIVE_LOCK:
        # Step 1: Deduplicate children by pairingCode AND by normalized custom name (preferring newest ts)
        dedup_groups = []
        for cid, ch in LIVE_STATE["childrenById"].items():
            code = str(ch.get("pairingCode") or f"id_{cid}").strip()
            norm_name = str(ch.get("name") or "").strip().lower()
            is_custom_name = len(norm_name) >= 2 and norm_name not in ("ребёнок", "ребенок", "алиса", "максим", "child", "test")
            matched_group = None
            for grp in dedup_groups:
                if code in grp["codes"] or (is_custom_name and norm_name in grp["names"]):
                    matched_group = grp
                    break
            if not matched_group:
                grp = {
                    "codes": {code},
                    "names": {norm_name} if is_custom_name else set(),
                    "ids": [cid],
                    "best": dict(ch)
                }
                dedup_groups.append(grp)
            else:
                matched_group["codes"].add(code)
                if is_custom_name:
                    matched_group["names"].add(norm_name)
                matched_group["ids"].append(cid)
                existing = matched_group["best"]
                ch_is_dev = bool(ch.get("isChildDevice", False))
                ex_is_dev = bool(existing.get("isChildDevice", False))
                ch_ts = int(ch.get("ts") or 0)
                ex_ts = int(existing.get("ts") or 0)
                if (ch_is_dev and not ex_is_dev) or (ch_is_dev == ex_is_dev and ch_ts >= ex_ts):
                    merged = dict(existing)
                    merged.update(ch)
                    matched_group["best"] = merged
                else:
                    merged = dict(ch)
                    merged.update(existing)
                    matched_group["best"] = merged

        children_list = []
        for grp in dedup_groups:
            ch = grp["best"]
            code = str(ch.get("pairingCode") or "").strip()
            cid = int(ch.get("id") or 1)
            all_ids = sorted(list(set(grp["ids"] + [1, 2, 8])))
            all_codes = sorted(list(grp["codes"]))
            # Find best screen frame across all alias codes and IDs
            best_screen = None
            for c_alias in all_codes:
                cand = LIVE_STATE["screensByCode"].get(c_alias)
                if cand and (not best_screen or (cand.get("base64Bitmap") and not best_screen.get("base64Bitmap")) or cand.get("ts", 0) > best_screen.get("ts", 0)):
                    best_screen = cand
            for aid in all_ids:
                cand = LIVE_STATE["screensById"].get(aid)
                if cand and (not best_screen or (cand.get("base64Bitmap") and not best_screen.get("base64Bitmap")) or cand.get("ts", 0) > best_screen.get("ts", 0)):
                    best_screen = cand
            # Combine offline history bundles across alias IDs
            combined_logs = []
            combined_locs = []
            combined_shots = []
            for aid in all_ids:
                b = LIVE_STATE["offlineById"].get(aid)
                if b:
                    combined_logs.extend(b.get("activityLogs") or [])
                    combined_locs.extend(b.get("locationPoints") or [])
                    combined_shots.extend(b.get("screenshots") or [])
            # Find apps across all alias codes or alias IDs
            apps = None
            for c_alias in all_codes:
                if c_alias in LIVE_STATE["appsByCode"]:
                    apps = LIVE_STATE["appsByCode"][c_alias]
                    break
            if not apps:
                for aid in all_ids:
                    if str(aid) in LIVE_STATE["appsByCode"]:
                        apps = LIVE_STATE["appsByCode"][str(aid)]
                        break
            if not apps and combined_logs:
                seen_pkgs = {}
                for log_item in combined_logs:
                    pkg = str(log_item.get("packageName") or "")
                    app_name = str(log_item.get("appName") or pkg)
                    if pkg and pkg not in seen_pkgs:
                        seen_pkgs[pkg] = {
                            "packageName": pkg,
                            "appName": app_name,
                            "category": "Активные на устройстве",
                            "usedMinutesToday": int(log_item.get("durationSeconds", 0)) // 60,
                            "dailyLimitMinutes": 0,
                            "isBlocked": False
                        }
                apps = list(seen_pkgs.values())

            ch_out = dict(ch)
            ch_out["aliasIds"] = all_ids
            ch_out["aliasCodes"] = all_codes
            ch_out["activeForegroundApp"] = (best_screen or {}).get("activeAppName") or "Активный экран Android"
            ch_out["screenBase64"] = (best_screen or {}).get("base64Bitmap") or ""
            ch_out["screenTimestamp"] = (best_screen or {}).get("ts") or 0
            ch_out["apps"] = apps or []
            ch_out["offlineBundle"] = {
                "activityLogs": combined_logs,
                "locationPoints": combined_locs,
                "screenshots": combined_shots
            }
            children_list.append(ch_out)

        children_list.sort(key=lambda x: int(x.get("id") or 0))

        return {
            "ok": True,
            "connected": LIVE_STATE["brokerConnected"],
            "brokerConnected": LIVE_STATE["brokerConnected"],
            "activeBroker": LIVE_STATE["activeBroker"],
            "packetsReceived": LIVE_STATE["packetsReceived"],
            "lastPacketTs": LIVE_STATE["lastPacketTs"],
            "serverTimeMs": int(time.time() * 1000),
            "children": children_list,
            "screenHistory": LIVE_STATE.get("screenHistory", []),
            "rules": LIVE_STATE["rules"],
            "chats": LIVE_STATE["chats"],
            "alerts": LIVE_STATE["alerts"],
            "pcState": LIVE_STATE["pcState"],
            "pcScreen": LIVE_STATE["pcScreen"]
        }


class CustomHandler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=DIRECTORY, **kwargs)

    def log_message(self, format, *args):
        pass

    def end_headers(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, HEAD, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type')
        self.send_header('Cache-Control', 'no-cache, no-store, must-revalidate')
        super().end_headers()

    def do_OPTIONS(self):
        self.send_response(200)
        self.end_headers()

    def _send_json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        try:
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(length).decode('utf-8', errors='replace') if length > 0 else '{}'

        if parsed.path == '/api/p2p_cmd':
            try:
                req = json.loads(body)
            except Exception as e:
                return self._send_json(400, {"ok": False, "error": str(e)})

            target_type = req.get("target", "phone")  # "phone" | "pc"
            now_ms = int(time.time() * 1000)

            if target_type == "pc":
                code = str(req.get("pairingCode") or "101-701").strip() or "101-701"
                action = str(req.get("action") or "/api/sync")
                data = req.get("data") or {}
                pwd = str(req.get("password") or "")
                # Also update local LIVE_STATE["pcState"] immediately for instant UI responsiveness
                with LIVE_LOCK:
                    ps = LIVE_STATE.get("pcState")
                    if isinstance(ps, dict):
                        if action == "/api/full_lock":
                            fl = ps.setdefault("full", {})
                            if "lock_now" in data:
                                fl["now"] = bool(data["lock_now"])
                                fl["blocked"] = bool(data["lock_now"])
                        elif action == "/api/toggle":
                            rname = data.get("name")
                            for ent in ps.get("entries", []):
                                if ent.get("name") == rname:
                                    ent["enabled"] = not ent.get("enabled", False)
                                    ent["blocked"] = ent["enabled"]
                pkt = {
                    "type": "PC_CMD",
                    "pairing_code": code,
                    "action": action,
                    "data": data,
                    "ts": now_ms
                }
                if pwd:
                    pkt["password"] = pwd
                for root in (TOPIC_V33, TOPIC_V34):
                    publish_mqtt(f"{root}/pc_cmd/{code}", pkt, retain=False)
                return self._send_json(200, {"ok": True, "sent": pkt})

            # Phone command (accepts both 'command' and 'action' keys)
            child_id = int(req.get("childId") or 8)
            pairing_code = str(req.get("pairingCode") or "838-597").strip()
            alias_ids = req.get("aliasIds") or [child_id, 1, 2, 8]
            cmd = str(req.get("command") or req.get("action") or "")
            payload = str(req.get("payload") or "")
            if cmd == "LOCK_DEVICE":
                cmd = "LOCK"
            elif cmd == "UNLOCK_DEVICE":
                cmd = "UNLOCK"
            elif cmd == "PLAY_SIREN":
                cmd = "SIREN"
            elif cmd == "SCAN_APPS":
                cmd = "REQUEST_APPS"

            if cmd in ("LOCK", "UNLOCK"):
                locked = (cmd == "LOCK")
                with LIVE_LOCK:
                    for aid in set(alias_ids):
                        LIVE_STATE["locksById"][int(aid)] = {"locked": locked, "ts": now_ms, "pairingCode": pairing_code}
                        if int(aid) in LIVE_STATE["childrenById"]:
                            LIVE_STATE["childrenById"][int(aid)]["isDeviceLocked"] = locked
                for aid in set(alias_ids):
                    lock_pkt = {
                        "type": "LOCK_STATE",
                        "childId": int(aid),
                        "id": int(aid),
                        "pairingCode": pairing_code,
                        "isDeviceLocked": locked,
                        "timestamp": now_ms,
                        "ts": now_ms
                    }
                    publish_mqtt(f"{TOPIC_V34}/lock/{int(aid)}", lock_pkt, retain=True)
                    publish_mqtt(f"{TOPIC_V33}/lock/{int(aid)}", lock_pkt, retain=True)

            if cmd == "SOS_EMERGENCY":
                child_name = str(req.get("childName") or "Ребёнок")
                addr = str(req.get("address") or "")
                lat = req.get("lat") or 0.0
                lng = req.get("lng") or 0.0
                alert_pkt = {
                    "type": "ALERT",
                    "id": now_ms,
                    "childId": child_id,
                    "pairingCode": pairing_code,
                    "alertType": "SOS",
                    "title": f"🚨 ЭКСТРЕННЫЙ ВЫЗОВ SOS ({child_name})!",
                    "message": f"Ребёнок нажал кнопку экстренной связи SOS! Координаты: {addr} ({lat}, {lng})",
                    "timestamp": now_ms,
                    "isRead": False
                }
                with LIVE_LOCK:
                    LIVE_STATE["alerts"].insert(0, alert_pkt)
                for aid in set(alias_ids):
                    publish_mqtt(f"{TOPIC_V34}/data/{int(aid)}", alert_pkt, retain=True)
                return self._send_json(200, {"ok": True, "alert": alert_pkt})

            if cmd in ("CHILD_PATCH", "GRANT_BONUS_TIME", "UPDATE_SCREEN_LIMIT", "UPDATE_BEDTIME"):
                fields = req.get("fields") or {}
                if "dailyLimitMinutes" in req:
                    fields["dailyScreenLimitMinutes"] = int(req["dailyLimitMinutes"])
                if "bedtimeEnabled" in req:
                    fields["bedtimeEnabled"] = bool(req["bedtimeEnabled"])
                with LIVE_LOCK:
                    for aid in set(alias_ids):
                        if int(aid) in LIVE_STATE["childrenById"]:
                            LIVE_STATE["childrenById"][int(aid)].update(fields)
                for aid in set(alias_ids):
                    patch_pkt = {"type": "CHILD_PATCH", "id": int(aid), "pairingCode": pairing_code, "ts": now_ms}
                    patch_pkt.update(fields)
                    publish_mqtt(f"{TOPIC_V34}/state/{int(aid)}", patch_pkt, retain=True)
                return self._send_json(200, {"ok": True})

            if cmd in ("CHAT_SEND", "CHAT_MESSAGE"):
                msg_txt = str(req.get("messageText") or payload or "")
                chat_pkt = {
                    "type": "CHAT",
                    "id": now_ms,
                    "childId": child_id,
                    "pairingCode": pairing_code,
                    "senderRole": str(req.get("senderRole") or "PARENT"),
                    "senderName": str(req.get("senderName") or "Родитель"),
                    "text": msg_txt,
                    "messageText": msg_txt,
                    "timestamp": now_ms,
                    "isAttentionSignal": bool(req.get("isAttentionSignal") or req.get("isAttentionGrabber", False))
                }
                with LIVE_LOCK:
                    LIVE_STATE["chats"].append(chat_pkt)
                for aid in set(alias_ids):
                    publish_mqtt(f"{TOPIC_V34}/data/{int(aid)}", chat_pkt, retain=True)
                    publish_mqtt(f"{TOPIC_V34}/data/chat_{int(aid)}", chat_pkt, retain=True)
                return self._send_json(200, {"ok": True, "chat": chat_pkt})

            for aid in set(alias_ids):
                cmd_pkt = {
                    "type": "CMD",
                    "childId": int(aid),
                    "pairingCode": pairing_code,
                    "command": cmd,
                    "payload": payload,
                    "timestamp": now_ms
                }
                publish_mqtt(f"{TOPIC_V34}/cmd/{int(aid)}", cmd_pkt, retain=False)

            return self._send_json(200, {"ok": True, "command": cmd, "aliasIds": list(set(alias_ids))})

        if parsed.path == '/api/sync':
            try:
                data = json.loads(body)
                with open(SYNC_FILE, 'w', encoding='utf-8') as f:
                    json.dump(data, f, ensure_ascii=False, indent=2)
                return self._send_json(200, {"ok": True})
            except Exception as e:
                return self._send_json(400, {"ok": False, "error": str(e)})

        self.send_response(404)
        self.end_headers()

    def _serve_custom_file(self, file_path, content_type, attachment_name=None, head_only=False):
        if not os.path.exists(file_path):
            return False
        try:
            self.send_response(200)
            self.send_header('Content-Type', content_type)
            self.send_header('Content-Length', str(os.path.getsize(file_path)))
            if attachment_name:
                self.send_header('Content-Disposition', f'attachment; filename="{attachment_name}"')
            self.end_headers()
            if not head_only:
                with open(file_path, 'rb') as f:
                    while True:
                        chunk = f.read(65536)
                        if not chunk:
                            break
                        self.wfile.write(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass
        return True

    def _handle_request(self, head_only=False):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path

        if path == '/api/live':
            snap = build_live_snapshot()
            body = json.dumps(snap, ensure_ascii=False).encode('utf-8')
            self.send_response(200)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            if not head_only:
                try:
                    self.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError):
                    pass
            return

        if path == '/api/sync':
            payload = b'{}'
            if os.path.exists(SYNC_FILE):
                with open(SYNC_FILE, 'rb') as f:
                    payload = f.read()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(payload)))
            self.end_headers()
            if not head_only:
                try:
                    self.wfile.write(payload)
                except (BrokenPipeError, ConnectionResetError):
                    pass
            return

        if path == '/hero_family_safety.jpg':
            img_path = os.path.join(DIRECTORY, 'app/src/main/res/drawable/hero_family_safety_1791372048354.jpg')
            if self._serve_custom_file(img_path, 'image/jpeg', head_only=head_only):
                return

        if path == '/app_icon_fg.jpg':
            img_path = os.path.join(DIRECTORY, 'app/src/main/res/drawable/app_icon_fg_1791372032758.jpg')
            if self._serve_custom_file(img_path, 'image/jpeg', head_only=head_only):
                return

        if path == '/hosts_blocker.py':
            py_path = os.path.join(DIRECTORY, 'app/src/main/assets/hosts_blocker.py')
            if not os.path.exists(py_path):
                py_path = os.path.join(DIRECTORY, 'hosts_blocker.py')
            if self._serve_custom_file(py_path, 'text/x-python; charset=utf-8', 'hosts_blocker.py', head_only=head_only):
                return

        if path.endswith('.apk'):
            file_path = os.path.join(DIRECTORY, 'app/build/outputs/apk/debug/app-debug.apk')
            if not os.path.exists(file_path):
                file_path = os.path.join(DIRECTORY, path.lstrip('/'))
            if not os.path.exists(file_path):
                file_path = os.path.join(DIRECTORY, 'KidsGuard-latest.apk')
            filename = os.path.basename(path) if path.endswith('.apk') else "KidsGuard-v3.3.5-Pro.apk"
            if self._serve_custom_file(file_path, 'application/vnd.android.package-archive', filename, head_only=head_only):
                return

        try:
            if head_only:
                return super().do_HEAD()
            return super().do_GET()
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_HEAD(self):
        self._handle_request(head_only=True)

    def do_GET(self):
        self._handle_request(head_only=False)


class ReusableThreadingTCPServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    t = threading.Thread(target=mqtt_bridge_worker, daemon=True)
    t.start()
    with ReusableThreadingTCPServer(("0.0.0.0", PORT), CustomHandler) as httpd:
        print(f"Serving at http://0.0.0.0:{PORT}", flush=True)
        httpd.serve_forever()
