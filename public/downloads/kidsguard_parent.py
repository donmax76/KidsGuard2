#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
KidsGuard Родитель (Windows Desktop App) — v1.0 (совместимо с Android v3.3.35 Pro.79+ и hosts_blocker.py v5.6+)

Полноценная настольная программа родителя под Windows 10/11:
  - Отдельное нативное окно (Edge WebView2 через pywebview с авто-откатом на Edge/Chrome --app).
  - Управление телефоном ребёнка (экран, блокировка, пробуждение, сирена, приложения, лимиты, веб-фильтр, GPS-карта, чат).
  - Управление ПК ребёнка (живой экран LAN MJPEG / P2P, полная блокировка, правила сайтов/программ,
    объединённая статистика окон за день, двусторонний чат с ПК и тумблер иконки в трее ПК).
  - Нативные уведомления Windows и системный трей (чат, SOS, тревоги).
  - Постоянный локальный архив истории в SQLite (%APPDATA%/KidsGuardParent/history.db) с поиском и экспортом CSV/JSON.
  - Защита входа мастер PIN-кодом (настраивается или отключается).

Сборка в один .exe на Windows:
  pip install pywebview pillow pyinstaller
  pyinstaller --noconsole --onefile --name KidsGuardParent kidsguard_parent.py
"""

import base64
import csv
import ctypes
from ctypes import wintypes
import hashlib
import http.server
import io
import json
import os
import re
import secrets
import shutil
import socket
import socketserver
import sqlite3
import ssl
import struct
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
from datetime import datetime

APP_NAME = "KidsGuard Родитель"
APP_VERSION = "1.0.0"
COMPAT_VERSION = "3.3.35 Pro.79"

TOPIC_V34 = "kidsguard/eb6ecfcf/v34_p2p"
TOPIC_V33 = "kidsguard/eb6ecfcf/v33_p2p"
PUBLIC_BROKERS = [
    ("broker.emqx.io", 1883),
    ("broker.hivemq.com", 1883),
]

IS_WINDOWS = os.name == "nt"


# =============================================================================
# 1. PATHS & PERSISTENT CONFIGURATION
# =============================================================================

def get_app_data_dir():
    if IS_WINDOWS:
        base = os.environ.get("APPDATA") or os.path.expanduser("~")
        d = os.path.join(base, "KidsGuardParent")
    else:
        d = os.path.join(os.path.expanduser("~"), ".kidsguard_parent")
    os.makedirs(d, exist_ok=True)
    return d


DATA_DIR = get_app_data_dir()
CONFIG_FILE = os.path.join(DATA_DIR, "config.json")
DB_FILE = os.path.join(DATA_DIR, "history.db")

DEFAULT_CONFIG = {
    "parent_name": "Родитель",
    "phone_pairing_code": "838-597",
    "pc_pairing_code": "101-701",
    "pc_lan_ip": "",
    "pc_lan_port": 8765,
    "pc_password": "",
    "vps_enabled": False,
    "vps_host": "",
    "vps_port": 443,
    "vps_password": "1234",
    "pin_enabled": True,
    "master_pin": "1234",
    "minimize_to_tray": True,
    "notifications_enabled": True,
    "sound_enabled": True,
}


def load_config():
    cfg = dict(DEFAULT_CONFIG)
    if os.path.exists(CONFIG_FILE):
        try:
            with open(CONFIG_FILE, "r", encoding="utf-8") as f:
                raw = json.load(f)
            if isinstance(raw, dict):
                cfg.update(raw)
        except Exception:
            pass
    return cfg


def save_config(cfg):
    tmp = CONFIG_FILE + ".tmp"
    try:
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(cfg, f, ensure_ascii=False, indent=2)
        os.replace(tmp, CONFIG_FILE)
        return True
    except Exception:
        return False


# =============================================================================
# 2. LOCAL SQLITE ARCHIVE (PERSISTENT HISTORY FOR PHONE & PC)
# =============================================================================

class LocalHistoryArchive:
    def __init__(self, db_path):
        self.db_path = db_path
        self.lock = threading.Lock()
        self._init_db()

    def _connect(self):
        conn = sqlite3.connect(self.db_path, timeout=10, check_same_thread=False)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        return conn

    def _init_db(self):
        with self.lock:
            conn = self._connect()
            try:
                conn.executescript("""
                    CREATE TABLE IF NOT EXISTS chat_messages (
                        id TEXT PRIMARY KEY,
                        source TEXT NOT NULL DEFAULT 'PHONE',
                        sender_role TEXT NOT NULL,
                        sender_name TEXT NOT NULL,
                        text TEXT NOT NULL,
                        ts INTEGER NOT NULL,
                        is_attention INTEGER NOT NULL DEFAULT 0
                    );
                    CREATE INDEX IF NOT EXISTS idx_chat_ts ON chat_messages(ts DESC);

                    CREATE TABLE IF NOT EXISTS alerts (
                        id TEXT PRIMARY KEY,
                        child_id INTEGER NOT NULL DEFAULT 0,
                        alert_type TEXT NOT NULL DEFAULT 'ALERT',
                        title TEXT NOT NULL,
                        message TEXT NOT NULL,
                        ts INTEGER NOT NULL,
                        is_read INTEGER NOT NULL DEFAULT 0
                    );
                    CREATE INDEX IF NOT EXISTS idx_alerts_ts ON alerts(ts DESC);

                    CREATE TABLE IF NOT EXISTS phone_activity (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        day TEXT NOT NULL,
                        child_id INTEGER NOT NULL DEFAULT 0,
                        child_name TEXT NOT NULL DEFAULT '',
                        app_name TEXT NOT NULL,
                        package_name TEXT NOT NULL,
                        duration_sec INTEGER NOT NULL DEFAULT 0,
                        ts INTEGER NOT NULL,
                        UNIQUE(day, child_id, package_name, ts)
                    );
                    CREATE INDEX IF NOT EXISTS idx_phone_act_day ON phone_activity(day DESC, ts DESC);

                    CREATE TABLE IF NOT EXISTS pc_activity_daily (
                        day TEXT NOT NULL,
                        user_name TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        label TEXT NOT NULL,
                        seconds INTEGER NOT NULL DEFAULT 0,
                        started_at INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (day, user_name, kind, label)
                    );
                    CREATE INDEX IF NOT EXISTS idx_pc_act_day ON pc_activity_daily(day DESC, seconds DESC);

                    CREATE TABLE IF NOT EXISTS location_points (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        child_id INTEGER NOT NULL DEFAULT 0,
                        lat REAL NOT NULL,
                        lng REAL NOT NULL,
                        address TEXT NOT NULL DEFAULT '',
                        speed_kmh REAL NOT NULL DEFAULT 0,
                        ts INTEGER NOT NULL,
                        UNIQUE(child_id, ts)
                    );
                    CREATE INDEX IF NOT EXISTS idx_loc_ts ON location_points(ts DESC);
                """)
                conn.commit()
            finally:
                conn.close()

    def add_chat(self, msg_id, source, sender_role, sender_name, text, ts, is_attention=False):
        if not text:
            return False
        mid = str(msg_id or f"{source}_{ts}_{hashlib.md5(text.encode('utf-8')).hexdigest()[:8]}")
        with self.lock:
            conn = self._connect()
            try:
                cur = conn.execute(
                    """INSERT OR IGNORE INTO chat_messages
                       (id, source, sender_role, sender_name, text, ts, is_attention)
                       VALUES (?, ?, ?, ?, ?, ?, ?)""",
                    (mid, source, sender_role, sender_name, text, int(ts), 1 if is_attention else 0),
                )
                conn.commit()
                return cur.rowcount > 0
            finally:
                conn.close()

    def get_chats(self, limit=120):
        with self.lock:
            conn = self._connect()
            try:
                rows = conn.execute(
                    "SELECT * FROM chat_messages ORDER BY ts DESC LIMIT ?",
                    (limit,),
                ).fetchall()
                items = [dict(r) for r in reversed(rows)]
                return items
            finally:
                conn.close()

    def add_alert(self, alert_id, child_id, alert_type, title, message, ts, is_read=False):
        aid = str(alert_id or f"alert_{ts}_{hashlib.md5((title + message).encode('utf-8')).hexdigest()[:8]}")
        with self.lock:
            conn = self._connect()
            try:
                cur = conn.execute(
                    """INSERT OR IGNORE INTO alerts
                       (id, child_id, alert_type, title, message, ts, is_read)
                       VALUES (?, ?, ?, ?, ?, ?, ?)""",
                    (aid, int(child_id or 0), str(alert_type or "ALERT"), str(title), str(message), int(ts), 1 if is_read else 0),
                )
                conn.commit()
                return cur.rowcount > 0
            finally:
                conn.close()

    def get_alerts(self, limit=150):
        with self.lock:
            conn = self._connect()
            try:
                rows = conn.execute(
                    "SELECT * FROM alerts ORDER BY ts DESC LIMIT ?",
                    (limit,),
                ).fetchall()
                return [dict(r) for r in rows]
            finally:
                conn.close()

    def mark_alerts_read(self):
        with self.lock:
            conn = self._connect()
            try:
                conn.execute("UPDATE alerts SET is_read = 1 WHERE is_read = 0")
                conn.commit()
            finally:
                conn.close()

    def upsert_phone_activity(self, child_id, child_name, logs):
        if not logs:
            return
        with self.lock:
            conn = self._connect()
            try:
                for item in logs:
                    ts = int(item.get("timestamp") or item.get("ts") or int(time.time() * 1000))
                    day = datetime.fromtimestamp(ts / 1000.0).strftime("%Y-%m-%d")
                    pkg = str(item.get("packageName") or item.get("appName") or "app")
                    app_name = str(item.get("appName") or pkg)
                    dur = int(item.get("durationSeconds") or item.get("durationSec") or 0)
                    conn.execute(
                        """INSERT OR REPLACE INTO phone_activity
                           (day, child_id, child_name, app_name, package_name, duration_sec, ts)
                           VALUES (?, ?, ?, ?, ?, ?, ?)""",
                        (day, int(child_id or 0), str(child_name or ""), app_name, pkg, dur, ts),
                    )
                conn.commit()
            finally:
                conn.close()

    def upsert_location_points(self, child_id, points):
        if not points:
            return
        with self.lock:
            conn = self._connect()
            try:
                for pt in points:
                    lat = float(pt.get("lat") or 0.0)
                    lng = float(pt.get("lng") or 0.0)
                    if lat == 0.0 and lng == 0.0:
                        continue
                    ts = int(pt.get("timestamp") or pt.get("ts") or int(time.time() * 1000))
                    addr = str(pt.get("address") or "")
                    speed = float(pt.get("speedKmh") or pt.get("speed") or 0.0)
                    conn.execute(
                        """INSERT OR IGNORE INTO location_points
                           (child_id, lat, lng, address, speed_kmh, ts)
                           VALUES (?, ?, ?, ?, ?, ?)""",
                        (int(child_id or 0), lat, lng, addr, speed, ts),
                    )
                conn.commit()
            finally:
                conn.close()

    def get_locations(self, child_id=0, limit=120):
        with self.lock:
            conn = self._connect()
            try:
                if child_id:
                    rows = conn.execute(
                        "SELECT * FROM location_points WHERE child_id = ? ORDER BY ts DESC LIMIT ?",
                        (int(child_id), limit),
                    ).fetchall()
                else:
                    rows = conn.execute(
                        "SELECT * FROM location_points ORDER BY ts DESC LIMIT ?",
                        (limit,),
                    ).fetchall()
                return [dict(r) for r in rows]
            finally:
                conn.close()

    def upsert_pc_daily_activity(self, day_str, users_list):
        if not day_str or not isinstance(users_list, list):
            return
        with self.lock:
            conn = self._connect()
            try:
                for uobj in users_list:
                    uname = str(uobj.get("user") or "Пользователь").strip()
                    # Merge duplicates per (day, user, kind, normalized label)
                    for kind_key, kind_name in (("procs", "proc"), ("titles", "title")):
                        merged = {}
                        for it in (uobj.get(kind_key) or []):
                            if isinstance(it, dict):
                                lbl = str(it.get("label") or it.get("name") or it.get("title") or "").strip()
                                sec = int(it.get("sec") or it.get("seconds") or 0)
                                st = int(it.get("started_at") or it.get("startedAtMs") or 0)
                            elif isinstance(it, (list, tuple)) and len(it) >= 2:
                                lbl = str(it[0]).strip()
                                sec = int(it[1] or 0)
                                st = int(it[2]) if len(it) >= 3 and it[2] else 0
                            else:
                                continue
                            if not lbl or sec <= 0:
                                continue
                            norm = re.sub(r"\s+", " ", lbl).strip().lower()
                            if norm in merged:
                                merged[norm]["sec"] += sec
                                if st and (merged[norm]["st"] == 0 or st < merged[norm]["st"]):
                                    merged[norm]["st"] = st
                            else:
                                merged[norm] = {"label": lbl, "sec": sec, "st": st}
                        for entry in merged.values():
                            conn.execute(
                                """INSERT INTO pc_activity_daily (day, user_name, kind, label, seconds, started_at)
                                   VALUES (?, ?, ?, ?, ?, ?)
                                   ON CONFLICT(day, user_name, kind, label) DO UPDATE SET
                                     seconds = MAX(pc_activity_daily.seconds, excluded.seconds),
                                     started_at = CASE
                                       WHEN pc_activity_daily.started_at = 0 THEN excluded.started_at
                                       WHEN excluded.started_at > 0 AND excluded.started_at < pc_activity_daily.started_at THEN excluded.started_at
                                       ELSE pc_activity_daily.started_at END""",
                                (day_str, uname, kind_name, entry["label"], entry["sec"], entry["st"]),
                            )
                conn.commit()
            finally:
                conn.close()

    def query_archive(self, kind="pc", day="", query="", limit=300):
        q = f"%{query.strip().lower()}%" if query.strip() else ""
        with self.lock:
            conn = self._connect()
            try:
                days_pc = [r[0] for r in conn.execute("SELECT DISTINCT day FROM pc_activity_daily ORDER BY day DESC LIMIT 60").fetchall()]
                days_ph = [r[0] for r in conn.execute("SELECT DISTINCT day FROM phone_activity ORDER BY day DESC LIMIT 60").fetchall()]
                all_days = sorted(set(days_pc + days_ph), reverse=True)

                if kind == "pc":
                    sql = "SELECT day, user_name, kind, label, seconds, started_at FROM pc_activity_daily WHERE 1=1"
                    params = []
                    if day:
                        sql += " AND day = ?"
                        params.append(day)
                    if q:
                        sql += " AND (LOWER(label) LIKE ? OR LOWER(user_name) LIKE ?)"
                        params.extend([q, q])
                    sql += " ORDER BY day DESC, seconds DESC LIMIT ?"
                    params.append(limit)
                    rows = [dict(r) for r in conn.execute(sql, params).fetchall()]
                    return {"days": all_days, "rows": rows}

                if kind == "phone":
                    sql = "SELECT day, child_id, child_name, app_name, package_name, duration_sec, ts FROM phone_activity WHERE 1=1"
                    params = []
                    if day:
                        sql += " AND day = ?"
                        params.append(day)
                    if q:
                        sql += " AND (LOWER(app_name) LIKE ? OR LOWER(package_name) LIKE ?)"
                        params.extend([q, q])
                    sql += " ORDER BY ts DESC LIMIT ?"
                    params.append(limit)
                    rows = [dict(r) for r in conn.execute(sql, params).fetchall()]
                    return {"days": all_days, "rows": rows}

                # alerts
                sql = "SELECT id, child_id, alert_type, title, message, ts, is_read FROM alerts WHERE 1=1"
                params = []
                if q:
                    sql += " AND (LOWER(title) LIKE ? OR LOWER(message) LIKE ? OR LOWER(alert_type) LIKE ?)"
                    params.extend([q, q, q])
                sql += " ORDER BY ts DESC LIMIT ?"
                params.append(limit)
                rows = [dict(r) for r in conn.execute(sql, params).fetchall()]
                return {"days": all_days, "rows": rows}
            finally:
                conn.close()


# =============================================================================
# 3. NATIVE WINDOWS SYSTEM TRAY & BALLOON NOTIFICATIONS (PURE WIN32 CTYPES)
# =============================================================================

class WinTrayManager:
    """Системный трей Windows и всплывающие уведомления (без сторонних зависимостей)."""

    def __init__(self, on_open=None, on_lock_phone=None, on_toggle_pc_lock=None, on_exit=None):
        self.on_open = on_open
        self.on_lock_phone = on_lock_phone
        self.on_toggle_pc_lock = on_toggle_pc_lock
        self.on_exit = on_exit
        self._hwnd = None
        self._nid = None
        self._ready = threading.Event()
        self._stop = False

    def start(self):
        if not IS_WINDOWS:
            return
        threading.Thread(target=self._run_loop, daemon=True, name="WinTrayThread").start()

    def stop(self):
        self._stop = True
        if IS_WINDOWS and self._hwnd:
            try:
                ctypes.windll.user32.PostMessageW(self._hwnd, 0x0010, 0, 0)  # WM_CLOSE
            except Exception:
                pass

    def notify(self, title, message, is_urgent=False, play_sound=True):
        if not IS_WINDOWS:
            return
        if play_sound:
            try:
                import winsound
                winsound.MessageBeep(winsound.MB_ICONEXCLAMATION if is_urgent else winsound.MB_ICONASTERISK)
            except Exception:
                pass
        if not self._hwnd or not self._nid:
            return
        try:
            shell32 = ctypes.windll.shell32
            nid = self._nid
            nid.uFlags = 0x00000010  # NIF_INFO
            nid.szInfoTitle = str(title or APP_NAME)[:63]
            nid.szInfo = str(message or "")[:255]
            nid.dwInfoFlags = 0x00000002 if is_urgent else 0x00000001  # NIIF_WARNING / NIIF_INFO
            shell32.Shell_NotifyIconW(1, ctypes.byref(nid))  # NIM_MODIFY
        except Exception:
            pass

    def _run_loop(self):
        try:
            user32 = ctypes.windll.user32
            kernel32 = ctypes.windll.kernel32
            shell32 = ctypes.windll.shell32

            WM_USER = 0x0400
            WM_TRAY = WM_USER + 20
            WM_COMMAND = 0x0111
            WM_DESTROY = 0x0002
            WM_LBUTTONDBLCLK = 0x0203
            WM_LBUTTONUP = 0x0202
            WM_RBUTTONUP = 0x0205
            NIN_BALLOONUSERCLICK = WM_USER + 5

            ID_OPEN = 1001
            ID_LOCK_PHONE = 1002
            ID_TOGGLE_PC = 1003
            ID_EXIT = 1004

            class GUID(ctypes.Structure):
                _fields_ = [
                    ("Data1", wintypes.DWORD),
                    ("Data2", wintypes.WORD),
                    ("Data3", wintypes.WORD),
                    ("Data4", ctypes.c_ubyte * 8),
                ]

            class NOTIFYICONDATAW(ctypes.Structure):
                _fields_ = [
                    ("cbSize", wintypes.DWORD),
                    ("hWnd", wintypes.HWND),
                    ("uID", wintypes.UINT),
                    ("uFlags", wintypes.UINT),
                    ("uCallbackMessage", wintypes.UINT),
                    ("hIcon", wintypes.HICON),
                    ("szTip", wintypes.WCHAR * 128),
                    ("dwState", wintypes.DWORD),
                    ("dwStateMask", wintypes.DWORD),
                    ("szInfo", wintypes.WCHAR * 256),
                    ("uTimeoutOrVersion", wintypes.UINT),
                    ("szInfoTitle", wintypes.WCHAR * 64),
                    ("dwInfoFlags", wintypes.DWORD),
                    ("guidItem", GUID),
                    ("hBalloonIcon", wintypes.HICON),
                ]

            WNDPROC = ctypes.WINFUNCTYPE(ctypes.c_longlong, wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM)

            class WNDCLASSW(ctypes.Structure):
                _fields_ = [
                    ("style", wintypes.UINT),
                    ("lpfnWndProc", WNDPROC),
                    ("cbClsExtra", ctypes.c_int),
                    ("cbWndExtra", ctypes.c_int),
                    ("hInstance", wintypes.HINSTANCE),
                    ("hIcon", wintypes.HICON),
                    ("hCursor", wintypes.HANDLE),
                    ("hbrBackground", wintypes.HBRUSH),
                    ("lpszMenuName", wintypes.LPCWSTR),
                    ("lpszClassName", wintypes.LPCWSTR),
                ]

            user32.DefWindowProcW.argtypes = [wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM]
            user32.DefWindowProcW.restype = ctypes.c_longlong

            def show_menu(hwnd):
                menu = user32.CreatePopupMenu()
                MF_STRING = 0x0000
                MF_SEPARATOR = 0x0800
                user32.AppendMenuW(menu, MF_STRING, ID_OPEN, "Открыть KidsGuard Родитель")
                user32.AppendMenuW(menu, MF_SEPARATOR, 0, None)
                user32.AppendMenuW(menu, MF_STRING, ID_LOCK_PHONE, "Заблокировать телефон ребёнка")
                user32.AppendMenuW(menu, MF_STRING, ID_TOGGLE_PC, "Переключить блокировку ПК")
                user32.AppendMenuW(menu, MF_SEPARATOR, 0, None)
                user32.AppendMenuW(menu, MF_STRING, ID_EXIT, "Выход")
                pt = wintypes.POINT()
                user32.GetCursorPos(ctypes.byref(pt))
                user32.SetForegroundWindow(hwnd)
                user32.TrackPopupMenu(menu, 0x0100, pt.x, pt.y, 0, hwnd, None)
                user32.DestroyMenu(menu)

            def wnd_proc(hwnd, msg, wparam, lparam):
                if msg == WM_TRAY:
                    evt = lparam & 0xFFFF
                    if evt in (WM_LBUTTONDBLCLK, WM_LBUTTONUP, NIN_BALLOONUSERCLICK):
                        if self.on_open:
                            threading.Thread(target=self.on_open, daemon=True).start()
                    elif evt == WM_RBUTTONUP:
                        show_menu(hwnd)
                    return 0
                if msg == WM_COMMAND:
                    cmd_id = wparam & 0xFFFF
                    if cmd_id == ID_OPEN and self.on_open:
                        threading.Thread(target=self.on_open, daemon=True).start()
                    elif cmd_id == ID_LOCK_PHONE and self.on_lock_phone:
                        threading.Thread(target=self.on_lock_phone, daemon=True).start()
                    elif cmd_id == ID_TOGGLE_PC and self.on_toggle_pc_lock:
                        threading.Thread(target=self.on_toggle_pc_lock, daemon=True).start()
                    elif cmd_id == ID_EXIT and self.on_exit:
                        threading.Thread(target=self.on_exit, daemon=True).start()
                    return 0
                if msg == WM_DESTROY:
                    if self._nid:
                        shell32.Shell_NotifyIconW(2, ctypes.byref(self._nid))  # NIM_DELETE
                    user32.PostQuitMessage(0)
                    return 0
                return user32.DefWindowProcW(hwnd, msg, wparam, lparam)

            self._wnd_proc_ref = WNDPROC(wnd_proc)
            hinst = kernel32.GetModuleHandleW(None)
            cls_name = f"KidsGuardParentTray_{os.getpid()}"
            wc = WNDCLASSW()
            wc.lpfnWndProc = self._wnd_proc_ref
            wc.hInstance = hinst
            wc.lpszClassName = cls_name
            user32.RegisterClassW(ctypes.byref(wc))

            hwnd = user32.CreateWindowExW(0, cls_name, APP_NAME, 0, 0, 0, 0, 0, 0, 0, hinst, None)
            self._hwnd = hwnd

            hicon = user32.LoadIconW(None, ctypes.c_void_p(32518))  # IDI_SHIELD
            if not hicon:
                hicon = user32.LoadIconW(None, ctypes.c_void_p(32512))  # IDI_APPLICATION

            nid = NOTIFYICONDATAW()
            nid.cbSize = ctypes.sizeof(NOTIFYICONDATAW)
            nid.hWnd = hwnd
            nid.uID = 1
            nid.uFlags = 0x00000001 | 0x00000002 | 0x00000004  # NIF_MESSAGE | NIF_ICON | NIF_TIP
            nid.uCallbackMessage = WM_TRAY
            nid.hIcon = hicon
            nid.szTip = APP_NAME
            self._nid = nid
            shell32.Shell_NotifyIconW(0, ctypes.byref(nid))  # NIM_ADD
            self._ready.set()

            msg_struct = wintypes.MSG()
            while not self._stop and user32.GetMessageW(ctypes.byref(msg_struct), None, 0, 0) > 0:
                user32.TranslateMessage(ctypes.byref(msg_struct))
                user32.DispatchMessageW(ctypes.byref(msg_struct))
        except Exception:
            self._ready.set()


# =============================================================================
# 4. LOW-LEVEL MQTT 3.1.1 & RFC-6455 WEBSOCKET HELPERS (ZERO PIP DEPS)
# =============================================================================

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
    b = str(s).encode("utf-8")
    return bytes([(len(b) >> 8) & 0xFF, len(b) & 0xFF]) + b


def _mqtt_connect_pkt(cid, username="", password=""):
    flags = 0x02  # Clean Session
    payload = _mqtt_utf8(cid)
    if username:
        flags |= 0x80
        payload += _mqtt_utf8(username)
        if password:
            flags |= 0x40
            payload += _mqtt_utf8(password)
    var = _mqtt_utf8("MQTT") + bytes([0x04, flags, 0x00, 0x3C]) + payload
    return bytes([0x10]) + _mqtt_rem_len(len(var)) + var


def _mqtt_sub_pkt(pid, topic):
    var = bytes([(pid >> 8) & 0xFF, pid & 0xFF]) + _mqtt_utf8(topic) + bytes([0x00])
    return bytes([0x82]) + _mqtt_rem_len(len(var)) + var


def _mqtt_pub_pkt(topic, payload_str, retain=False):
    var = _mqtt_utf8(topic) + payload_str.encode("utf-8")
    hdr = 0x31 if retain else 0x30
    return bytes([hdr]) + _mqtt_rem_len(len(var)) + var


def _sock_read_exact(sock, n):
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise EOFError("socket closed")
        buf.extend(chunk)
    return bytes(buf)


def _mqtt_read_rem_len(sock):
    mult, val = 1, 0
    while True:
        b = _sock_read_exact(sock, 1)[0]
        val += (b & 127) * mult
        mult *= 128
        if (b & 128) == 0:
            return val


def _ws_handshake(sock, host, path):
    key = base64.b64encode(os.urandom(16)).decode("ascii")
    req = (
        f"GET {path} HTTP/1.1\r\n"
        f"Host: {host}\r\n"
        f"Upgrade: websocket\r\n"
        f"Connection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {key}\r\n"
        f"Sec-WebSocket-Protocol: mqtt\r\n"
        f"Sec-WebSocket-Version: 13\r\n\r\n"
    )
    sock.sendall(req.encode("ascii"))
    resp = b""
    while b"\r\n\r\n" not in resp:
        ch = sock.recv(1024)
        if not ch:
            raise EOFError("ws handshake closed")
        resp += ch
        if len(resp) > 16384:
            break
    if b" 101 " not in resp.split(b"\r\n", 1)[0]:
        raise RuntimeError("ws handshake rejected")


def _ws_send(sock, data, opcode=0x02):
    mask = os.urandom(4)
    length = len(data)
    hdr = bytearray([0x80 | opcode])
    if length < 126:
        hdr.append(0x80 | length)
    elif length < 65536:
        hdr.append(0x80 | 126)
        hdr.extend(struct.pack("!H", length))
    else:
        hdr.append(0x80 | 127)
        hdr.extend(struct.pack("!Q", length))
    hdr.extend(mask)
    masked = bytes(b ^ mask[i & 3] for i, b in enumerate(data))
    sock.sendall(bytes(hdr) + masked)


def _ws_recv_frame(sock):
    b1, b2 = _sock_read_exact(sock, 2)
    opcode = b1 & 0x0F
    masked = bool(b2 & 0x80)
    length = b2 & 0x7F
    if length == 126:
        length = struct.unpack("!H", _sock_read_exact(sock, 2))[0]
    elif length == 127:
        length = struct.unpack("!Q", _sock_read_exact(sock, 8))[0]
    mask = _sock_read_exact(sock, 4) if masked else b""
    payload = _sock_read_exact(sock, length) if length else b""
    if masked:
        payload = bytes(b ^ mask[i & 3] for i, b in enumerate(payload))
    return opcode, payload


def _parse_mqtt_buffer(buf):
    """Извлекает все завершённые MQTT-пакеты из буфера байтов."""
    packets = []
    idx = 0
    n = len(buf)
    while idx + 2 <= n:
        hdr = buf[idx]
        pos = idx + 1
        mult, rem = 1, 0
        complete_len = False
        while pos < n:
            b = buf[pos]
            pos += 1
            rem += (b & 127) * mult
            mult *= 128
            if (b & 128) == 0:
                complete_len = True
                break
        if not complete_len or pos + rem > n:
            break
        body = bytes(buf[pos:pos + rem])
        packets.append((hdr, body))
        idx = pos + rem
    return packets, buf[idx:]


# =============================================================================
# 5. MULTI-TRANSPORT PARENT SYNC ENGINE (MQTT + VPS + LAN PC HTTP/MJPEG)
# =============================================================================

def _merge_activity_items(raw_items):
    """Объединяет повторяющиеся заголовки окон/программ за день с суммированием времени."""
    merged = {}
    for it in (raw_items or []):
        if isinstance(it, dict):
            lbl = str(it.get("label") or it.get("name") or it.get("title") or "").strip()
            sec = int(it.get("sec") or it.get("seconds") or 0)
            st = int(it.get("started_at") or it.get("startedAtMs") or 0)
        elif isinstance(it, (list, tuple)) and len(it) >= 2:
            lbl = str(it[0]).strip()
            sec = int(it[1] or 0)
            st = int(it[2]) if len(it) >= 3 and it[2] else 0
        else:
            continue
        if not lbl or sec <= 0:
            continue
        norm = re.sub(r"\s+", " ", lbl).strip().lower()
        if norm in merged:
            merged[norm]["sec"] += sec
            if st and (merged[norm]["started_at"] == 0 or st < merged[norm]["started_at"]):
                merged[norm]["started_at"] = st
        else:
            merged[norm] = {"label": lbl, "sec": sec, "started_at": st}
    return sorted(merged.values(), key=lambda x: x["sec"], reverse=True)


class ParentSyncEngine:
    def __init__(self, archive: LocalHistoryArchive, tray: WinTrayManager):
        self.archive = archive
        self.tray = tray
        self.cfg = load_config()
        self.cfg_lock = threading.Lock()
        self.state_lock = threading.Lock()
        self.started_at_ms = int(time.time() * 1000)
        self._stop = False

        # Active outgoing sockets
        self.mqtt_sockets = {}  # broker_key -> {"sock": sock, "lock": Lock}
        self.vps_socket = {"sock": None, "lock": threading.Lock(), "connected": False, "host": ""}
        self.vps_restart_event = threading.Event()

        # LAN PC HTTP session
        self.pc_lan_cookie = ""
        self.pc_lan_csrf = ""
        self.pc_lan_online = False
        self.pc_lan_last_ok = 0.0

        # Live runtime state
        self.blob_cache = {}
        self.brokers_connected = set()
        self.packets_received = 0
        self.last_packet_ts = 0

        self.children_by_id = {}
        self.locks_by_id = {}
        self.screens_by_id = {}
        self.screens_by_code = {}
        self.offline_by_id = {}
        self.apps_by_code = {}
        self.rules = []

        self.pc_state = None
        self.pc_state_ts = 0
        self.pc_screen_b64 = ""
        self.pc_screen_fmt = "image/jpeg"
        self.pc_screen_ts = 0
        self.pc_stream_active = False
        self.pc_stream_fps = 0.0
        self.pc_stream_frames = 0
        self.pc_stream_frame_times = []
        self.pc_chat_tray_override = None
        self.pc_chat_tray_override_until = 0.0
        self.pc_chat_messages = []

    def get_config(self):
        with self.cfg_lock:
            return dict(self.cfg)

    def update_config(self, patch):
        vps_changed = False
        with self.cfg_lock:
            for k in ("vps_enabled", "vps_host", "vps_port", "vps_password", "pc_pairing_code"):
                if k in patch and patch[k] != self.cfg.get(k):
                    vps_changed = True
            for k, v in patch.items():
                if k in DEFAULT_CONFIG:
                    self.cfg[k] = v
            save_config(self.cfg)
            cfg_copy = dict(self.cfg)
        if vps_changed:
            self.vps_restart_event.set()
            self._close_vps_socket()
        return cfg_copy

    def start(self):
        for host, port in PUBLIC_BROKERS:
            threading.Thread(target=self._mqtt_worker, args=(host, port), daemon=True, name=f"MQTT-{host}").start()
        threading.Thread(target=self._vps_worker, daemon=True, name="VPS-Relay").start()
        threading.Thread(target=self._pc_lan_poller, daemon=True, name="PC-LAN-Poll").start()

    def stop(self):
        self._stop = True
        self._close_vps_socket()
        for entry in list(self.mqtt_sockets.values()):
            try:
                entry["sock"].close()
            except Exception:
                pass

    def _close_vps_socket(self):
        with self.vps_socket["lock"]:
            s = self.vps_socket.get("sock")
            self.vps_socket["sock"] = None
            self.vps_socket["connected"] = False
            if s:
                try:
                    s.close()
                except Exception:
                    pass

    def publish_all(self, topic, payload_obj, retain=False):
        payload_str = payload_obj if isinstance(payload_obj, str) else json.dumps(payload_obj, ensure_ascii=False)
        pkt = _mqtt_pub_pkt(topic, payload_str, retain=retain)
        sent = 0
        # 1. VPS Relay
        with self.vps_socket["lock"]:
            vs = self.vps_socket.get("sock")
            if vs and self.vps_socket.get("connected"):
                try:
                    _ws_send(vs, pkt)
                    sent += 1
                except Exception:
                    pass
        # 2. All public MQTT brokers
        for key, entry in list(self.mqtt_sockets.items()):
            with entry["lock"]:
                try:
                    entry["sock"].sendall(pkt)
                    sent += 1
                except Exception:
                    pass
        return sent > 0

    def _download_blob(self, key):
        if not key:
            return ""
        if key in self.blob_cache:
            return self.blob_cache[key]
        try:
            req = urllib.request.Request(
                f"https://bytebin.lucko.me/{key}",
                headers={"User-Agent": "KidsGuardParent/1.0"},
            )
            with urllib.request.urlopen(req, timeout=6) as resp:
                data = resp.read().decode("utf-8", errors="ignore")
                if data:
                    self.blob_cache[key] = data
                    return data
        except Exception:
            pass
        return ""

    def _notify_user(self, title, body, is_urgent=False):
        cfg = self.get_config()
        if not cfg.get("notifications_enabled", True):
            return
        self.tray.notify(title, body, is_urgent=is_urgent, play_sound=bool(cfg.get("sound_enabled", True)))

    def _handle_packet(self, topic, raw_bytes):
        now_ms = int(time.time() * 1000)

        # Binary PC live video frame: .../pc_stream_bin/<code/pc_id>
        if "/pc_stream_bin/" in topic and len(raw_bytes) > 16:
            try:
                cfg = self.get_config()
                pc_code = str(cfg.get("pc_pairing_code") or "101-701").strip()
                if pc_code and f"/{pc_code}" not in topic and f"/{pc_code.replace('-', '')}" not in topic:
                    return
                jpeg_bytes = raw_bytes[12:]
                if jpeg_bytes[:2] == b"\xff\xd8":
                    b64 = base64.b64encode(jpeg_bytes).decode("ascii")
                    with self.state_lock:
                        self.pc_screen_b64 = b64
                        self.pc_screen_fmt = "image/jpeg"
                        self.pc_screen_ts = now_ms
                        self.pc_stream_frames += 1
                        self.pc_stream_frame_times.append(time.time())
                        cutoff = time.time() - 5.0
                        self.pc_stream_frame_times = [t for t in self.pc_stream_frame_times if t >= cutoff]
                        self.pc_stream_fps = round(len(self.pc_stream_frame_times) / 5.0, 1)
                return
            except Exception:
                return

        try:
            raw_text = raw_bytes.decode("utf-8", errors="ignore").strip()
        except Exception:
            return
        if not raw_text.startswith("{"):
            return
        try:
            msg = json.loads(raw_text)
        except Exception:
            return

        mtype = str(msg.get("type") or "")
        code = str(msg.get("pairingCode") or msg.get("pairing_code") or "").strip()
        cfg = self.get_config()
        want_pc_code = str(cfg.get("pc_pairing_code") or "101-701").strip()

        with self.state_lock:
            self.packets_received += 1
            self.last_packet_ts = now_ms

        # --- PC_STATE ---
        if mtype == "PC_STATE" or "/pc_state/" in topic:
            if want_pc_code and code and code.replace("-", "") != want_pc_code.replace("-", ""):
                return
            self._ingest_pc_state(msg, now_ms)
            return

        # --- PC_SCREEN ---
        if mtype == "PC_SCREEN" or "/pc_screen/" in topic:
            if want_pc_code and code and code.replace("-", "") != want_pc_code.replace("-", ""):
                return
            b64 = str(msg.get("screen_b64") or msg.get("b64") or "")
            if b64:
                with self.state_lock:
                    self.pc_screen_b64 = b64
                    self.pc_screen_fmt = str(msg.get("fmt") or "image/jpeg")
                    self.pc_screen_ts = now_ms
            return

        # --- PC_STREAM_FRAME ---
        if mtype == "PC_STREAM_FRAME" or "/pc_stream/" in topic:
            if want_pc_code and code and code.replace("-", "") != want_pc_code.replace("-", ""):
                return
            b64 = str(msg.get("b64") or msg.get("screen_b64") or "")
            if b64:
                with self.state_lock:
                    self.pc_screen_b64 = b64
                    self.pc_screen_fmt = str(msg.get("fmt") or "image/jpeg")
                    self.pc_screen_ts = now_ms
                    self.pc_stream_frames += 1
                    self.pc_stream_frame_times.append(time.time())
                    cutoff = time.time() - 5.0
                    self.pc_stream_frame_times = [t for t in self.pc_stream_frame_times if t >= cutoff]
                    self.pc_stream_fps = round(len(self.pc_stream_frame_times) / 5.0, 1)
            return

        # --- PC_CHAT ---
        if mtype == "PC_CHAT" or "/pc_chat/" in topic:
            if want_pc_code and code and code.replace("-", "") != want_pc_code.replace("-", ""):
                return
            cm = msg.get("message") if isinstance(msg.get("message"), dict) else msg
            txt = str(cm.get("text") or "").strip()
            sender = str(cm.get("sender") or "PC").upper()
            win_user = str(cm.get("user") or "").strip()
            mid = str(cm.get("id") or f"pc_{cm.get('ts') or now_ms}")
            ts = int(cm.get("ts") or now_ms)
            if txt:
                role = "PARENT" if sender == "PARENT" else "CHILD"
                sname = "Родитель → ПК" if role == "PARENT" else (f"ПК · {win_user}" if win_user else "ПК ребёнка")
                is_new = self.archive.add_chat(mid, "PC", role, sname, txt, ts, False)
                with self.state_lock:
                    if not any(m.get("id") == mid for m in self.pc_chat_messages):
                        self.pc_chat_messages.append({
                            "id": mid,
                            "sender": "PARENT" if role == "PARENT" else "PC",
                            "user": win_user,
                            "text": txt,
                            "ts": ts,
                        })
                        self.pc_chat_messages = sorted(self.pc_chat_messages, key=lambda x: x["ts"])[-60:]
                if is_new and role == "CHILD" and ts >= self.started_at_ms - 10_000:
                    self._notify_user(f"Сообщение с ПК ({win_user or 'Ребёнок'})", txt, is_urgent=False)
            return

        # --- LOCK_STATE ---
        if mtype == "LOCK_STATE" or topic.startswith(f"{TOPIC_V34}/lock/"):
            cid = int(msg.get("childId") or msg.get("id") or 0)
            if cid > 0:
                locked = bool(msg.get("isDeviceLocked", False))
                ts = int(msg.get("timestamp") or msg.get("ts") or now_ms)
                if locked and abs(now_ms - ts) > 25_000:
                    return
                with self.state_lock:
                    prev = self.locks_by_id.get(cid)
                    if not prev or ts >= prev["ts"]:
                        self.locks_by_id[cid] = {"locked": locked, "ts": ts, "pairingCode": code}
                    if cid in self.children_by_id:
                        self.children_by_id[cid]["isDeviceLocked"] = locked
            return

        # --- CHILD_DELETE ---
        if mtype == "CHILD_DELETE":
            cid = int(msg.get("id") or msg.get("childId") or 0)
            with self.state_lock:
                self.children_by_id.pop(cid, None)
            return

        # --- CHILD_STATE / CHILD_PATCH ---
        if mtype in ("CHILD_STATE", "CHILD_PATCH") or topic.startswith(f"{TOPIC_V34}/state/"):
            cid = int(msg.get("id") or msg.get("childId") or 0)
            if cid <= 0:
                return
            name = str(msg.get("name") or "").strip()
            if name.lower() == "test":
                return
            with self.state_lock:
                prev = self.children_by_id.get(cid, {})
                merged = dict(prev)
                merged.update(msg)
                merged["id"] = cid
                merged["_rx_ts"] = now_ms
                if cid in self.locks_by_id and self.locks_by_id[cid]["ts"] >= int(msg.get("ts") or 0):
                    merged["isDeviceLocked"] = self.locks_by_id[cid]["locked"]
                self.children_by_id[cid] = merged
            # Persist current GPS fix if valid
            lat = float(msg.get("lastLocationLat") or 0.0)
            lng = float(msg.get("lastLocationLng") or 0.0)
            if lat != 0.0 or lng != 0.0:
                self.archive.upsert_location_points(cid, [{
                    "lat": lat,
                    "lng": lng,
                    "address": msg.get("lastLocationAddress") or "",
                    "speedKmh": 0.0,
                    "timestamp": int(msg.get("ts") or now_ms),
                }])
            return

        # --- SCREEN_FRAME ---
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
                b64 = self._download_blob(blob_key)
            frame = {"childId": cid, "pairingCode": code, "activeAppName": app_name, "base64Bitmap": b64, "ts": ts, "rx_ts": now_ms}
            with self.state_lock:
                if cid > 0:
                    prev = self.screens_by_id.get(cid)
                    if b64 or not prev:
                        if not b64 and prev:
                            frame["base64Bitmap"] = prev.get("base64Bitmap", "")
                        self.screens_by_id[cid] = frame
                if code:
                    prev_c = self.screens_by_code.get(code)
                    if b64 or not prev_c or ts >= prev_c.get("ts", 0):
                        if not b64 and prev_c:
                            frame["base64Bitmap"] = prev_c.get("base64Bitmap", "")
                        self.screens_by_code[code] = frame
            return

        # --- OFFLINE_HISTORY_BUNDLE ---
        if mtype == "OFFLINE_HISTORY_BUNDLE":
            cid = int(msg.get("childId") or 0)
            if cid > 0:
                with self.state_lock:
                    self.offline_by_id[cid] = msg
                    cname = str((self.children_by_id.get(cid) or {}).get("name") or "Ребёнок")
                self.archive.upsert_phone_activity(cid, cname, msg.get("activityLogs") or [])
                self.archive.upsert_location_points(cid, msg.get("locationPoints") or [])
            return

        # --- APPS_LIST ---
        if mtype == "APPS_LIST":
            apps = msg.get("apps")
            blob_key = str(msg.get("blobKey") or "")
            if not apps and blob_key:
                raw_apps = self._download_blob(blob_key)
                try:
                    apps = json.loads(raw_apps)
                except Exception:
                    apps = []
            if isinstance(apps, list) and apps:
                key = code or str(msg.get("childId") or "default")
                with self.state_lock:
                    self.apps_by_code[key] = apps
            return

        # --- RULES_LIST ---
        if mtype == "RULES_LIST":
            rules = msg.get("rules")
            if isinstance(rules, list):
                with self.state_lock:
                    self.rules = rules
            return

        # --- CHAT ---
        if mtype == "CHAT":
            txt = str(msg.get("messageText") or msg.get("text") or "").strip()
            role = str(msg.get("senderRole") or "PARENT").upper()
            sname = str(msg.get("senderName") or ("Родитель" if role == "PARENT" else "Ребёнок"))
            ts = int(msg.get("timestamp") or msg.get("ts") or now_ms)
            mid = str(msg.get("id") or f"chat_{ts}")
            is_att = bool(msg.get("isAttentionSignal") or msg.get("isAttentionGrabber", False))
            src = "PC" if ("ПК" in sname or "💻" in sname) else "PHONE"
            is_new = self.archive.add_chat(mid, src, role, sname, txt, ts, is_att)
            if is_new and role == "CHILD" and ts >= self.started_at_ms - 10_000:
                self._notify_user(f"Новое сообщение: {sname}", txt, is_urgent=is_att)
            return

        # --- ALERT / SOS ---
        if mtype == "ALERT":
            aid = str(msg.get("id") or f"alert_{msg.get('timestamp') or now_ms}")
            cid = int(msg.get("childId") or 0)
            atype = str(msg.get("alertType") or "ALERT")
            title = str(msg.get("title") or "Уведомление безопасности")
            body = str(msg.get("message") or "")
            ts = int(msg.get("timestamp") or msg.get("ts") or now_ms)
            is_new = self.archive.add_alert(aid, cid, atype, title, body, ts, False)
            if is_new and ts >= self.started_at_ms - 15_000:
                self._notify_user(title, body, is_urgent=(atype.upper() == "SOS"))
            return

    def _ingest_pc_state(self, ps, now_ms):
        # Extract & merge daily PC activity by user and persist to SQLite
        act_date = str(ps.get("activity_date") or datetime.now().strftime("%Y-%m-%d"))
        act_users_raw = ps.get("activity_by_user") or ps.get("activity") or []
        act_users_clean = []
        if isinstance(act_users_raw, list):
            for u in act_users_raw:
                if not isinstance(u, dict):
                    continue
                act_users_clean.append({
                    "user": str(u.get("user") or "Пользователь"),
                    "procs": _merge_activity_items(u.get("procs")),
                    "titles": _merge_activity_items(u.get("titles")),
                })
        elif isinstance(act_users_raw, dict):
            for uname, uval in act_users_raw.items():
                if isinstance(uval, dict):
                    act_users_clean.append({
                        "user": str(uname),
                        "procs": _merge_activity_items(uval.get("procs")),
                        "titles": _merge_activity_items(uval.get("titles")),
                    })

        if act_users_clean:
            self.archive.upsert_pc_daily_activity(act_date, act_users_clean)

        # Also persist historical days if present (full=True packet)
        act_by_date = ps.get("activity_by_date") or {}
        if isinstance(act_by_date, dict):
            for d_str, d_users in act_by_date.items():
                if isinstance(d_users, list):
                    self.archive.upsert_pc_daily_activity(str(d_str), d_users)

        # Sync PC chat history included in PC_STATE
        raw_chat = ps.get("chat_messages") or []
        if isinstance(raw_chat, list):
            for cm in raw_chat:
                if not isinstance(cm, dict):
                    continue
                txt = str(cm.get("text") or "").strip()
                if not txt:
                    continue
                sender = str(cm.get("sender") or "PC").upper()
                win_user = str(cm.get("user") or "").strip()
                ts = int(cm.get("ts") or now_ms)
                mid = str(cm.get("id") or f"pc_{ts}_{hashlib.md5(txt.encode('utf-8')).hexdigest()[:6]}")
                role = "PARENT" if sender == "PARENT" else "CHILD"
                sname = "Родитель → ПК" if role == "PARENT" else (f"ПК · {win_user}" if win_user else "ПК ребёнка")
                is_new = self.archive.add_chat(mid, "PC", role, sname, txt, ts, False)
                if is_new and role == "CHILD" and ts >= self.started_at_ms - 10_000:
                    self._notify_user(f"Сообщение с ПК ({win_user or 'Ребёнок'})", txt, is_urgent=False)

        with self.state_lock:
            ps_copy = dict(ps)
            ps_copy["_merged_activity"] = act_users_clean
            if self.pc_chat_tray_override is not None and time.time() < self.pc_chat_tray_override_until:
                ps_copy["chat_tray_enabled"] = self.pc_chat_tray_override
            self.pc_state = ps_copy
            self.pc_state_ts = now_ms
            if isinstance(raw_chat, list) and raw_chat:
                seen = {m["id"]: m for m in self.pc_chat_messages}
                for cm in raw_chat:
                    if isinstance(cm, dict) and cm.get("text"):
                        mid = str(cm.get("id") or f"pc_{cm.get('ts') or now_ms}")
                        seen[mid] = {
                            "id": mid,
                            "sender": str(cm.get("sender") or "PC").upper(),
                            "user": str(cm.get("user") or ""),
                            "text": str(cm.get("text") or ""),
                            "ts": int(cm.get("ts") or now_ms),
                        }
                self.pc_chat_messages = sorted(seen.values(), key=lambda x: x["ts"])[-60:]

            # Remember PC local IP automatically if not set
            lip = str(ps.get("local_ip") or "").strip()
            lport = int(ps.get("port") or 8765)
            if lip and not self.cfg.get("pc_lan_ip"):
                with self.cfg_lock:
                    self.cfg["pc_lan_ip"] = lip
                    self.cfg["pc_lan_port"] = lport
                    save_config(self.cfg)

    def _mqtt_worker(self, host, port):
        key = f"{host}:{port}"
        while not self._stop:
            sock = None
            try:
                sock = socket.create_connection((host, port), timeout=8)
                sock.settimeout(4.0)
                cid = f"kg_win_parent_{secrets.token_hex(4)}"
                sock.sendall(_mqtt_connect_pkt(cid))
                hdr = _sock_read_exact(sock, 1)[0]
                rl = _mqtt_read_rem_len(sock)
                if rl > 0:
                    _sock_read_exact(sock, rl)
                if (hdr & 0xF0) != 0x20:
                    raise RuntimeError("CONNACK rejected")

                entry = {"sock": sock, "lock": threading.Lock()}
                self.mqtt_sockets[key] = entry
                with self.state_lock:
                    self.brokers_connected.add(key)

                sock.sendall(_mqtt_sub_pkt(1, f"{TOPIC_V34}/#"))
                sock.sendall(_mqtt_sub_pkt(2, f"{TOPIC_V33}/#"))

                # Initial sync request to PC
                cfg = self.get_config()
                pc_code = str(cfg.get("pc_pairing_code") or "101-701").strip() or "101-701"
                self.send_pc_cmd("/api/sync", {})

                last_ping = time.time()
                while not self._stop:
                    if time.time() - last_ping >= 25.0:
                        last_ping = time.time()
                        with entry["lock"]:
                            sock.sendall(bytes([0xC0, 0x00]))
                    try:
                        h = _sock_read_exact(sock, 1)[0]
                        rem = _mqtt_read_rem_len(sock)
                        body = _sock_read_exact(sock, rem) if rem > 0 else b""
                        if (h & 0xF0) == 0x30 and len(body) >= 2:
                            tlen = (body[0] << 8) | body[1]
                            topic = body[2:2 + tlen].decode("utf-8", "ignore")
                            pos = 2 + tlen
                            if ((h >> 1) & 0x03) > 0:
                                pos += 2
                            self._handle_packet(topic, body[pos:])
                    except socket.timeout:
                        continue
            except Exception:
                time.sleep(3)
            finally:
                self.mqtt_sockets.pop(key, None)
                with self.state_lock:
                    self.brokers_connected.discard(key)
                if sock:
                    try:
                        sock.close()
                    except Exception:
                        pass

    def _vps_worker(self):
        while not self._stop:
            self.vps_restart_event.clear()
            cfg = self.get_config()
            if not cfg.get("vps_enabled") or not str(cfg.get("vps_host") or "").strip():
                self.vps_restart_event.wait(3.0)
                continue

            raw_host = str(cfg.get("vps_host") or "").strip()
            port = int(cfg.get("vps_port") or 443)
            pwd = str(cfg.get("vps_password") or "1234")
            code = str(cfg.get("pc_pairing_code") or "101-701").strip() or "101-701"
            tls = port in (443, 8443, 2053, 2083, 2087, 2096) or raw_host.lower().startswith(("wss://", "https://"))
            stripped = raw_host
            for pfx in ("wss://", "WSS://", "ws://", "WS://", "https://", "HTTPS://", "http://", "HTTP://"):
                stripped = stripped.replace(pfx, "")
            stripped = stripped.strip("/")
            host = stripped.split("/")[0]
            ws_path = "/" + stripped.split("/", 1)[1] if "/" in stripped else "/mqtt"
            if ":" in host:
                h_only, p_str = host.rsplit(":", 1)
                if p_str.isdigit():
                    host, port = h_only, int(p_str)

            sock = None
            try:
                raw = socket.create_connection((host, port), timeout=10)
                if tls:
                    ctx = ssl.create_default_context()
                    ctx.check_hostname = False
                    ctx.verify_mode = ssl.CERT_NONE
                    sock = ctx.wrap_socket(raw, server_hostname=host)
                else:
                    sock = raw
                sock.settimeout(4.0)
                _ws_handshake(sock, host, ws_path)
                cid = f"kg_win_vps_{secrets.token_hex(4)}"
                _ws_send(sock, _mqtt_connect_pkt(cid, username=code, password=pwd))
                _ws_send(sock, _mqtt_sub_pkt(1, f"{TOPIC_V34}/#"))
                _ws_send(sock, _mqtt_sub_pkt(2, f"{TOPIC_V33}/#"))

                with self.vps_socket["lock"]:
                    self.vps_socket["sock"] = sock
                    self.vps_socket["connected"] = True
                    self.vps_socket["host"] = f"{host}:{port}"

                buf = bytearray()
                last_ping = time.time()
                while not self._stop and not self.vps_restart_event.is_set():
                    if time.time() - last_ping >= 25.0:
                        last_ping = time.time()
                        with self.vps_socket["lock"]:
                            _ws_send(sock, bytes([0xC0, 0x00]))
                    try:
                        op, payload = _ws_recv_frame(sock)
                        if op == 0x08:
                            break
                        if op == 0x09:
                            with self.vps_socket["lock"]:
                                _ws_send(sock, payload, opcode=0x0A)
                            continue
                        if op in (0x01, 0x02) and payload:
                            buf.extend(payload)
                            pkts, buf = _parse_mqtt_buffer(buf)
                            for h, body in pkts:
                                if (h & 0xF0) == 0x30 and len(body) >= 2:
                                    tlen = (body[0] << 8) | body[1]
                                    topic = body[2:2 + tlen].decode("utf-8", "ignore")
                                    pos = 2 + tlen
                                    if ((h >> 1) & 0x03) > 0:
                                        pos += 2
                                    self._handle_packet(topic, body[pos:])
                    except socket.timeout:
                        continue
            except Exception:
                time.sleep(4)
            finally:
                self._close_vps_socket()

    # --- DIRECT LAN PC HTTP CLIENT ---
    def _pc_lan_base_url(self):
        cfg = self.get_config()
        ip = str(cfg.get("pc_lan_ip") or "").strip()
        port = int(cfg.get("pc_lan_port") or 8765)
        if not ip:
            return ""
        return f"http://{ip}:{port}"

    def _pc_lan_login(self, base_url):
        cfg = self.get_config()
        pwd = str(cfg.get("pc_password") or "")
        if not base_url:
            return False
        try:
            body = json.dumps({"password": pwd}).encode("utf-8")
            req = urllib.request.Request(
                f"{base_url}/api/login",
                data=body,
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with urllib.request.urlopen(req, timeout=2.5) as resp:
                sc = resp.headers.get("Set-Cookie") or ""
                if "hb_session=" in sc:
                    self.pc_lan_cookie = sc.split(";", 1)[0].strip()
                data = json.loads(resp.read().decode("utf-8", errors="ignore") or "{}")
                if data.get("csrf"):
                    self.pc_lan_csrf = str(data["csrf"])
                return bool(data.get("ok"))
        except Exception:
            return False

    def _pc_lan_request(self, path, method="GET", data_obj=None, timeout=2.5):
        base = self._pc_lan_base_url()
        if not base:
            return None
        headers = {}
        if self.pc_lan_cookie:
            headers["Cookie"] = self.pc_lan_cookie
        if self.pc_lan_csrf:
            headers["X-CSRF-Token"] = self.pc_lan_csrf
        body = None
        if data_obj is not None:
            headers["Content-Type"] = "application/json"
            body = json.dumps(data_obj, ensure_ascii=False).encode("utf-8")
        try:
            req = urllib.request.Request(f"{base}{path}", data=body, headers=headers, method=method)
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                raw = resp.read().decode("utf-8", errors="ignore")
                self.pc_lan_online = True
                self.pc_lan_last_ok = time.time()
                return json.loads(raw) if raw.startswith("{") else {"ok": True}
        except urllib.error.HTTPError as he:
            if he.code in (401, 403):
                if self._pc_lan_login(base):
                    headers["Cookie"] = self.pc_lan_cookie
                    if self.pc_lan_csrf:
                        headers["X-CSRF-Token"] = self.pc_lan_csrf
                    try:
                        req2 = urllib.request.Request(f"{base}{path}", data=body, headers=headers, method=method)
                        with urllib.request.urlopen(req2, timeout=timeout) as resp2:
                            raw2 = resp2.read().decode("utf-8", errors="ignore")
                            self.pc_lan_online = True
                            self.pc_lan_last_ok = time.time()
                            return json.loads(raw2) if raw2.startswith("{") else {"ok": True}
                    except Exception:
                        pass
            return None
        except Exception:
            if time.time() - self.pc_lan_last_ok > 10.0:
                self.pc_lan_online = False
            return None

    def _pc_lan_poller(self):
        while not self._stop:
            base = self._pc_lan_base_url()
            if base:
                res = self._pc_lan_request("/api/state", method="GET", timeout=2.5)
                if isinstance(res, dict) and res.get("pc_name"):
                    self._ingest_pc_state(res, int(time.time() * 1000))
            time.sleep(4.5)

    # --- COMMAND SENDERS ---
    def send_pc_cmd(self, action, data_obj=None):
        data_obj = data_obj or {}
        cfg = self.get_config()
        code = str(cfg.get("pc_pairing_code") or "101-701").strip() or "101-701"
        pwd = str(cfg.get("pc_password") or "")
        now_ms = int(time.time() * 1000)

        # Optimistic UI updates
        with self.state_lock:
            if action == "/api/chat/config" and "tray_enabled" in data_obj:
                val = bool(data_obj["tray_enabled"])
                self.pc_chat_tray_override = val
                self.pc_chat_tray_override_until = time.time() + 10.0
                if isinstance(self.pc_state, dict):
                    self.pc_state["chat_tray_enabled"] = val
            elif action == "/api/full_lock" and isinstance(self.pc_state, dict):
                fl = self.pc_state.setdefault("full", {})
                if "lock_now" in data_obj:
                    fl["now"] = bool(data_obj["lock_now"])
                    fl["blocked"] = bool(data_obj["lock_now"])
            elif action == "/api/toggle" and isinstance(self.pc_state, dict):
                rname = data_obj.get("name")
                for ent in self.pc_state.get("entries", []):
                    if ent.get("name") == rname:
                        ent["enabled"] = not ent.get("enabled", False)
                        ent["blocked"] = ent["enabled"]

        # 1. Try direct LAN HTTP first (fast, local Wi-Fi)
        lan_res = None
        if self.pc_lan_online and action not in ("/api/sync",):
            lan_res = self._pc_lan_request(action, method="POST", data_obj=data_obj, timeout=2.5)
            if isinstance(lan_res, dict) and action == "/api/screen" and lan_res.get("screen_b64"):
                with self.state_lock:
                    self.pc_screen_b64 = lan_res["screen_b64"]
                    self.pc_screen_fmt = lan_res.get("fmt", "image/jpeg")
                    self.pc_screen_ts = now_ms

        # 2. Always also publish over P2P/VPS if LAN didn't confirm (or for sync/chat/config)
        if not lan_res or action in ("/api/sync", "/api/stream_start", "/api/stream_stop", "/api/chat/send", "/api/chat/config"):
            pkt = {
                "type": "PC_CMD",
                "pairing_code": code,
                "action": action,
                "data": data_obj,
                "ts": now_ms,
            }
            if pwd:
                pkt["password"] = pwd
            for root in (TOPIC_V33, TOPIC_V34):
                self.publish_all(f"{root}/pc_cmd/{code}", pkt, retain=False)

        return {"ok": True, "lan": bool(lan_res)}

    def send_phone_cmd(self, child_id, command, payload="", extra=None):
        extra = extra or {}
        now_ms = int(time.time() * 1000)
        cfg = self.get_config()
        cid = int(child_id or 8)
        pcode = str(extra.get("pairingCode") or cfg.get("phone_pairing_code") or "838-597").strip()
        alias_ids = sorted(set([cid] + list(extra.get("aliasIds") or [])))
        if not alias_ids:
            alias_ids = [cid]

        cmd = str(command or "").strip().upper()
        if cmd == "LOCK_DEVICE":
            cmd = "LOCK"
        elif cmd == "UNLOCK_DEVICE":
            cmd = "UNLOCK"
        elif cmd == "SIREN":
            cmd = "LOUD_RING"

        if cmd in ("LOCK", "UNLOCK"):
            locked = (cmd == "LOCK")
            with self.state_lock:
                for aid in alias_ids:
                    self.locks_by_id[aid] = {"locked": locked, "ts": now_ms, "pairingCode": pcode}
                    if aid in self.children_by_id:
                        self.children_by_id[aid]["isDeviceLocked"] = locked
            for aid in alias_ids:
                lock_pkt = {
                    "type": "LOCK_STATE",
                    "childId": aid,
                    "id": aid,
                    "pairingCode": pcode,
                    "isDeviceLocked": locked,
                    "timestamp": now_ms,
                    "ts": now_ms,
                }
                self.publish_all(f"{TOPIC_V34}/lock/{aid}", lock_pkt, retain=True)
                self.publish_all(f"{TOPIC_V33}/lock/{aid}", lock_pkt, retain=True)

        if cmd == "WAKE_UP":
            for aid in alias_ids:
                for sub_cmd, sub_pl in (("PING", "WAKE"), ("PARENT_SESSION_START", "1")):
                    self.publish_all(f"{TOPIC_V34}/cmd/{aid}", {
                        "type": "CMD",
                        "childId": aid,
                        "pairingCode": pcode,
                        "command": sub_cmd,
                        "payload": sub_pl,
                        "timestamp": now_ms,
                    }, retain=False)
            return {"ok": True, "command": "WAKE_UP"}

        if cmd == "CHILD_PATCH":
            fields = extra.get("fields") or {}
            with self.state_lock:
                for aid in alias_ids:
                    if aid in self.children_by_id:
                        self.children_by_id[aid].update(fields)
            for aid in alias_ids:
                pkt = {"type": "CHILD_PATCH", "id": aid, "pairingCode": pcode, "ts": now_ms}
                pkt.update(fields)
                self.publish_all(f"{TOPIC_V34}/state/{aid}", pkt, retain=True)
            return {"ok": True}

        if cmd == "RULES_UPDATE":
            rules = extra.get("rules") or []
            with self.state_lock:
                self.rules = rules
            for aid in alias_ids:
                self.publish_all(f"{TOPIC_V34}/data/{aid}", {
                    "type": "RULES_LIST",
                    "childId": aid,
                    "pairingCode": pcode,
                    "rules": rules,
                    "timestamp": now_ms,
                }, retain=True)
            return {"ok": True}

        # Optimistic local app block/unblock/limit update
        if cmd in ("BLOCK_APP", "UNBLOCK_APP", "SET_APP_LIMIT"):
            with self.state_lock:
                for k, alist in self.apps_by_code.items():
                    for a in alist:
                        if cmd in ("BLOCK_APP", "UNBLOCK_APP") and a.get("packageName") == payload:
                            a["isBlocked"] = (cmd == "BLOCK_APP")
                        elif cmd == "SET_APP_LIMIT" and "|" in payload:
                            pkg, mins = payload.split("|", 1)
                            if a.get("packageName") == pkg:
                                a["dailyLimitMinutes"] = int(mins or 0)

        for aid in alias_ids:
            self.publish_all(f"{TOPIC_V34}/cmd/{aid}", {
                "type": "CMD",
                "childId": aid,
                "pairingCode": pcode,
                "command": cmd,
                "payload": str(payload),
                "timestamp": now_ms,
            }, retain=False)
        return {"ok": True, "command": cmd}

    def send_chat(self, target, text, child_id=8, pairing_code="", alias_ids=None, attention=False):
        txt = str(text or "").strip()
        if not txt:
            return {"ok": False, "error": "empty"}
        cfg = self.get_config()
        parent_name = str(cfg.get("parent_name") or "Родитель")
        now_ms = int(time.time() * 1000)
        tgt = str(target or "PHONE").upper()  # PHONE | PC | BOTH

        if tgt in ("PHONE", "BOTH"):
            cid = int(child_id or 8)
            pcode = str(pairing_code or cfg.get("phone_pairing_code") or "838-597").strip()
            aids = sorted(set([cid] + list(alias_ids or [])))
            mid = f"win_ph_{now_ms}"
            pkt = {
                "type": "CHAT",
                "id": now_ms,
                "childId": cid,
                "pairingCode": pcode,
                "senderRole": "PARENT",
                "senderName": parent_name,
                "text": txt,
                "messageText": txt,
                "timestamp": now_ms,
                "isAttentionSignal": bool(attention),
            }
            self.archive.add_chat(mid, "PHONE", "PARENT", parent_name, txt, now_ms, bool(attention))
            for aid in aids:
                self.publish_all(f"{TOPIC_V34}/data/{aid}", pkt, retain=True)
                self.publish_all(f"{TOPIC_V34}/data/chat_{aid}", pkt, retain=True)

        if tgt in ("PC", "BOTH"):
            mid = f"win_pc_{now_ms}"
            self.archive.add_chat(mid, "PC", "PARENT", f"{parent_name} → ПК", txt, now_ms, False)
            with self.state_lock:
                self.pc_chat_messages.append({
                    "id": mid,
                    "sender": "PARENT",
                    "user": "",
                    "text": txt,
                    "ts": now_ms,
                })
                self.pc_chat_messages = self.pc_chat_messages[-60:]
            self.send_pc_cmd("/api/chat/send", {"text": txt, "sender": parent_name})

        return {"ok": True}

    def build_snapshot(self):
        now_ms = int(time.time() * 1000)
        cfg = self.get_config()
        with self.state_lock:
            # Deduplicate children by pairingCode or normalized custom name
            groups = []
            for cid, ch in self.children_by_id.items():
                code = str(ch.get("pairingCode") or f"id_{cid}").strip()
                norm_name = str(ch.get("name") or "").strip().lower()
                is_custom = len(norm_name) >= 2 and norm_name not in ("ребёнок", "ребенок", "child", "test")
                matched = None
                for g in groups:
                    if code in g["codes"] or (is_custom and norm_name in g["names"]):
                        matched = g
                        break
                if not matched:
                    groups.append({
                        "codes": {code},
                        "names": {norm_name} if is_custom else set(),
                        "ids": [cid],
                        "best": dict(ch),
                    })
                else:
                    matched["codes"].add(code)
                    if is_custom:
                        matched["names"].add(norm_name)
                    matched["ids"].append(cid)
                    ex = matched["best"]
                    if int(ch.get("ts") or 0) >= int(ex.get("ts") or 0):
                        m = dict(ex)
                        m.update(ch)
                        matched["best"] = m

            children_out = []
            for g in groups:
                ch = dict(g["best"])
                all_ids = sorted(set(g["ids"]))
                all_codes = sorted(c for c in g["codes"] if not c.startswith("id_"))
                best_screen = None
                for i in all_ids:
                    sc = self.screens_by_id.get(i)
                    if sc and (not best_screen or sc.get("ts", 0) > best_screen.get("ts", 0)):
                        best_screen = sc
                for c in all_codes:
                    sc = self.screens_by_code.get(c)
                    if sc and (not best_screen or sc.get("ts", 0) > best_screen.get("ts", 0)):
                        best_screen = sc
                apps = []
                for key in list(all_codes) + [str(i) for i in all_ids]:
                    if key in self.apps_by_code and len(self.apps_by_code[key]) > len(apps):
                        apps = self.apps_by_code[key]

                rx_ts = int(ch.get("_rx_ts") or ch.get("ts") or 0)
                is_online = bool(rx_ts and (now_ms - rx_ts) < 120_000)
                ch["isOnline"] = is_online
                ch["aliasIds"] = all_ids
                ch["aliasCodes"] = all_codes
                ch["activeForegroundApp"] = (best_screen or {}).get("activeAppName") or ch.get("activeForegroundApp") or "Нет активного кадра"
                ch["screenBase64"] = (best_screen or {}).get("base64Bitmap") or ""
                ch["screenTimestamp"] = (best_screen or {}).get("ts") or 0
                ch["apps"] = apps
                children_out.append(ch)

            children_out.sort(key=lambda x: int(x.get("ts") or 0), reverse=True)

            # PC summary
            pc_online = bool(self.pc_lan_online or (self.pc_state_ts and (now_ms - self.pc_state_ts) < 35_000))
            ps = dict(self.pc_state) if isinstance(self.pc_state, dict) else None
            if ps:
                ps["is_online"] = pc_online
                ps["lan_online"] = self.pc_lan_online
                ps["last_sync_ms"] = self.pc_state_ts
                ps["screen_b64"] = self.pc_screen_b64
                ps["screen_fmt"] = self.pc_screen_fmt
                ps["screen_ts"] = self.pc_screen_ts
                ps["stream_fps"] = self.pc_stream_fps
                ps["stream_frames"] = self.pc_stream_frames
                ps["chat_messages"] = list(self.pc_chat_messages)

            # Transport label
            vps_on = bool(self.vps_socket.get("connected"))
            vps_host = self.vps_socket.get("host") or ""
            brokers = sorted(self.brokers_connected)
            if vps_on:
                transport = f"VPS ({vps_host})" + (" · LAN ПК" if self.pc_lan_online else "")
            elif self.pc_lan_online and brokers:
                transport = f"LAN ПК · P2P ({brokers[0]})"
            elif brokers:
                transport = f"P2P ({brokers[0]})"
            else:
                transport = "Подключение к сети..."

            rules_copy = list(self.rules)

        chats = self.archive.get_chats(120)
        alerts = self.archive.get_alerts(100)
        active_cid = children_out[0]["id"] if children_out else 0
        locs = self.archive.get_locations(active_cid, 80)

        return {
            "appVersion": APP_VERSION,
            "compatVersion": COMPAT_VERSION,
            "serverTimeMs": now_ms,
            "transportLabel": transport,
            "connected": bool(vps_on or brokers or self.pc_lan_online),
            "vpsConnected": vps_on,
            "brokers": brokers,
            "pcLanOnline": self.pc_lan_online,
            "children": children_out,
            "rules": rules_copy,
            "pc": ps,
            "pcOnline": pc_online,
            "chats": chats,
            "alerts": alerts,
            "unreadAlerts": sum(1 for a in alerts if not a.get("is_read")),
            "locations": locs,
            "pinEnabled": bool(cfg.get("pin_enabled", True)),
            "configPublic": {
                "parent_name": cfg.get("parent_name", "Родитель"),
                "phone_pairing_code": cfg.get("phone_pairing_code", "838-597"),
                "pc_pairing_code": cfg.get("pc_pairing_code", "101-701"),
                "pc_lan_ip": cfg.get("pc_lan_ip", ""),
                "pc_lan_port": cfg.get("pc_lan_port", 8765),
                "pc_has_password": bool(cfg.get("pc_password")),
                "vps_enabled": bool(cfg.get("vps_enabled", False)),
                "vps_host": cfg.get("vps_host", ""),
                "vps_port": cfg.get("vps_port", 443),
                "pin_enabled": bool(cfg.get("pin_enabled", True)),
                "minimize_to_tray": bool(cfg.get("minimize_to_tray", True)),
                "notifications_enabled": bool(cfg.get("notifications_enabled", True)),
                "sound_enabled": bool(cfg.get("sound_enabled", True)),
            },
        }


# =============================================================================
# 6. MODERN DESKTOP UI (EMBEDDED SELF-CONTAINED HTML/CSS/JS)
#    Design: 60-30-10 Dark Slate Console, 256px Sidebar, 3-Zone Top Bar,
#    Zero Static Pills, Single-Elevation Hairline Panels, Tabular Numerals
# =============================================================================

PARENT_UI_HTML = r"""<!DOCTYPE html>
<html lang="ru">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>KidsGuard Родитель — Windows</title>
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link href="https://fonts.googleapis.com/css2?family=DM+Sans:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500;600&family=Plus+Jakarta+Sans:wght@600;700;800&display=swap" rel="stylesheet">
  <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
  <style>
    :root {
      --bg: #0B1120;
      --surface: #111827;
      --surface-alt: #161F31;
      --border: #1E293B;
      --border-strong: #334155;
      --text: #F1F5F9;
      --text-muted: #94A3B8;
      --text-dim: #64748B;
      --accent: #38BDF8;
      --accent-hover: #7DD3FC;
      --accent-ink: #081220;
      --green: #16A34A;
      --green-text: #4ADE80;
      --amber: #D97706;
      --amber-text: #FBBF24;
      --red: #DC2626;
      --red-text: #F87171;
      --font-display: 'Plus Jakarta Sans', 'Segoe UI Variable', 'Segoe UI', sans-serif;
      --font-body: 'DM Sans', 'Segoe UI', sans-serif;
      --font-mono: 'JetBrains Mono', 'Consolas', monospace;
      --radius: 10px;
    }

    * { box-sizing: border-box; margin: 0; padding: 0; }
    html, body {
      height: 100%;
      background: var(--bg);
      color: var(--text);
      font-family: var(--font-body);
      font-size: 14px;
      line-height: 1.5;
      overflow: hidden;
      -webkit-font-smoothing: antialiased;
    }
    .mono, .num { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
    button, input, select, textarea { font-family: inherit; color: inherit; }

    /* App Shell */
    .shell { display: flex; height: 100vh; width: 100vw; }

    /* Sidebar (256px) */
    .sidebar {
      width: 256px;
      min-width: 256px;
      background: var(--surface);
      border-right: 1px solid var(--border);
      display: flex;
      flex-direction: column;
      padding: 20px 16px;
      gap: 20px;
    }
    .brand {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 0 8px;
    }
    .brand-mark {
      width: 30px; height: 30px; border-radius: 8px;
      background: #0C4A6E; border: 1px solid #0284C7;
      display: flex; align-items: center; justify-content: center;
      color: var(--accent); font-weight: 800; font-family: var(--font-display); font-size: 15px;
      flex-shrink: 0;
    }
    .brand-name {
      font-family: var(--font-display);
      font-size: 16px;
      font-weight: 700;
      letter-spacing: -0.01em;
      white-space: nowrap;
    }

    .child-select-wrap { padding: 0 4px; }
    .field-label {
      display: block;
      font-size: 12px;
      color: var(--text-muted);
      margin-bottom: 6px;
      font-weight: 500;
    }
    .select-input, .text-input {
      width: 100%;
      background: var(--bg);
      border: 1px solid var(--border-strong);
      border-radius: 8px;
      padding: 8px 12px;
      font-size: 13px;
      color: var(--text);
      outline: none;
      transition: border-color 150ms ease;
    }
    .select-input:focus, .text-input:focus { border-color: var(--accent); }

    .nav-list { display: flex; flex-direction: column; gap: 4px; flex: 1; }
    .nav-btn {
      display: flex;
      align-items: center;
      justify-content: space-between;
      width: 100%;
      padding: 10px 12px;
      border-radius: 8px;
      border: 1px solid transparent;
      background: transparent;
      color: var(--text-muted);
      font-size: 13.5px;
      font-weight: 500;
      cursor: pointer;
      text-align: left;
      white-space: nowrap;
      transition: background 150ms ease, color 150ms ease;
    }
    .nav-btn:hover { background: var(--surface-alt); color: var(--text); }
    .nav-btn.active {
      background: rgba(56, 189, 248, 0.12);
      border-color: rgba(56, 189, 248, 0.28);
      color: var(--accent);
      font-weight: 600;
    }
    .nav-count { font-size: 12px; color: var(--red-text); font-weight: 600; }

    .sidebar-footer {
      border-top: 1px solid var(--border);
      padding-top: 14px;
      display: flex;
      flex-direction: column;
      gap: 4px;
    }

    /* Main Column */
    .main-col { flex: 1; display: flex; flex-direction: column; min-width: 0; background: var(--bg); }

    /* Top Bar Contract (3 Zones) */
    .topbar {
      height: 60px;
      min-height: 60px;
      padding: 0 28px;
      border-bottom: 1px solid var(--border);
      background: var(--surface);
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 24px;
    }
    .topbar-title {
      font-family: var(--font-display);
      font-size: 17px;
      font-weight: 700;
      white-space: nowrap;
      letter-spacing: -0.01em;
    }
    .topbar-meta {
      font-size: 12.5px;
      color: var(--text-muted);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .topbar-actions { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }

    /* Viewport */
    .viewport {
      flex: 1;
      overflow-y: auto;
      padding: 24px 28px 36px;
    }
    .viewport-inner { max-width: 1320px; margin: 0 auto; display: flex; flex-direction: column; gap: 24px; }

    /* Buttons */
    .btn {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 6px;
      padding: 8px 16px;
      min-height: 38px;
      border-radius: 8px;
      font-size: 13px;
      font-weight: 600;
      border: 1px solid var(--border-strong);
      background: var(--surface-alt);
      color: var(--text);
      cursor: pointer;
      white-space: nowrap;
      flex-shrink: 0;
      transition: background 150ms ease, border-color 150ms ease, opacity 150ms ease;
    }
    .btn:hover { background: #1E293B; border-color: #475569; }
    .btn:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
    .btn-primary { background: var(--accent); border-color: var(--accent); color: var(--accent-ink); }
    .btn-primary:hover { background: var(--accent-hover); border-color: var(--accent-hover); }
    .btn-danger { background: rgba(220, 38, 38, 0.16); border-color: rgba(220, 38, 38, 0.55); color: var(--red-text); }
    .btn-danger:hover { background: rgba(220, 38, 38, 0.28); }
    .btn-success { background: rgba(22, 163, 74, 0.16); border-color: rgba(22, 163, 74, 0.5); color: var(--green-text); }
    .btn-success:hover { background: rgba(22, 163, 74, 0.28); }
    .btn-sm { min-height: 32px; padding: 5px 12px; font-size: 12px; }

    /* Flat Single-Elevation Panels */
    .panel {
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: var(--radius);
      padding: 20px;
      display: flex;
      flex-direction: column;
      gap: 16px;
    }
    .panel-head {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: 16px;
      flex-wrap: wrap;
    }
    .panel-title {
      font-family: var(--font-display);
      font-size: 15.5px;
      font-weight: 700;
      color: var(--text);
    }
    .meta-line {
      display: flex;
      align-items: center;
      gap: 8px;
      flex-wrap: wrap;
      font-size: 12.5px;
      color: var(--text-muted);
    }
    .sep { color: var(--text-dim); }
    .status-ok { color: var(--green-text); font-weight: 600; }
    .status-warn { color: var(--amber-text); font-weight: 600; }
    .status-err { color: var(--red-text); font-weight: 600; }

    /* Grids */
    .grid-2 { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px; align-items: start; }
    .grid-3 { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; }
    .grid-4 { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 16px; }
    .kpi-cell {
      border-top: 1px solid var(--border);
      padding-top: 12px;
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .kpi-label { font-size: 12px; color: var(--text-muted); }
    .kpi-val { font-family: var(--font-mono); font-size: 20px; font-weight: 600; color: var(--text); font-variant-numeric: tabular-nums; }

    .divider { height: 1px; background: var(--border); width: 100%; }
    .row-actions { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }

    /* Segmented Filter Controls */
    .segmented {
      display: inline-flex;
      background: var(--bg);
      border: 1px solid var(--border);
      border-radius: 8px;
      padding: 3px;
      gap: 3px;
    }
    .seg-btn {
      padding: 6px 12px;
      border: none;
      border-radius: 6px;
      background: transparent;
      color: var(--text-muted);
      font-size: 12.5px;
      font-weight: 600;
      cursor: pointer;
      white-space: nowrap;
      transition: background 150ms ease, color 150ms ease;
    }
    .seg-btn:hover { color: var(--text); }
    .seg-btn.active { background: var(--surface-alt); color: var(--accent); }

    /* Screen Viewports */
    .screen-stage {
      width: 100%;
      background: #050811;
      border: 1px solid var(--border);
      border-radius: 8px;
      min-height: 260px;
      max-height: 460px;
      display: flex;
      align-items: center;
      justify-content: center;
      overflow: hidden;
      position: relative;
    }
    .screen-stage img {
      max-width: 100%;
      max-height: 456px;
      object-fit: contain;
      display: block;
    }
    .screen-empty {
      padding: 40px 24px;
      text-align: center;
      color: var(--text-muted);
      font-size: 13px;
    }

    /* Data Tables */
    .table-wrap { width: 100%; overflow-x: auto; }
    table { width: 100%; border-collapse: collapse; font-size: 13px; }
    th {
      text-align: left;
      font-size: 12px;
      font-weight: 600;
      color: var(--text-muted);
      padding: 10px 12px;
      border-bottom: 1px solid var(--border-strong);
      white-space: nowrap;
    }
    td {
      padding: 10px 12px;
      border-bottom: 1px solid var(--border);
      vertical-align: middle;
    }
    tr:hover td { background: rgba(255, 255, 255, 0.02); }
    th.num-col, td.num-col { text-align: right; font-family: var(--font-mono); font-variant-numeric: tabular-nums; white-space: nowrap; }

    /* Toggle Switch */
    .switch-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
      padding: 10px 0;
      border-bottom: 1px solid var(--border);
    }
    .switch-row:last-child { border-bottom: none; }
    .switch-title { font-weight: 600; font-size: 13.5px; }
    .switch-desc { font-size: 12px; color: var(--text-muted); margin-top: 2px; }
    .switch {
      width: 44px; height: 24px; border-radius: 99px;
      background: #1E293B; border: 1px solid #334155;
      position: relative; cursor: pointer; flex-shrink: 0;
      transition: background 150ms ease, border-color 150ms ease;
    }
    .switch::after {
      content: ''; position: absolute; top: 2px; left: 2px;
      width: 18px; height: 18px; border-radius: 50%;
      background: #94A3B8; transition: transform 150ms ease, background 150ms ease;
    }
    .switch.on { background: var(--accent); border-color: var(--accent); }
    .switch.on::after { transform: translateX(20px); background: var(--accent-ink); }

    /* Chat */
    .chat-log {
      display: flex;
      flex-direction: column;
      gap: 10px;
      max-height: 460px;
      overflow-y: auto;
      padding-right: 6px;
    }
    .chat-msg {
      max-width: 78%;
      padding: 10px 14px;
      border-radius: 10px;
      border: 1px solid var(--border);
      background: var(--bg);
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .chat-msg.parent {
      align-self: flex-end;
      background: rgba(56, 189, 248, 0.1);
      border-color: rgba(56, 189, 248, 0.3);
    }
    .chat-msg.child { align-self: flex-start; }
    .chat-sender { font-size: 11.5px; color: var(--text-muted); font-weight: 600; }
    .chat-body { font-size: 13.5px; color: var(--text); white-space: pre-wrap; word-break: break-word; }
    .chat-compose { display: flex; gap: 10px; align-items: center; }

    /* Map */
    #gps-map { width: 100%; height: 380px; border-radius: 8px; border: 1px solid var(--border); background: #070C18; }

    /* PIN Overlay */
    .pin-overlay {
      position: fixed; inset: 0; z-index: 200;
      background: rgba(11, 17, 32, 0.96);
      display: flex; align-items: center; justify-content: center;
    }
    .pin-box {
      width: 360px; background: var(--surface); border: 1px solid var(--border-strong);
      border-radius: 12px; padding: 28px; display: flex; flex-direction: column; gap: 16px;
    }

    /* Fullscreen Modal */
    .fs-modal {
      position: fixed; inset: 0; z-index: 150;
      background: rgba(5, 8, 17, 0.95);
      display: none; flex-direction: column; padding: 18px 24px; gap: 12px;
    }
    .fs-modal.open { display: flex; }
    .fs-img-wrap { flex: 1; display: flex; align-items: center; justify-content: center; overflow: hidden; }
    .fs-img-wrap img { max-width: 100%; max-height: 100%; object-fit: contain; }

    /* Toast */
    .toast {
      position: fixed; bottom: 24px; right: 24px; z-index: 300;
      background: var(--surface-alt); border: 1px solid var(--accent);
      color: var(--text); padding: 10px 16px; border-radius: 8px;
      font-size: 13px; font-weight: 500; opacity: 0; pointer-events: none;
      transform: translateY(8px); transition: opacity 180ms ease, transform 180ms ease;
    }
    .toast.show { opacity: 1; transform: translateY(0); }

    @media (max-width: 1120px) {
      .grid-2, .grid-4 { grid-template-columns: 1fr; }
      .sidebar { width: 220px; min-width: 220px; }
    }
  </style>
</head>
<body>
  <div id="pin-overlay" class="pin-overlay" style="display:none;">
    <div class="pin-box">
      <div class="brand-name" style="font-size:18px;">KidsGuard Родитель</div>
      <div style="font-size:13px; color:var(--text-muted);">Введите мастер PIN-код для доступа к управлению устройствами.</div>
      <input id="pin-input" type="password" class="text-input mono" placeholder="PIN-код (по умолчанию 1234)" autocomplete="off" />
      <div id="pin-err" class="status-err" style="font-size:12.5px; display:none;">Неверный PIN-код</div>
      <button class="btn btn-primary" onclick="submitPin()">Войти</button>
    </div>
  </div>

  <div class="shell">
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-mark">K</div>
        <div class="brand-name">KidsGuard Родитель</div>
      </div>

      <div class="child-select-wrap">
        <label class="field-label" for="child-select">Профиль ребёнка</label>
        <select id="child-select" class="select-input" onchange="onSelectChild(this.value)">
          <option value="0">Ожидание устройства...</option>
        </select>
      </div>

      <nav class="nav-list">
        <button class="nav-btn active" data-tab="overview" onclick="switchTab('overview')">Обзор</button>
        <button class="nav-btn" data-tab="phone" onclick="switchTab('phone')">Телефон ребёнка</button>
        <button class="nav-btn" data-tab="pc" onclick="switchTab('pc')">Компьютер (ПК)</button>
        <button class="nav-btn" data-tab="map" onclick="switchTab('map')">Карта и GPS</button>
        <button class="nav-btn" data-tab="chat" onclick="switchTab('chat')">Семейный чат</button>
        <button class="nav-btn" data-tab="archive" onclick="switchTab('archive')">
          <span>Архив и тревоги</span>
          <span id="nav-alerts-count" class="nav-count num" style="display:none;">0</span>
        </button>
      </nav>

      <div class="sidebar-footer">
        <button class="nav-btn" data-tab="settings" onclick="switchTab('settings')">Настройки</button>
      </div>
    </aside>

    <div class="main-col">
      <header class="topbar">
        <div id="topbar-title" class="topbar-title">Обзор</div>
        <div id="topbar-meta" class="topbar-meta">Подключение к каналам связи...</div>
        <div class="topbar-actions">
          <button id="topbar-primary-btn" class="btn btn-sm" onclick="lockWindowNow()" style="display:none;">Заблокировать окно</button>
        </div>
      </header>

      <main class="viewport">
        <div id="view-root" class="viewport-inner"></div>
      </main>
    </div>
  </div>

  <div id="fs-modal" class="fs-modal">
    <div style="display:flex; justify-content:space-between; align-items:center;">
      <div id="fs-title" class="panel-title">Просмотр экрана</div>
      <div class="row-actions">
        <button class="btn btn-sm" onclick="downloadCurrentFrame()">Сохранить снимок</button>
        <button class="btn btn-sm btn-primary" onclick="closeFullscreen()">Закрыть</button>
      </div>
    </div>
    <div class="fs-img-wrap">
      <img id="fs-img" alt="Экран устройства" />
    </div>
  </div>

  <div id="toast" class="toast"></div>

  <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
  <script>
    const SESSION_TOKEN = "__SESSION_TOKEN__";

    const ui = {
      tab: "overview",
      activeChildId: 0,
      snapshot: null,
      pinUnlocked: false,
      appSearch: "",
      appFilter: "all",
      chatTarget: "PHONE",
      pcQuality: "BALANCED",
      pcStreamOn: false,
      pcShowAllTitles: false,
      archiveKind: "pc",
      archiveDay: "",
      archiveQuery: "",
      archiveData: { days: [], rows: [] },
      leafletMap: null,
      leafletMarker: null,
      leafletLine: null,
      fsSource: "pc",
      renderedTab: "",
    };

    const TAB_TITLES = {
      overview: "Обзор",
      phone: "Телефон ребёнка",
      pc: "Компьютер (ПК)",
      map: "Карта и GPS",
      chat: "Семейный чат",
      archive: "Архив и тревоги",
      settings: "Настройки",
    };

    function esc(s) {
      return String(s == null ? "" : s)
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;");
    }

    function fmtDur(sec) {
      const s = Math.max(0, Math.floor(Number(sec) || 0));
      const h = Math.floor(s / 3600);
      const m = Math.floor((s % 3600) / 60);
      const r = s % 60;
      if (h > 0) return `${h} ч ${m} мин`;
      if (m > 0) return `${m} мин ${r} с`;
      return `${r} с`;
    }

    function fmtTime(ts) {
      if (!ts) return "—";
      const d = new Date(Number(ts));
      return d.toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit", second: "2-digit" });
    }

    function fmtDateTime(ts) {
      if (!ts) return "—";
      const d = new Date(Number(ts));
      return d.toLocaleString("ru-RU", { day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" });
    }

    function toast(msg) {
      const el = document.getElementById("toast");
      el.textContent = msg;
      el.classList.add("show");
      clearTimeout(toast._t);
      toast._t = setTimeout(() => el.classList.remove("show"), 2600);
    }

    async function api(path, method = "GET", body = null) {
      const opts = {
        method,
        headers: { "X-Session-Token": SESSION_TOKEN },
        cache: "no-store",
      };
      if (body !== null) {
        opts.headers["Content-Type"] = "application/json";
        opts.body = JSON.stringify(body);
      }
      const res = await fetch(path, opts);
      return await res.json();
    }

    function getActiveChild() {
      const list = (ui.snapshot && ui.snapshot.children) || [];
      if (!list.length) return null;
      return list.find(c => Number(c.id) === Number(ui.activeChildId)) || list[0];
    }

    // --- PIN LOCK ---
    async function submitPin() {
      const val = document.getElementById("pin-input").value.trim();
      const res = await api("/api/pin/verify", "POST", { pin: val });
      if (res.ok) {
        ui.pinUnlocked = true;
        document.getElementById("pin-overlay").style.display = "none";
        document.getElementById("pin-err").style.display = "none";
        document.getElementById("pin-input").value = "";
        toast("Доступ открыт");
      } else {
        document.getElementById("pin-err").style.display = "block";
      }
    }

    function lockWindowNow() {
      ui.pinUnlocked = false;
      document.getElementById("pin-overlay").style.display = "flex";
      document.getElementById("pin-input").focus();
    }

    document.getElementById("pin-input").addEventListener("keydown", e => {
      if (e.key === "Enter") submitPin();
    });

    // --- NAVIGATION ---
    function switchTab(tab) {
      const prev = ui.tab;
      ui.tab = tab;
      document.querySelectorAll(".nav-btn").forEach(b => {
        b.classList.toggle("active", b.dataset.tab === tab);
      });
      document.getElementById("topbar-title").textContent = TAB_TITLES[tab] || tab;

      if (prev === "pc" && tab !== "pc" && ui.pcStreamOn) {
        ui.pcStreamOn = false;
        sendPcCmd("/api/stream_stop", {}, true);
      }
      if (tab === "pc") {
        sendPcCmd("/api/sync", {}, true);
      }
      if (tab === "map") {
        const ch = getActiveChild();
        if (ch) sendPhoneCmd("MAP_VIEWING", "1", true);
      }
      if (tab === "archive") {
        loadArchive();
      }
      renderView(true);
    }

    function onSelectChild(val) {
      ui.activeChildId = Number(val) || 0;
      renderView(true);
    }

    // --- COMMANDS ---
    async function sendPhoneCmd(command, payload = "", silent = false, extra = {}) {
      const ch = getActiveChild();
      const body = {
        childId: ch ? ch.id : 8,
        pairingCode: ch ? ch.pairingCode : "",
        aliasIds: ch ? (ch.aliasIds || [ch.id]) : [8],
        command,
        payload,
        extra,
      };
      const res = await api("/api/phone/cmd", "POST", body);
      if (!silent) toast(res.ok ? `Команда отправлена: ${command}` : "Ошибка отправки команды");
      setTimeout(pollState, 600);
    }

    async function sendPcCmd(action, data = {}, silent = false) {
      const res = await api("/api/pc/cmd", "POST", { action, data });
      if (!silent) toast(res.ok ? "Команда отправлена на ПК" : "Ошибка отправки на ПК");
      setTimeout(pollState, 600);
    }

    async function togglePcStream() {
      ui.pcStreamOn = !ui.pcStreamOn;
      if (ui.pcStreamOn) {
        const qMap = {
          ECONOMY: { w: 480, h: 270, quality: 45, fps: 4 },
          BALANCED: { w: 640, h: 360, quality: 52, fps: 5 },
          HD: { w: 1280, h: 720, quality: 68, fps: 5 },
        };
        const q = qMap[ui.pcQuality] || qMap.BALANCED;
        await sendPcCmd("/api/stream_start", { ...q, duration: 30, binary: false }, true);
        toast("Живой поток экрана ПК запущен");
      } else {
        await sendPcCmd("/api/stream_stop", {}, true);
        toast("Поток экрана ПК остановлен");
      }
      renderView(true);
    }

    function setPcQuality(q) {
      ui.pcQuality = q;
      if (ui.pcStreamOn) {
        ui.pcStreamOn = false;
        togglePcStream();
      } else {
        renderView(true);
      }
    }

    async function sendChatMessage(forceTarget = null, inputId = "chat-input") {
      const inp = document.getElementById(inputId);
      if (!inp) return;
      const text = inp.value.trim();
      if (!text) return;
      const ch = getActiveChild();
      const attEl = document.getElementById("chat-attention");
      const target = forceTarget || ui.chatTarget;
      inp.value = "";
      const res = await api("/api/chat/send", "POST", {
        target,
        text,
        childId: ch ? ch.id : 8,
        pairingCode: ch ? ch.pairingCode : "",
        aliasIds: ch ? (ch.aliasIds || [ch.id]) : [8],
        attention: attEl ? attEl.checked : false,
      });
      if (res.ok) {
        toast(target === "PC" ? "Отправлено на ПК" : target === "BOTH" ? "Отправлено на телефон и ПК" : "Сообщение отправлено");
        await pollState();
      }
    }

    async function addPcRule() {
      const name = document.getElementById("pc-rule-name").value.trim();
      const domains = document.getElementById("pc-rule-domains").value.split(/[\s,;]+/).map(s => s.trim()).filter(Boolean);
      const programs = document.getElementById("pc-rule-progs").value.split(/[\s,;]+/).map(s => s.trim()).filter(Boolean);
      const daily = parseInt(document.getElementById("pc-rule-daily").value || "0", 10) || 0;
      if (!name) { toast("Укажите название правила"); return; }
      await sendPcCmd("/api/add", { name, domains, programs, daily, from: "", to: "" });
      document.getElementById("pc-rule-name").value = "";
      document.getElementById("pc-rule-domains").value = "";
      document.getElementById("pc-rule-progs").value = "";
      document.getElementById("pc-rule-daily").value = "";
    }

    async function savePcFullLockSchedule() {
      const daily = parseInt(document.getElementById("pc-lock-daily").value || "0", 10) || 0;
      const from = document.getElementById("pc-lock-from").value.trim();
      const to = document.getElementById("pc-lock-to").value.trim();
      const acc = document.getElementById("pc-lock-acc").value.trim();
      await sendPcCmd("/api/full_lock", { daily, from, to, child_account: acc });
    }

    async function addPhoneRule() {
      const val = document.getElementById("phone-rule-val").value.trim();
      const rtype = document.getElementById("phone-rule-type").value;
      if (!val) return;
      const cur = ((ui.snapshot && ui.snapshot.rules) || []).slice();
      cur.push({ ruleType: rtype, value: val, isBlocked: true });
      await sendPhoneCmd("RULES_UPDATE", "", false, { rules: cur });
      document.getElementById("phone-rule-val").value = "";
    }

    async function deletePhoneRule(idx) {
      const cur = ((ui.snapshot && ui.snapshot.rules) || []).slice();
      cur.splice(idx, 1);
      await sendPhoneCmd("RULES_UPDATE", "", false, { rules: cur });
    }

    async function savePhoneLimits() {
      const lim = parseInt(document.getElementById("ph-daily-limit").value || "120", 10) || 120;
      const bStart = document.getElementById("ph-bed-start").value.trim() || "22:00";
      const bEnd = document.getElementById("ph-bed-end").value.trim() || "07:00";
      const bEn = document.getElementById("ph-bed-enabled").checked;
      await sendPhoneCmd("SET_LIMIT", String(lim), true);
      await sendPhoneCmd("CHILD_PATCH", "", false, {
        fields: {
          dailyScreenLimitMinutes: lim,
          bedtimeStart: bStart,
          bedtimeEnd: bEnd,
          bedtimeEnabled: bEn,
        },
      });
    }

    async function loadArchive() {
      const qs = new URLSearchParams({
        kind: ui.archiveKind,
        day: ui.archiveDay,
        q: ui.archiveQuery,
      });
      ui.archiveData = await api(`/api/archive?${qs.toString()}`);
      renderView(true);
    }

    async function markAlertsRead() {
      await api("/api/alerts/read", "POST", {});
      toast("Тревоги отмечены прочитанными");
      await pollState();
      if (ui.tab === "archive") loadArchive();
    }

    function exportArchive(fmt) {
      const qs = new URLSearchParams({
        kind: ui.archiveKind,
        day: ui.archiveDay,
        q: ui.archiveQuery,
        fmt,
        token: SESSION_TOKEN,
      });
      window.open(`/api/archive/export?${qs.toString()}`, "_blank");
    }

    async function saveSettingsForm() {
      const patch = {
        parent_name: document.getElementById("cfg-parent-name").value.trim() || "Родитель",
        phone_pairing_code: document.getElementById("cfg-phone-code").value.trim() || "838-597",
        pc_pairing_code: document.getElementById("cfg-pc-code").value.trim() || "101-701",
        pc_lan_ip: document.getElementById("cfg-pc-ip").value.trim(),
        pc_lan_port: parseInt(document.getElementById("cfg-pc-port").value || "8765", 10) || 8765,
        vps_enabled: document.getElementById("cfg-vps-en").checked,
        vps_host: document.getElementById("cfg-vps-host").value.trim(),
        vps_port: parseInt(document.getElementById("cfg-vps-port").value || "443", 10) || 443,
        pin_enabled: document.getElementById("cfg-pin-en").checked,
        minimize_to_tray: document.getElementById("cfg-tray").checked,
        notifications_enabled: document.getElementById("cfg-notif").checked,
        sound_enabled: document.getElementById("cfg-sound").checked,
      };
      const pcPwd = document.getElementById("cfg-pc-pwd").value;
      if (pcPwd) patch.pc_password = pcPwd;
      const vpsPwd = document.getElementById("cfg-vps-pwd").value;
      if (vpsPwd) patch.vps_password = vpsPwd;
      const newPin = document.getElementById("cfg-new-pin").value.trim();
      if (newPin) patch.master_pin = newPin;

      const res = await api("/api/settings", "POST", patch);
      if (res.ok) {
        toast("Настройки сохранены");
        await pollState();
      }
    }

    // --- FULLSCREEN FRAME ---
    function openFullscreen(source) {
      ui.fsSource = source;
      const modal = document.getElementById("fs-modal");
      const img = document.getElementById("fs-img");
      const title = document.getElementById("fs-title");
      if (source === "pc") {
        title.textContent = "Экран компьютера ребёнка";
        const pc = ui.snapshot && ui.snapshot.pc;
        if (pc && pc.lan_online && ui.pcStreamOn) {
          img.src = `/api/pc/mjpeg?token=${SESSION_TOKEN}&t=${Date.now()}`;
        } else if (pc && pc.screen_b64) {
          img.src = `data:${pc.screen_fmt || "image/jpeg"};base64,${pc.screen_b64}`;
        }
      } else {
        title.textContent = "Экран телефона ребёнка";
        const ch = getActiveChild();
        if (ch && ch.screenBase64) {
          img.src = `data:image/jpeg;base64,${ch.screenBase64}`;
        }
      }
      modal.classList.add("open");
    }

    function closeFullscreen() {
      document.getElementById("fs-modal").classList.remove("open");
    }

    function downloadCurrentFrame() {
      const img = document.getElementById("fs-img");
      if (!img || !img.src || !img.src.startsWith("data:")) {
        toast("Для сохранения снимка нажмите «Снимок экрана»");
        return;
      }
      const a = document.createElement("a");
      a.href = img.src;
      a.download = `kidsguard_${ui.fsSource}_${Date.now()}.jpg`;
      a.click();
    }

    // --- VIEWS ---
    function renderOverview() {
      const ch = getActiveChild();
      const pc = ui.snapshot && ui.snapshot.pc;
      const alerts = (ui.snapshot && ui.snapshot.alerts) || [];
      const pcFl = (pc && (pc.full || pc.full_lock)) || {};
      const pcLocked = !!(pcFl.now || pcFl.blocked);

      const phoneBlock = ch ? `
        <div class="panel">
          <div class="panel-head">
            <div>
              <div class="panel-title">Телефон · ${esc(ch.name || "Ребёнок")}</div>
              <div class="meta-line">
                <span class="${ch.isOnline ? "status-ok" : "status-warn"}">${ch.isOnline ? "В сети" : "Не в сети"}</span>
                <span class="sep">·</span>
                <span>${esc(ch.deviceModel || "Android")}</span>
                <span class="sep">·</span>
                <span class="mono">Код ${esc(ch.pairingCode || "—")}</span>
              </div>
            </div>
            <button class="btn btn-sm" onclick="switchTab('phone')">Открыть раздел</button>
          </div>
          <div class="grid-3">
            <div class="kpi-cell">
              <div class="kpi-label">Заряд батареи</div>
              <div class="kpi-val">${Number(ch.batteryLevel || 0)}%</div>
            </div>
            <div class="kpi-cell">
              <div class="kpi-label">Экранное время</div>
              <div class="kpi-val">${Number(ch.usedScreenTimeMinutes || 0)} / ${Number(ch.dailyScreenLimitMinutes || 120)} м</div>
            </div>
            <div class="kpi-cell">
              <div class="kpi-label">Блокировка</div>
              <div class="kpi-val ${ch.isDeviceLocked ? "status-err" : "status-ok"}" style="font-size:16px;">${ch.isDeviceLocked ? "Заблокирован" : "Открыт"}</div>
            </div>
          </div>
          <div class="meta-line">
            <span>Активное приложение: <b>${esc(ch.activeForegroundApp || "—")}</b></span>
          </div>
          <div class="row-actions">
            <button class="btn btn-sm ${ch.isDeviceLocked ? "btn-success" : "btn-danger"}" onclick="sendPhoneCmd('${ch.isDeviceLocked ? "UNLOCK" : "LOCK"}')">
              ${ch.isDeviceLocked ? "Разблокировать" : "Заблокировать"}
            </button>
            <button class="btn btn-sm" onclick="sendPhoneCmd('WAKE_UP')">Разбудить</button>
            <button class="btn btn-sm" onclick="sendPhoneCmd('LOUD_RING')">Сирена</button>
            <button class="btn btn-sm" onclick="sendPhoneCmd('TAKE_SCREENSHOT', '1')">Снимок экрана</button>
          </div>
        </div>
      ` : `
        <div class="panel">
          <div class="panel-title">Телефон ребёнка</div>
          <div style="color:var(--text-muted); font-size:13px;">
            Устройство ещё не вышло на связь. Проверьте код привязки в разделе «Настройки» или убедитесь, что телефон ребёнка подключён к сети.
          </div>
          <div class="row-actions">
            <button class="btn btn-sm" onclick="switchTab('settings')">Открыть настройки</button>
          </div>
        </div>
      `;

      const pcBlock = pc ? `
        <div class="panel">
          <div class="panel-head">
            <div>
              <div class="panel-title">Компьютер · ${esc(pc.pc_name || "Windows ПК")}</div>
              <div class="meta-line">
                <span class="${pc.is_online ? "status-ok" : "status-warn"}">${pc.is_online ? "В сети" : "Не в сети"}</span>
                <span class="sep">·</span>
                <span>${pc.lan_online ? "Домашняя сеть LAN" : "Облако P2P"}</span>
                <span class="sep">·</span>
                <span class="mono">Код ${esc(pc.pairing_code || "101-701")}</span>
                ${pc.script_version ? `<span class="sep">·</span><span class="mono">v${esc(pc.script_version)}</span>` : ""}
              </div>
            </div>
            <button class="btn btn-sm" onclick="switchTab('pc')">Открыть раздел</button>
          </div>
          <div class="grid-3">
            <div class="kpi-cell">
              <div class="kpi-label">За ПК сегодня</div>
              <div class="kpi-val">${fmtDur(pc.computer_today_sec || 0)}</div>
            </div>
            <div class="kpi-cell">
              <div class="kpi-label">Пользователь</div>
              <div class="kpi-val" style="font-size:15px;">${esc(pcFl.child_account || ((pc.active_windows && pc.active_windows[0] && pc.active_windows[0].user) || "—"))}</div>
            </div>
            <div class="kpi-cell">
              <div class="kpi-label">Состояние ПК</div>
              <div class="kpi-val ${pcLocked ? "status-err" : "status-ok"}" style="font-size:16px;">${pcLocked ? "Заблокирован" : "Открыт"}</div>
            </div>
          </div>
          <div class="row-actions">
            <button class="btn btn-sm ${pcLocked ? "btn-success" : "btn-danger"}" onclick="sendPcCmd('/api/full_lock', { lock_now: ${!pcLocked} })">
              ${pcLocked ? "Разблокировать ПК" : "Заблокировать ПК"}
            </button>
            <button class="btn btn-sm" onclick="sendPcCmd('/api/screen', {})">Снимок экрана ПК</button>
            <button class="btn btn-sm" onclick="switchTab('chat')">Написать на ПК</button>
          </div>
        </div>
      ` : `
        <div class="panel">
          <div class="panel-title">Компьютер ребёнка (Windows ПК)</div>
          <div style="color:var(--text-muted); font-size:13px;">
            Ожидание телеметрии от hosts_blocker. Проверьте код привязки ПК (по умолчанию 101-701) или локальный IP в «Настройках».
          </div>
          <div class="row-actions">
            <button class="btn btn-sm" onclick="sendPcCmd('/api/sync', {})">Запросить связь</button>
            <button class="btn btn-sm" onclick="switchTab('settings')">Настройки ПК</button>
          </div>
        </div>
      `;

      const recentAlerts = alerts.slice(0, 6).map(a => `
        <tr>
          <td class="mono" style="white-space:nowrap; color:var(--text-muted);">${fmtDateTime(a.ts)}</td>
          <td class="${String(a.alert_type).toUpperCase() === "SOS" ? "status-err" : ""}" style="font-weight:600;">${esc(a.title)}</td>
          <td style="color:var(--text-muted);">${esc(a.message)}</td>
        </tr>
      `).join("");

      return `
        <div class="grid-2">${phoneBlock}${pcBlock}</div>
        <div class="panel">
          <div class="panel-head">
            <div class="panel-title">Последние уведомления и тревоги</div>
            <button class="btn btn-sm" onclick="switchTab('archive')">Весь архив</button>
          </div>
          ${recentAlerts ? `
            <div class="table-wrap">
              <table>
                <thead><tr><th>Время</th><th>Событие</th><th>Подробности</th></tr></thead>
                <tbody>${recentAlerts}</tbody>
              </table>
            </div>
          ` : `<div style="color:var(--text-muted); font-size:13px;">Новых тревог и уведомлений пока нет.</div>`}
        </div>
      `;
    }

    function renderPhone() {
      const ch = getActiveChild();
      if (!ch) {
        return `<div class="panel"><div class="panel-title">Телефон ребёнка не подключён</div><div style="color:var(--text-muted);">Откройте «Настройки» и проверьте код привязки устройства.</div></div>`;
      }
      const apps = (ch.apps || []).filter(a => {
        const q = ui.appSearch.trim().toLowerCase();
        const matchQ = !q || String(a.appName || "").toLowerCase().includes(q) || String(a.packageName || "").toLowerCase().includes(q);
        const matchF = ui.appFilter === "all" || (ui.appFilter === "blocked" ? a.isBlocked : !a.isBlocked);
        return matchQ && matchF;
      });
      const rules = (ui.snapshot && ui.snapshot.rules) || [];

      return `
        <div class="grid-2">
          <div class="panel">
            <div class="panel-head">
              <div>
                <div class="panel-title">Экран телефона</div>
                <div class="meta-line">
                  <span class="${ch.isOnline ? "status-ok" : "status-warn"}">${ch.isOnline ? "Онлайн" : "Оффлайн"}</span>
                  <span class="sep">·</span>
                  <span>Кадр: <span class="mono">${fmtTime(ch.screenTimestamp)}</span></span>
                  <span class="sep">·</span>
                  <span>${esc(ch.activeForegroundApp || "—")}</span>
                </div>
              </div>
              ${ch.screenBase64 ? `<button class="btn btn-sm" onclick="openFullscreen('phone')">Во весь экран</button>` : ""}
            </div>
            <div class="screen-stage">
              ${ch.screenBase64
                ? `<img id="phone-live-img" src="data:image/jpeg;base64,${ch.screenBase64}" alt="Экран телефона ребёнка" />`
                : `<div class="screen-empty">Нажмите «Снимок экрана» или «Запустить эфир», чтобы увидеть экран телефона.</div>`}
            </div>
            <div class="row-actions">
              <button class="btn btn-sm btn-primary" onclick="sendPhoneCmd('START_LIVE_STREAM', '1')">Запустить эфир</button>
              <button class="btn btn-sm" onclick="sendPhoneCmd('STOP_LIVE_STREAM', '1')">Стоп эфир</button>
              <button class="btn btn-sm" onclick="sendPhoneCmd('TAKE_SCREENSHOT', '1')">Снимок экрана</button>
              <button class="btn btn-sm" onclick="sendPhoneCmd('WAKE_UP')">Разбудить</button>
              <button class="btn btn-sm ${ch.isDeviceLocked ? "btn-success" : "btn-danger"}" onclick="sendPhoneCmd('${ch.isDeviceLocked ? "UNLOCK" : "LOCK"}')">
                ${ch.isDeviceLocked ? "Разблокировать" : "Заблокировать"}
              </button>
              <button class="btn btn-sm" onclick="sendPhoneCmd('LOUD_RING')">Сирена</button>
            </div>
          </div>

          <div class="panel">
            <div class="panel-title">Режимы, время и расписание сна</div>
            <div class="switch-row">
              <div>
                <div class="switch-title">Режим учёбы</div>
                <div class="switch-desc">Ограничивает развлекательные приложения на время занятий</div>
              </div>
              <button class="switch ${ch.studyModeActive ? "on" : ""}" onclick="sendPhoneCmd('${ch.studyModeActive ? "STUDY_OFF" : "STUDY_ON"}')"></button>
            </div>
            <div class="switch-row">
              <div>
                <div class="switch-title">Мобильный интернет (4G/5G)</div>
                <div class="switch-desc">Разрешить фоновую синхронизацию вне домашнего Wi-Fi</div>
              </div>
              <button class="switch ${ch.allowMobileData !== false ? "on" : ""}" onclick="sendPhoneCmd('${ch.allowMobileData !== false ? "NET_WIFI_ONLY" : "NET_ALL"}')"></button>
            </div>
            <div class="switch-row">
              <div>
                <div class="switch-title">Отслеживание GPS</div>
                <div class="switch-desc">Передача координат телефона ребёнка на карту</div>
              </div>
              <button class="switch ${ch.gpsTrackingEnabled !== false ? "on" : ""}" onclick="sendPhoneCmd('${ch.gpsTrackingEnabled !== false ? "GPS_TRACKING_OFF" : "GPS_TRACKING_ON"}')"></button>
            </div>
            <div class="divider"></div>
            <div class="grid-3">
              <div>
                <label class="field-label">Лимит (мин/день)</label>
                <input id="ph-daily-limit" type="number" class="text-input mono" value="${Number(ch.dailyScreenLimitMinutes || 120)}" />
              </div>
              <div>
                <label class="field-label">Сон с</label>
                <input id="ph-bed-start" type="text" class="text-input mono" value="${esc(ch.bedtimeStart || "22:00")}" />
              </div>
              <div>
                <label class="field-label">Сон до</label>
                <input id="ph-bed-end" type="text" class="text-input mono" value="${esc(ch.bedtimeEnd || "07:00")}" />
              </div>
            </div>
            <div class="row-actions" style="justify-content:space-between;">
              <label style="display:flex; align-items:center; gap:8px; font-size:13px; cursor:pointer;">
                <input id="ph-bed-enabled" type="checkbox" ${ch.bedtimeEnabled !== false ? "checked" : ""} />
                Включить расписание сна
              </label>
              <button class="btn btn-sm btn-primary" onclick="savePhoneLimits()">Сохранить расписание</button>
            </div>
          </div>
        </div>

        <div class="panel">
          <div class="panel-head">
            <div>
              <div class="panel-title">Приложения на телефоне</div>
              <div class="meta-line"><span>Всего получено: <span class="mono">${(ch.apps || []).length}</span></span></div>
            </div>
            <div class="row-actions">
              <input type="text" class="text-input" style="width:220px;" placeholder="Поиск приложения..." value="${esc(ui.appSearch)}" oninput="ui.appSearch=this.value; renderView(true);" />
              <div class="segmented">
                <button class="seg-btn ${ui.appFilter === "all" ? "active" : ""}" onclick="ui.appFilter='all'; renderView(true);">Все</button>
                <button class="seg-btn ${ui.appFilter === "blocked" ? "active" : ""}" onclick="ui.appFilter='blocked'; renderView(true);">Заблокированные</button>
                <button class="seg-btn ${ui.appFilter === "allowed" ? "active" : ""}" onclick="ui.appFilter='allowed'; renderView(true);">Разрешённые</button>
              </div>
              <button class="btn btn-sm" onclick="sendPhoneCmd('REFRESH_APPS')">Обновить список</button>
            </div>
          </div>
          ${apps.length ? `
            <div class="table-wrap">
              <table>
                <thead><tr><th>Приложение</th><th>Пакет</th><th class="num-col">Сегодня</th><th class="num-col">Лимит</th><th>Статус</th><th class="num-col">Действие</th></tr></thead>
                <tbody>
                  ${apps.slice(0, 80).map(a => `
                    <tr>
                      <td style="font-weight:600;">${esc(a.appName || a.packageName)}</td>
                      <td class="mono" style="color:var(--text-muted); font-size:12px;">${esc(a.packageName)}</td>
                      <td class="num-col">${Number(a.usedMinutesToday || 0)} мин</td>
                      <td class="num-col">${Number(a.dailyLimitMinutes || 0) > 0 ? a.dailyLimitMinutes + " мин" : "—"}</td>
                      <td><span class="${a.isBlocked ? "status-err" : "status-ok"}">${a.isBlocked ? "Заблокировано" : "Разрешено"}</span></td>
                      <td class="num-col">
                        <button class="btn btn-sm ${a.isBlocked ? "btn-success" : "btn-danger"}" onclick="sendPhoneCmd('${a.isBlocked ? "UNBLOCK_APP" : "BLOCK_APP"}', '${esc(a.packageName)}')">
                          ${a.isBlocked ? "Разрешить" : "Заблокировать"}
                        </button>
                      </td>
                    </tr>
                  `).join("")}
                </tbody>
              </table>
            </div>
          ` : `<div style="color:var(--text-muted); font-size:13px;">Список приложений пуст. Нажмите «Обновить список», чтобы запросить установленные программы с телефона.</div>`}
        </div>

        <div class="panel">
          <div class="panel-head">
            <div class="panel-title">Веб-фильтр телефона</div>
            <div class="row-actions">
              <select id="phone-rule-type" class="select-input" style="width:140px;">
                <option value="DOMAIN">Домен / сайт</option>
                <option value="KEYWORD">Ключевое слово</option>
              </select>
              <input id="phone-rule-val" type="text" class="text-input" style="width:240px;" placeholder="Например: tiktok.com" />
              <button class="btn btn-sm btn-primary" onclick="addPhoneRule()">Добавить</button>
            </div>
          </div>
          ${rules.length ? `
            <div class="table-wrap">
              <table>
                <thead><tr><th>Тип</th><th>Значение</th><th>Статус</th><th class="num-col">Удалить</th></tr></thead>
                <tbody>
                  ${rules.map((r, idx) => `
                    <tr>
                      <td class="mono" style="color:var(--text-muted);">${esc(r.ruleType || "DOMAIN")}</td>
                      <td style="font-weight:600;">${esc(r.value)}</td>
                      <td><span class="status-err">Блокируется</span></td>
                      <td class="num-col"><button class="btn btn-sm" onclick="deletePhoneRule(${idx})">Удалить</button></td>
                    </tr>
                  `).join("")}
                </tbody>
              </table>
            </div>
          ` : `<div style="color:var(--text-muted); font-size:13px;">Правил веб-фильтра пока нет.</div>`}
        </div>
      `;
    }

    function renderPc() {
      const pc = ui.snapshot && ui.snapshot.pc;
      if (!pc) {
        return `
          <div class="panel">
            <div class="panel-title">Компьютер ребёнка не в сети</div>
            <div style="color:var(--text-muted); font-size:13px;">
              Убедитесь, что на компьютере ребёнка запущен hosts_blocker.py / hosts_blocker.exe, а код привязки (по умолчанию 101-701) совпадает в «Настройках».
            </div>
            <div class="row-actions">
              <button class="btn btn-sm btn-primary" onclick="sendPcCmd('/api/sync', {})">Проверить связь с ПК</button>
              <button class="btn btn-sm" onclick="switchTab('settings')">Открыть настройки</button>
            </div>
          </div>
        `;
      }

      const fl = pc.full || pc.full_lock || {};
      const locked = !!(fl.now || fl.blocked);
      const entries = pc.entries || [];
      const actUsers = pc._merged_activity || [];
      const allProcs = [];
      const allTitles = [];
      actUsers.forEach(u => {
        (u.procs || []).forEach(p => allProcs.push({ user: u.user, ...p }));
        (u.titles || []).forEach(t => allTitles.push({ user: u.user, ...t }));
      });
      allProcs.sort((a, b) => b.sec - a.sec);
      allTitles.sort((a, b) => b.sec - a.sec);

      const pcChats = pc.chat_messages || [];
      const trayEnabled = !!pc.chat_tray_enabled;
      const useMjpeg = ui.pcStreamOn && pc.lan_online;
      const imgSrc = useMjpeg
        ? `/api/pc/mjpeg?token=${SESSION_TOKEN}`
        : (pc.screen_b64 ? `data:${pc.screen_fmt || "image/jpeg"};base64,${pc.screen_b64}` : "");

      return `
        <div class="grid-2">
          <div class="panel">
            <div class="panel-head">
              <div>
                <div class="panel-title">Живой монитор · ${esc(pc.pc_name || "Windows ПК")}</div>
                <div class="meta-line">
                  <span class="${pc.is_online ? "status-ok" : "status-warn"}">${pc.is_online ? "В сети" : "Не в сети"}</span>
                  <span class="sep">·</span>
                  <span>${pc.lan_online ? "LAN Wi-Fi (MJPEG)" : "Интернет P2P"}</span>
                  <span class="sep">·</span>
                  <span class="mono">${ui.pcStreamOn ? (pc.stream_fps + " к/с") : ("кадр " + fmtTime(pc.screen_ts))}</span>
                </div>
              </div>
              <div class="segmented">
                <button class="seg-btn ${ui.pcQuality === "ECONOMY" ? "active" : ""}" onclick="setPcQuality('ECONOMY')">Эконом</button>
                <button class="seg-btn ${ui.pcQuality === "BALANCED" ? "active" : ""}" onclick="setPcQuality('BALANCED')">Баланс</button>
                <button class="seg-btn ${ui.pcQuality === "HD" ? "active" : ""}" onclick="setPcQuality('HD')">HD</button>
              </div>
            </div>
            <div class="screen-stage">
              ${imgSrc
                ? `<img id="pc-live-img" src="${imgSrc}" alt="Монитор ПК ребёнка" />`
                : `<div class="screen-empty">Нажмите «Запустить видеопоток» или «Снимок экрана», чтобы увидеть монитор ПК.</div>`}
            </div>
            <div class="row-actions">
              <button class="btn btn-sm ${ui.pcStreamOn ? "btn-danger" : "btn-primary"}" onclick="togglePcStream()">
                ${ui.pcStreamOn ? "Остановить поток" : "Запустить видеопоток"}
              </button>
              <button class="btn btn-sm" onclick="sendPcCmd('/api/screen', {})">Снимок экрана</button>
              ${imgSrc ? `<button class="btn btn-sm" onclick="openFullscreen('pc')">Во весь экран</button>` : ""}
              <button class="btn btn-sm" onclick="sendPcCmd('/api/sync', {})">Обновить данные</button>
            </div>
          </div>

          <div class="panel">
            <div class="panel-head">
              <div>
                <div class="panel-title">Полная блокировка и чат с ПК</div>
                <div class="meta-line">
                  <span>За ПК сегодня: <b class="mono">${fmtDur(pc.computer_today_sec || 0)}</b></span>
                  <span class="sep">·</span>
                  <span class="${locked ? "status-err" : "status-ok"}">${locked ? "ПК заблокирован" : "ПК открыт"}</span>
                </div>
              </div>
              <button class="btn btn-sm ${locked ? "btn-success" : "btn-danger"}" onclick="sendPcCmd('/api/full_lock', { lock_now: ${!locked} })">
                ${locked ? "Разблокировать ПК" : "Заблокировать ПК"}
              </button>
            </div>

            <div class="grid-4">
              <div>
                <label class="field-label">Учётная запись</label>
                <input id="pc-lock-acc" class="text-input mono" value="${esc(fl.child_account || "")}" placeholder="Имя Windows" />
              </div>
              <div>
                <label class="field-label">Лимит (мин/день)</label>
                <input id="pc-lock-daily" type="number" class="text-input mono" value="${Number(fl.daily || 0)}" />
              </div>
              <div>
                <label class="field-label">Запрет с</label>
                <input id="pc-lock-from" class="text-input mono" value="${esc(fl.from || "")}" placeholder="22:00" />
              </div>
              <div>
                <label class="field-label">Запрет до</label>
                <input id="pc-lock-to" class="text-input mono" value="${esc(fl.to || "")}" placeholder="07:00" />
              </div>
            </div>
            <div class="row-actions">
              <button class="btn btn-sm" onclick="savePcFullLockSchedule()">Сохранить расписание ПК</button>
            </div>

            <div class="divider"></div>

            <div class="switch-row" style="padding:4px 0;">
              <div>
                <div class="switch-title">Иконка чата в трее ПК (ребёнок может писать сам)</div>
                <div class="switch-desc">${trayEnabled ? "Включено: значок чата виден в трее Windows у ребёнка" : "Выключено: в трее пусто, окно чата открывается только когда пишете вы"}</div>
              </div>
              <button class="switch ${trayEnabled ? "on" : ""}" onclick="sendPcCmd('/api/chat/config', { tray_enabled: ${!trayEnabled} })"></button>
            </div>

            <div class="chat-log" style="max-height:170px;">
              ${pcChats.length ? pcChats.slice(-8).map(m => `
                <div class="chat-msg ${m.sender === "PARENT" ? "parent" : "child"}">
                  <div class="chat-sender">${m.sender === "PARENT" ? "Вы → ПК" : esc("ПК · " + (m.user || "Ребёнок"))} · <span class="mono">${fmtTime(m.ts)}</span></div>
                  <div class="chat-body">${esc(m.text)}</div>
                </div>
              `).join("") : `<div style="color:var(--text-muted); font-size:12.5px;">Напишите сообщение — на компьютере поверх окон откроется окно со звуковым сигналом.</div>`}
            </div>
            <div class="chat-compose">
              <input id="pc-quick-chat-input" class="text-input" placeholder="Написать сообщение на компьютер ребёнка..." onkeydown="if(event.key==='Enter') sendChatMessage('PC','pc-quick-chat-input')" />
              <button class="btn btn-sm btn-primary" onclick="sendChatMessage('PC','pc-quick-chat-input')">Отправить</button>
            </div>
          </div>
        </div>

        <div class="panel">
          <div class="panel-head">
            <div class="panel-title">Правила блокировки сайтов и программ на ПК</div>
          </div>
          <div class="grid-4">
            <input id="pc-rule-name" class="text-input" placeholder="Название (например Roblox)" />
            <input id="pc-rule-domains" class="text-input mono" placeholder="Домены через запятую" />
            <input id="pc-rule-progs" class="text-input mono" placeholder="Программы (.exe) через запятую" />
            <div style="display:flex; gap:8px;">
              <input id="pc-rule-daily" type="number" class="text-input mono" placeholder="Лимит мин" style="width:110px;" />
              <button class="btn btn-sm btn-primary" onclick="addPcRule()" style="flex:1;">Добавить</button>
            </div>
          </div>
          ${entries.length ? `
            <div class="table-wrap">
              <table>
                <thead><tr><th>Правило</th><th>Домены / программы</th><th class="num-col">Сегодня</th><th>Статус</th><th class="num-col">Управление</th></tr></thead>
                <tbody>
                  ${entries.map(e => {
                    const isOn = !!(e.enabled || e.blocked);
                    const detail = [...(e.domains || []).slice(0, 3), ...(e.programs || []).slice(0, 2)].join(", ");
                    return `
                      <tr>
                        <td style="font-weight:600;">${esc(e.name)}</td>
                        <td class="mono" style="font-size:12px; color:var(--text-muted);">${esc(detail || "—")}</td>
                        <td class="num-col">${fmtDur(e.today_sec || 0)}</td>
                        <td><span class="${isOn ? "status-err" : "status-ok"}">${isOn ? "Заблокировано" : "Разрешено"}</span></td>
                        <td class="num-col">
                          <div style="display:inline-flex; gap:6px;">
                            <button class="btn btn-sm ${isOn ? "btn-success" : "btn-danger"}" onclick="sendPcCmd('/api/toggle', { name: '${esc(e.name)}' })">${isOn ? "Разрешить" : "Заблокировать"}</button>
                            <button class="btn btn-sm" onclick="sendPcCmd('/api/delete', { name: '${esc(e.name)}' })">Удалить</button>
                          </div>
                        </td>
                      </tr>
                    `;
                  }).join("")}
                </tbody>
              </table>
            </div>
          ` : `<div style="color:var(--text-muted); font-size:13px;">Список правил пуст.</div>`}
        </div>

        <div class="grid-2">
          <div class="panel">
            <div class="panel-title">Программы за сегодня (суммарно)</div>
            ${allProcs.length ? `
              <div class="table-wrap">
                <table>
                  <thead><tr><th>Пользователь</th><th>Программа</th><th class="num-col">Время</th></tr></thead>
                  <tbody>
                    ${allProcs.slice(0, 20).map(p => `
                      <tr>
                        <td style="color:var(--text-muted);">${esc(p.user)}</td>
                        <td class="mono" style="font-weight:500;">${esc(p.label)}</td>
                        <td class="num-col">${fmtDur(p.sec)}</td>
                      </tr>
                    `).join("")}
                  </tbody>
                </table>
              </div>
            ` : `<div style="color:var(--text-muted); font-size:13px;">За сегодня активность программ ещё не зафиксирована.</div>`}
          </div>

          <div class="panel">
            <div class="panel-head">
              <div class="panel-title">Окна и сайты за сегодня (без повторов)</div>
              ${allTitles.length > 12 ? `<button class="btn btn-sm" onclick="ui.pcShowAllTitles=!ui.pcShowAllTitles; renderView(true);">${ui.pcShowAllTitles ? "Свернуть" : "Показать все (" + allTitles.length + ")"}</button>` : ""}
            </div>
            ${allTitles.length ? `
              <div class="table-wrap">
                <table>
                  <thead><tr><th>Пользователь</th><th>Заголовок окна / страницы</th><th class="num-col">Суммарно</th></tr></thead>
                  <tbody>
                    ${(ui.pcShowAllTitles ? allTitles : allTitles.slice(0, 12)).map(t => `
                      <tr>
                        <td style="color:var(--text-muted); white-space:nowrap;">${esc(t.user)}</td>
                        <td>${esc(t.label)}</td>
                        <td class="num-col">${fmtDur(t.sec)}</td>
                      </tr>
                    `).join("")}
                  </tbody>
                </table>
              </div>
            ` : `<div style="color:var(--text-muted); font-size:13px;">За сегодня заголовки окон ещё не получены.</div>`}
          </div>
        </div>
      `;
    }

    function renderMap() {
      const ch = getActiveChild();
      const locs = (ui.snapshot && ui.snapshot.locations) || [];
      const lat = ch && ch.lastLocationLat ? Number(ch.lastLocationLat) : (locs[0] ? Number(locs[0].lat) : 0);
      const lng = ch && ch.lastLocationLng ? Number(ch.lastLocationLng) : (locs[0] ? Number(locs[0].lng) : 0);
      const addr = (ch && ch.lastLocationAddress) || (locs[0] && locs[0].address) || "Координаты ещё не получены";

      return `
        <div class="panel">
          <div class="panel-head">
            <div>
              <div class="panel-title">Местоположение телефона ребёнка</div>
              <div class="meta-line">
                <span>${esc(addr)}</span>
                ${lat || lng ? `<span class="sep">·</span><span class="mono">${lat.toFixed(5)}, ${lng.toFixed(5)}</span>` : ""}
              </div>
            </div>
            <button class="btn btn-sm btn-primary" onclick="sendPhoneCmd('MAP_VIEWING', '1')">Обновить GPS</button>
          </div>
          <div id="gps-map"></div>
        </div>

        <div class="panel">
          <div class="panel-title">История перемещений (локальный архив)</div>
          ${locs.length ? `
            <div class="table-wrap">
              <table>
                <thead><tr><th>Время</th><th>Адрес</th><th class="num-col">Координаты</th><th class="num-col">Скорость</th></tr></thead>
                <tbody>
                  ${locs.slice(0, 40).map(pt => `
                    <tr>
                      <td class="mono" style="color:var(--text-muted);">${fmtDateTime(pt.ts)}</td>
                      <td>${esc(pt.address || "—")}</td>
                      <td class="num-col">${Number(pt.lat).toFixed(5)}, ${Number(pt.lng).toFixed(5)}</td>
                      <td class="num-col">${Number(pt.speed_kmh || 0).toFixed(1)} км/ч</td>
                    </tr>
                  `).join("")}
                </tbody>
              </table>
            </div>
          ` : `<div style="color:var(--text-muted); font-size:13px;">Точек маршрута в архиве пока нет.</div>`}
        </div>
      `;
    }

    function initOrUpdateLeafletMap() {
      const el = document.getElementById("gps-map");
      if (!el || typeof L === "undefined") return;
      const ch = getActiveChild();
      const locs = (ui.snapshot && ui.snapshot.locations) || [];
      const lat = ch && ch.lastLocationLat ? Number(ch.lastLocationLat) : (locs[0] ? Number(locs[0].lat) : 55.7558);
      const lng = ch && ch.lastLocationLng ? Number(ch.lastLocationLng) : (locs[0] ? Number(locs[0].lng) : 37.6173);

      if (ui.leafletMap) {
        ui.leafletMap.remove();
        ui.leafletMap = null;
      }
      const map = L.map("gps-map").setView([lat, lng], (ch && ch.lastLocationLat) || locs.length ? 15 : 5);
      L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
        maxZoom: 19,
        attribution: "&copy; OpenStreetMap",
      }).addTo(map);

      if ((ch && ch.lastLocationLat) || locs.length) {
        L.marker([lat, lng]).addTo(map).bindPopup(esc((ch && ch.lastLocationAddress) || "Последняя точка")).openPopup();
        if (locs.length > 1) {
          const pts = locs.slice(0, 50).map(p => [Number(p.lat), Number(p.lng)]);
          L.polyline(pts, { color: "#38BDF8", weight: 3, opacity: 0.85 }).addTo(map);
        }
      }
      ui.leafletMap = map;
    }

    function renderChat() {
      const chats = (ui.snapshot && ui.snapshot.chats) || [];
      const pcOnline = !!(ui.snapshot && ui.snapshot.pcOnline);
      return `
        <div class="panel">
          <div class="panel-head">
            <div>
              <div class="panel-title">Семейный чат</div>
              <div class="meta-line">
                <span>Выберите адресата: телефон ребёнка, компьютер Windows или оба устройства сразу</span>
              </div>
            </div>
            <div class="segmented">
              <button class="seg-btn ${ui.chatTarget === "PHONE" ? "active" : ""}" onclick="ui.chatTarget='PHONE'; renderView(true);">Телефон</button>
              <button class="seg-btn ${ui.chatTarget === "PC" ? "active" : ""}" onclick="ui.chatTarget='PC'; renderView(true);">ПК ${pcOnline ? "· онлайн" : ""}</button>
              <button class="seg-btn ${ui.chatTarget === "BOTH" ? "active" : ""}" onclick="ui.chatTarget='BOTH'; renderView(true);">Оба</button>
            </div>
          </div>

          <div id="main-chat-log" class="chat-log" style="min-height:360px; max-height:520px;">
            ${chats.length ? chats.map(m => {
              const isParent = String(m.sender_role).toUpperCase() === "PARENT";
              const isPc = String(m.source).toUpperCase() === "PC" || String(m.sender_name || "").includes("ПК");
              return `
                <div class="chat-msg ${isParent ? "parent" : "child"}">
                  <div class="chat-sender">
                    ${esc(m.sender_name)}
                    ${isPc ? " · ПК" : " · Телефон"}
                    · <span class="mono">${fmtDateTime(m.ts)}</span>
                  </div>
                  <div class="chat-body">${esc(m.text)}</div>
                </div>
              `;
            }).join("") : `<div style="color:var(--text-muted); font-size:13px; padding:32px 0; text-align:center;">Сообщений пока нет. Напишите первое сообщение ниже.</div>`}
          </div>

          <div class="divider"></div>
          <div class="chat-compose">
            <input id="chat-input" class="text-input" placeholder="${ui.chatTarget === "PC" ? "Написать на компьютер ребёнка..." : ui.chatTarget === "BOTH" ? "Написать на телефон и ПК..." : "Написать на телефон ребёнка..."}" onkeydown="if(event.key==='Enter') sendChatMessage()" />
            <label style="display:flex; align-items:center; gap:6px; font-size:12.5px; color:var(--text-muted); white-space:nowrap; cursor:pointer;">
              <input id="chat-attention" type="checkbox" />
              Сигнал внимания
            </label>
            <button class="btn btn-primary" onclick="sendChatMessage()">Отправить</button>
          </div>
        </div>
      `;
    }

    function renderArchive() {
      const days = ui.archiveData.days || [];
      const rows = ui.archiveData.rows || [];
      let tableHtml = `<div style="color:var(--text-muted); font-size:13px;">Записей по выбранному фильтру нет.</div>`;

      if (rows.length) {
        if (ui.archiveKind === "pc") {
          tableHtml = `
            <div class="table-wrap">
              <table>
                <thead><tr><th>Дата</th><th>Пользователь</th><th>Тип</th><th>Программа / окно</th><th class="num-col">Суммарно</th></tr></thead>
                <tbody>
                  ${rows.map(r => `
                    <tr>
                      <td class="mono" style="color:var(--text-muted);">${esc(r.day)}</td>
                      <td>${esc(r.user_name)}</td>
                      <td style="color:var(--text-muted);">${r.kind === "proc" ? "Программа" : "Окно"}</td>
                      <td>${esc(r.label)}</td>
                      <td class="num-col">${fmtDur(r.seconds)}</td>
                    </tr>
                  `).join("")}
                </tbody>
              </table>
            </div>
          `;
        } else if (ui.archiveKind === "phone") {
          tableHtml = `
            <div class="table-wrap">
              <table>
                <thead><tr><th>Время</th><th>Ребёнок</th><th>Приложение</th><th>Пакет</th><th class="num-col">Длительность</th></tr></thead>
                <tbody>
                  ${rows.map(r => `
                    <tr>
                      <td class="mono" style="color:var(--text-muted);">${fmtDateTime(r.ts)}</td>
                      <td>${esc(r.child_name || ("#" + r.child_id))}</td>
                      <td style="font-weight:600;">${esc(r.app_name)}</td>
                      <td class="mono" style="font-size:12px; color:var(--text-muted);">${esc(r.package_name)}</td>
                      <td class="num-col">${fmtDur(r.duration_sec)}</td>
                    </tr>
                  `).join("")}
                </tbody>
              </table>
            </div>
          `;
        } else {
          tableHtml = `
            <div class="table-wrap">
              <table>
                <thead><tr><th>Время</th><th>Тип</th><th>Заголовок</th><th>Сообщение</th></tr></thead>
                <tbody>
                  ${rows.map(r => `
                    <tr>
                      <td class="mono" style="color:var(--text-muted);">${fmtDateTime(r.ts)}</td>
                      <td class="${String(r.alert_type).toUpperCase() === "SOS" ? "status-err" : ""}" style="font-weight:600;">${esc(r.alert_type)}</td>
                      <td style="font-weight:600;">${esc(r.title)}</td>
                      <td style="color:var(--text-muted);">${esc(r.message)}</td>
                    </tr>
                  `).join("")}
                </tbody>
              </table>
            </div>
          `;
        }
      }

      return `
        <div class="panel">
          <div class="panel-head">
            <div class="segmented">
              <button class="seg-btn ${ui.archiveKind === "pc" ? "active" : ""}" onclick="ui.archiveKind='pc'; loadArchive();">Активность ПК</button>
              <button class="seg-btn ${ui.archiveKind === "phone" ? "active" : ""}" onclick="ui.archiveKind='phone'; loadArchive();">Активность телефона</button>
              <button class="seg-btn ${ui.archiveKind === "alerts" ? "active" : ""}" onclick="ui.archiveKind='alerts'; loadArchive();">Тревоги и SOS</button>
            </div>
            <div class="row-actions">
              <select class="select-input mono" style="width:150px;" onchange="ui.archiveDay=this.value; loadArchive();">
                <option value="">Все даты</option>
                ${days.map(d => `<option value="${esc(d)}" ${ui.archiveDay === d ? "selected" : ""}>${esc(d)}</option>`).join("")}
              </select>
              <input class="text-input" style="width:220px;" placeholder="Поиск по архиву..." value="${esc(ui.archiveQuery)}" oninput="ui.archiveQuery=this.value; loadArchive();" />
              ${ui.archiveKind === "alerts" ? `<button class="btn btn-sm" onclick="markAlertsRead()">Прочитано</button>` : ""}
              <button class="btn btn-sm" onclick="exportArchive('csv')">Экспорт CSV</button>
              <button class="btn btn-sm" onclick="exportArchive('json')">Экспорт JSON</button>
            </div>
          </div>
          ${tableHtml}
        </div>
      `;
    }

    function renderSettings() {
      const c = (ui.snapshot && ui.snapshot.configPublic) || {};
      return `
        <div class="grid-2">
          <div class="panel">
            <div class="panel-title">Подключение к устройствам ребёнка</div>
            <div>
              <label class="field-label">Имя родителя в чате</label>
              <input id="cfg-parent-name" class="text-input" value="${esc(c.parent_name || "Родитель")}" />
            </div>
            <div class="grid-2">
              <div>
                <label class="field-label">Код привязки телефона</label>
                <input id="cfg-phone-code" class="text-input mono" value="${esc(c.phone_pairing_code || "838-597")}" />
              </div>
              <div>
                <label class="field-label">Код привязки ПК</label>
                <input id="cfg-pc-code" class="text-input mono" value="${esc(c.pc_pairing_code || "101-701")}" />
              </div>
            </div>
            <div class="grid-3">
              <div>
                <label class="field-label">Локальный IP ПК (Wi-Fi)</label>
                <input id="cfg-pc-ip" class="text-input mono" value="${esc(c.pc_lan_ip || "")}" placeholder="192.168.0.100" />
              </div>
              <div>
                <label class="field-label">Порт панели ПК</label>
                <input id="cfg-pc-port" type="number" class="text-input mono" value="${Number(c.pc_lan_port || 8765)}" />
              </div>
              <div>
                <label class="field-label">Пароль панели ПК</label>
                <input id="cfg-pc-pwd" type="password" class="text-input" placeholder="${c.pc_has_password ? "Сохранён (введите новый)" : "Пароль панели"}" />
              </div>
            </div>
            <div class="divider"></div>
            <div class="panel-title" style="font-size:14px;">Собственный VPS / Cloudflare Relay (опционально)</div>
            <label style="display:flex; align-items:center; gap:8px; font-size:13px; cursor:pointer;">
              <input id="cfg-vps-en" type="checkbox" ${c.vps_enabled ? "checked" : ""} />
              Включить подключение через VPS Relay
            </label>
            <div class="grid-3">
              <div>
                <label class="field-label">Адрес сервера (wss://...)</label>
                <input id="cfg-vps-host" class="text-input mono" value="${esc(c.vps_host || "")}" placeholder="relay.example.com/mqtt" />
              </div>
              <div>
                <label class="field-label">Порт</label>
                <input id="cfg-vps-port" type="number" class="text-input mono" value="${Number(c.vps_port || 443)}" />
              </div>
              <div>
                <label class="field-label">Пароль VPS</label>
                <input id="cfg-vps-pwd" type="password" class="text-input" placeholder="Не менять" />
              </div>
            </div>
          </div>

          <div class="panel">
            <div class="panel-title">Безопасность, трей и уведомления Windows</div>
            <label style="display:flex; align-items:center; gap:8px; font-size:13.5px; cursor:pointer;">
              <input id="cfg-pin-en" type="checkbox" ${c.pin_enabled ? "checked" : ""} />
              Запрашивать мастер PIN-код при входе в программу
            </label>
            <div>
              <label class="field-label">Сменить мастер PIN-код (оставьте пустым, чтобы не менять)</label>
              <input id="cfg-new-pin" type="password" class="text-input mono" placeholder="Новый PIN-код" />
            </div>
            <div class="divider"></div>
            <label style="display:flex; align-items:center; gap:8px; font-size:13.5px; cursor:pointer;">
              <input id="cfg-tray" type="checkbox" ${c.minimize_to_tray ? "checked" : ""} />
              Сворачивать в системный трей при закрытии окна
            </label>
            <label style="display:flex; align-items:center; gap:8px; font-size:13.5px; cursor:pointer;">
              <input id="cfg-notif" type="checkbox" ${c.notifications_enabled ? "checked" : ""} />
              Показывать уведомления Windows о сообщениях, тревогах и SOS
            </label>
            <label style="display:flex; align-items:center; gap:8px; font-size:13.5px; cursor:pointer;">
              <input id="cfg-sound" type="checkbox" ${c.sound_enabled ? "checked" : ""} />
              Звуковой сигнал при важных уведомлениях
            </label>
            <div class="divider"></div>
            <div class="row-actions">
              <button class="btn btn-primary" onclick="saveSettingsForm()">Сохранить все настройки</button>
            </div>
          </div>
        </div>
      `;
    }

    function renderView(forceFull = false) {
      if (!ui.snapshot) return;

      // Avoid destroying focused inputs during background refresh
      const activeTag = document.activeElement ? document.activeElement.tagName : "";
      if (!forceFull && (activeTag === "INPUT" || activeTag === "TEXTAREA" || activeTag === "SELECT")) {
        updateLiveImagesInPlace();
        return;
      }
      if (!forceFull && (ui.tab === "settings" || ui.tab === "map" || ui.tab === "archive") && ui.renderedTab === ui.tab) {
        return;
      }

      const root = document.getElementById("view-root");
      if (ui.tab === "overview") root.innerHTML = renderOverview();
      else if (ui.tab === "phone") root.innerHTML = renderPhone();
      else if (ui.tab === "pc") root.innerHTML = renderPc();
      else if (ui.tab === "map") {
        root.innerHTML = renderMap();
        setTimeout(initOrUpdateLeafletMap, 30);
      }
      else if (ui.tab === "chat") {
        root.innerHTML = renderChat();
        const cl = document.getElementById("main-chat-log");
        if (cl) cl.scrollTop = cl.scrollHeight;
      }
      else if (ui.tab === "archive") root.innerHTML = renderArchive();
      else if (ui.tab === "settings") root.innerHTML = renderSettings();
      ui.renderedTab = ui.tab;
    }

    function updateLiveImagesInPlace() {
      const ch = getActiveChild();
      const phImg = document.getElementById("phone-live-img");
      if (phImg && ch && ch.screenBase64) {
        const src = `data:image/jpeg;base64,${ch.screenBase64}`;
        if (phImg.src !== src) phImg.src = src;
      }
      const pc = ui.snapshot && ui.snapshot.pc;
      const pcImg = document.getElementById("pc-live-img");
      if (pcImg && pc && pc.screen_b64 && !(ui.pcStreamOn && pc.lan_online)) {
        const src = `data:${pc.screen_fmt || "image/jpeg"};base64,${pc.screen_b64}`;
        if (pcImg.src !== src) pcImg.src = src;
      }
      const fsModal = document.getElementById("fs-modal");
      if (fsModal && fsModal.classList.contains("open")) {
        const fsImg = document.getElementById("fs-img");
        if (ui.fsSource === "pc" && pc && pc.screen_b64 && !(ui.pcStreamOn && pc.lan_online)) {
          fsImg.src = `data:${pc.screen_fmt || "image/jpeg"};base64,${pc.screen_b64}`;
        } else if (ui.fsSource === "phone" && ch && ch.screenBase64) {
          fsImg.src = `data:image/jpeg;base64,${ch.screenBase64}`;
        }
      }
    }

    async function pollState() {
      try {
        const snap = await api("/api/state");
        ui.snapshot = snap;

        // First-load PIN gate
        if (snap.pinEnabled && !ui.pinUnlocked) {
          document.getElementById("pin-overlay").style.display = "flex";
        }
        document.getElementById("topbar-primary-btn").style.display = snap.pinEnabled ? "inline-flex" : "none";

        // Update topbar transport status
        document.getElementById("topbar-meta").textContent =
          `${snap.connected ? "Подключено" : "Ожидание"} · ${snap.transportLabel} · Совместимость v${snap.compatVersion}`;

        // Update unread alerts badge
        const badge = document.getElementById("nav-alerts-count");
        if (snap.unreadAlerts > 0) {
          badge.textContent = String(snap.unreadAlerts);
          badge.style.display = "inline";
        } else {
          badge.style.display = "none";
        }

        // Update child selector dropdown
        const sel = document.getElementById("child-select");
        const children = snap.children || [];
        if (children.length > 0) {
          if (!children.some(c => Number(c.id) === Number(ui.activeChildId))) {
            ui.activeChildId = Number(children[0].id);
          }
          const curOpts = Array.from(sel.options).map(o => o.value).join(",");
          const newOpts = children.map(c => String(c.id)).join(",");
          if (curOpts !== newOpts) {
            sel.innerHTML = children.map(c => `<option value="${c.id}" ${Number(c.id) === Number(ui.activeChildId) ? "selected" : ""}>${esc(c.name || "Ребёнок")} (${esc(c.pairingCode || c.id)})</option>`).join("");
          }
        }

        renderView(false);
        updateLiveImagesInPlace();
      } catch (e) {
        // transient local error
      }
    }

    // Keep PC P2P stream lease alive every 8s while watching on PC tab
    setInterval(() => {
      if (ui.tab === "pc" && ui.pcStreamOn && ui.snapshot && ui.snapshot.pc && !ui.snapshot.pc.lan_online) {
        const qMap = {
          ECONOMY: { w: 480, h: 270, quality: 45, fps: 4 },
          BALANCED: { w: 640, h: 360, quality: 52, fps: 5 },
          HD: { w: 1280, h: 720, quality: 68, fps: 5 },
        };
        const q = qMap[ui.pcQuality] || qMap.BALANCED;
        sendPcCmd("/api/stream_start", { ...q, duration: 30, binary: false }, true);
      }
    }, 8000);

    pollState();
    setInterval(pollState, 2200);
  </script>
</body>
</html>
"""


# =============================================================================
# 7. LOCAL EMBEDDED HTTP SERVER (BOUND STRICTLY TO 127.0.0.1)
# =============================================================================

class ReusableThreadingTCPServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
    allow_reuse_address = True
    daemon_threads = True


def make_http_handler(engine: ParentSyncEngine, session_token: str):
    rendered_html = PARENT_UI_HTML.replace("__SESSION_TOKEN__", session_token).encode("utf-8")

    class ParentApiHandler(http.server.BaseHTTPRequestHandler):
        def log_message(self, format, *args):
            pass

        def _check_auth(self, parsed_qs=None):
            tok = self.headers.get("X-Session-Token") or ""
            if not tok and parsed_qs:
                tok = (parsed_qs.get("token") or [""])[0]
            return tok == session_token

        def _send_json(self, code, obj):
            body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def do_GET(self):
            parsed = urllib.parse.urlparse(self.path)
            qs = urllib.parse.parse_qs(parsed.query)

            if parsed.path in ("/", "/index.html"):
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Content-Length", str(len(rendered_html)))
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                try:
                    self.wfile.write(rendered_html)
                except (BrokenPipeError, ConnectionResetError):
                    pass
                return

            if not self._check_auth(qs):
                return self._send_json(403, {"ok": False, "error": "forbidden"})

            if parsed.path == "/api/state":
                return self._send_json(200, engine.build_snapshot())

            if parsed.path == "/api/archive":
                kind = (qs.get("kind") or ["pc"])[0]
                day = (qs.get("day") or [""])[0]
                q = (qs.get("q") or [""])[0]
                return self._send_json(200, engine.archive.query_archive(kind=kind, day=day, query=q))

            if parsed.path == "/api/archive/export":
                kind = (qs.get("kind") or ["pc"])[0]
                day = (qs.get("day") or [""])[0]
                q = (qs.get("q") or [""])[0]
                fmt = (qs.get("fmt") or ["csv"])[0].lower()
                res = engine.archive.query_archive(kind=kind, day=day, query=q, limit=5000)
                rows = res.get("rows") or []
                if fmt == "json":
                    data = json.dumps(rows, ensure_ascii=False, indent=2).encode("utf-8")
                    ctype = "application/json; charset=utf-8"
                    ext = "json"
                else:
                    buf = io.StringIO()
                    if rows:
                        writer = csv.DictWriter(buf, fieldnames=list(rows[0].keys()))
                        writer.writeheader()
                        writer.writerows(rows)
                    data = ("\ufeff" + buf.getvalue()).encode("utf-8")
                    ctype = "text/csv; charset=utf-8"
                    ext = "csv"
                fname = f"kidsguard_archive_{kind}_{day or 'all'}.{ext}"
                self.send_response(200)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Disposition", f'attachment; filename="{fname}"')
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                try:
                    self.wfile.write(data)
                except (BrokenPipeError, ConnectionResetError):
                    pass
                return

            if parsed.path == "/api/pc/mjpeg":
                base = engine._pc_lan_base_url()
                if not base:
                    self.send_response(503)
                    self.end_headers()
                    return
                if not engine.pc_lan_cookie:
                    engine._pc_lan_login(base)
                try:
                    req = urllib.request.Request(f"{base}/api/stream", headers={"Cookie": engine.pc_lan_cookie})
                    with urllib.request.urlopen(req, timeout=8) as upstream:
                        self.send_response(200)
                        self.send_header("Content-Type", upstream.headers.get("Content-Type", "multipart/x-mixed-replace; boundary=kidsguardframe"))
                        self.send_header("Cache-Control", "no-store")
                        self.end_headers()
                        while True:
                            chunk = upstream.read(16384)
                            if not chunk:
                                break
                            self.wfile.write(chunk)
                            self.wfile.flush()
                except Exception:
                    pass
                return

            self._send_json(404, {"ok": False})

        def do_POST(self):
            parsed = urllib.parse.urlparse(self.path)
            if not self._check_auth():
                return self._send_json(403, {"ok": False, "error": "forbidden"})
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length).decode("utf-8", errors="ignore") if length > 0 else "{}"
            try:
                body = json.loads(raw)
            except Exception:
                body = {}

            if parsed.path == "/api/pin/verify":
                cfg = engine.get_config()
                ok = (not cfg.get("pin_enabled")) or (str(body.get("pin") or "") == str(cfg.get("master_pin") or "1234"))
                return self._send_json(200, {"ok": ok})

            if parsed.path == "/api/phone/cmd":
                res = engine.send_phone_cmd(
                    body.get("childId", 8),
                    body.get("command", ""),
                    body.get("payload", ""),
                    extra={
                        "pairingCode": body.get("pairingCode", ""),
                        "aliasIds": body.get("aliasIds") or [],
                        **(body.get("extra") or {}),
                    },
                )
                return self._send_json(200, res)

            if parsed.path == "/api/pc/cmd":
                res = engine.send_pc_cmd(body.get("action", "/api/sync"), body.get("data") or {})
                return self._send_json(200, res)

            if parsed.path == "/api/chat/send":
                res = engine.send_chat(
                    target=body.get("target", "PHONE"),
                    text=body.get("text", ""),
                    child_id=body.get("childId", 8),
                    pairing_code=body.get("pairingCode", ""),
                    alias_ids=body.get("aliasIds") or [],
                    attention=bool(body.get("attention", False)),
                )
                return self._send_json(200, res)

            if parsed.path == "/api/alerts/read":
                engine.archive.mark_alerts_read()
                return self._send_json(200, {"ok": True})

            if parsed.path == "/api/settings":
                engine.update_config(body)
                return self._send_json(200, {"ok": True})

            self._send_json(404, {"ok": False})

    return ParentApiHandler


# =============================================================================
# 8. NATIVE WINDOWS DESKTOP LAUNCHER (PYWEBVIEW -> EDGE/CHROME --APP -> BROWSER)
# =============================================================================

def _find_chromium_app_browser():
    if not IS_WINDOWS:
        for cand in ("chromium", "google-chrome", "microsoft-edge"):
            p = shutil.which(cand)
            if p:
                return p
        return None
    candidates = [
        os.path.join(os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)"), "Microsoft", "Edge", "Application", "msedge.exe"),
        os.path.join(os.environ.get("ProgramFiles", r"C:\Program Files"), "Microsoft", "Edge", "Application", "msedge.exe"),
        os.path.join(os.environ.get("ProgramFiles", r"C:\Program Files"), "Google", "Chrome", "Application", "chrome.exe"),
        os.path.join(os.environ.get("LocalAppData", ""), "Google", "Chrome", "Application", "chrome.exe"),
    ]
    for p in candidates:
        if p and os.path.exists(p):
            return p
    return None


def main():
    headless_mode = "--headless" in sys.argv
    port_override = 0
    for arg in sys.argv[1:]:
        if arg.startswith("--port="):
            try:
                port_override = int(arg.split("=", 1)[1])
            except Exception:
                pass

    session_token = secrets.token_hex(24)
    archive = LocalHistoryArchive(DB_FILE)

    window_ref = {"win": None, "exiting": False, "url": ""}

    def open_window():
        win = window_ref.get("win")
        if win is not None:
            try:
                win.show()
                win.restore()
            except Exception:
                pass
        elif window_ref.get("url"):
            browser = _find_chromium_app_browser()
            if browser:
                try:
                    subprocess.Popen([browser, f"--app={window_ref['url']}", "--window-size=1360,860"])
                    return
                except Exception:
                    pass
            webbrowser.open(window_ref["url"])

    tray = WinTrayManager(on_open=open_window)
    engine = ParentSyncEngine(archive, tray)

    def quick_lock_phone():
        snap = engine.build_snapshot()
        ch = (snap.get("children") or [None])[0]
        if ch:
            engine.send_phone_cmd(ch["id"], "LOCK", "", extra={"pairingCode": ch.get("pairingCode", ""), "aliasIds": ch.get("aliasIds") or [ch["id"]]})
            tray.notify(APP_NAME, "Команда блокировки телефона отправлена")

    def quick_toggle_pc():
        snap = engine.build_snapshot()
        pc = snap.get("pc") or {}
        fl = pc.get("full") or pc.get("full_lock") or {}
        locked = bool(fl.get("now") or fl.get("blocked"))
        engine.send_pc_cmd("/api/full_lock", {"lock_now": not locked})
        tray.notify(APP_NAME, "ПК разблокирован" if locked else "Команда блокировки ПК отправлена")

    def exit_app():
        window_ref["exiting"] = True
        engine.stop()
        tray.stop()
        win = window_ref.get("win")
        if win is not None:
            try:
                win.destroy()
            except Exception:
                pass
        os._exit(0)

    tray.on_lock_phone = quick_lock_phone
    tray.on_toggle_pc_lock = quick_toggle_pc
    tray.on_exit = exit_app

    handler_cls = make_http_handler(engine, session_token)
    httpd = ReusableThreadingTCPServer(("127.0.0.1", port_override), handler_cls)
    bound_port = httpd.server_address[1]
    url = f"http://127.0.0.1:{bound_port}/"
    window_ref["url"] = url

    threading.Thread(target=httpd.serve_forever, daemon=True, name="LocalUIHttp").start()
    engine.start()
    tray.start()

    if headless_mode:
        print(f"KidsGuard Parent headless ready at {url}", flush=True)
        try:
            while True:
                time.sleep(1)
        except KeyboardInterrupt:
            exit_app()
        return

    # 1. Preferred: Native Windows Desktop Window via pywebview (Edge WebView2)
    try:
        import webview

        win = webview.create_window(
            APP_NAME,
            url,
            width=1360,
            height=860,
            min_size=(1024, 680),
            background_color="#0B1120",
        )
        window_ref["win"] = win

        def on_closing():
            if window_ref["exiting"]:
                return True
            if IS_WINDOWS and engine.get_config().get("minimize_to_tray", True):
                try:
                    win.hide()
                    tray.notify(APP_NAME, "Программа продолжает работу в системном трее.", play_sound=False)
                    return False
                except Exception:
                    return True
            return True

        win.events.closing += on_closing
        webview.start(debug=False)
        exit_app()
        return
    except Exception:
        pass

    # 2. Fallback: Dedicated chromeless Edge/Chrome App Window
    browser = _find_chromium_app_browser()
    if browser:
        try:
            proc = subprocess.Popen([browser, f"--app={url}", "--window-size=1360,860"])
            proc.wait()
            if not (IS_WINDOWS and engine.get_config().get("minimize_to_tray", True)):
                exit_app()
            else:
                while True:
                    time.sleep(1)
            return
        except Exception:
            pass

    # 3. Last-resort fallback: Default system browser
    webbrowser.open(url)
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        exit_app()


if __name__ == "__main__":
    main()
