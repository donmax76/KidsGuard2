# -*- coding: utf-8 -*-
"""
Hosts Blocker v5 (один файл): мгновенная блокировка + лимиты времени + фоновая служба — блокировка сайтов и игр несколькими способами.

Способы (включаются отдельно для каждой записи):
  1. hosts      — домены -> 0.0.0.0 (IPv4) и :: (IPv6)
  2. браузер    — политики Chrome / Edge / Brave / Firefox: чёрный список URL
                  + принудительное отключение DNS-over-HTTPS (из-за него
                  браузер игнорирует hosts)
  3. файрвол    — правило Windows Firewall, блокирующее .exe (нужен полный путь)
  4. закрытие   — процесс убивается, как только запустится
                  (работает, пока программа Hosts Blocker открыта)
  5. без прокси — запрет прокси/расширений-VPN в браузерах (политики)
Файрвол умеет блокировать и по IP-подсетям (нужно для Telegram, который
ходит на зашитые IP, а не по доменам).

Запуск:  python hosts_blocker.py     (запросит права администратора)
"""
import base64
import csv
import ctypes
import datetime
import hashlib
import hmac
import http.server
import io
import ipaddress
import json
import math
import os
import re
import secrets
import select
import shutil
import socket
import struct
import subprocess
import sys
import threading
import time
import traceback
import zlib
try:
    from PIL import Image as _PILImage
except Exception:  # Pillow не обязателен — есть чистый PNG-резерв
    _PILImage = None
try:
    import tkinter as tk
    from tkinter import filedialog, messagebox, simpledialog, ttk
except ImportError:
    class _TkStub:
        Label = Tk = Frame = Toplevel = Entry = Button = Canvas = Text = Checkbutton = BooleanVar = PhotoImage = object
    tk = _TkStub()
    filedialog = messagebox = simpledialog = ttk = None



IS_WIN = os.name == "nt"

# Версия скрипта: по ней приложение/панель понимают, что на ПК действительно новый файл.
SCRIPT_VERSION = "5.3"
SCRIPT_BUILD = "2026-11-04"
if IS_WIN:
    import winreg

APP = "HostsBlocker"
HOSTS = r"C:\Windows\System32\drivers\etc\hosts" if IS_WIN else "/etc/hosts"
# Общее место для всех учётных записей: C:\ProgramData\HostsBlocker.
# Доступ закрывается для обычных пользователей (читать и менять могут только
# Администраторы и SYSTEM), поэтому ребёнок не увидит и не удалит конфиг.
if IS_WIN:
    CONF_DIR = os.path.join(os.environ.get("PROGRAMDATA") or r"C:\ProgramData", APP)
else:
    CONF_DIR = os.path.join(os.path.expanduser("~/.config"), APP)
CONF_FILE = os.path.join(CONF_DIR, "config.json")
# старое расположение (для переноса настроек из прошлых версий)
OLD_CONF_FILE = os.path.join(
    os.environ.get("APPDATA") or os.path.expanduser("~/.config"), APP, "config.json"
)

BEGIN = "# >>> HostsBlocker BEGIN >>>"
END = "# <<< HostsBlocker END <<<"
FW_NAME = "HostsBlocker"
NO_WINDOW = 0x08000000

BG = "#111827"
CARD = "#1f2937"
TEXT = "#f3f4f6"
MUTED = "#9ca3af"
ACCENT = "#6366f1"
GREEN = "#22c55e"
GRAY = "#4b5563"
RED = "#ef4444"

METHODS = {
    "hosts": "Файл hosts (IPv4 + IPv6)",
    "browser": "Политики браузеров (Chrome, Edge, Brave, Firefox) + отключить DoH",
    "firewall": "Брандмауэр Windows — блок .exe (нужен полный путь)",
    "kill": "Закрывать процесс (пока приложение запущено)",
    "noproxy": "Запретить прокси / VPN-расширения в браузерах",
}
METHOD_SHORT = {"hosts": "hosts", "browser": "браузер",
                "firewall": "файрвол", "kill": "закрытие",
                "noproxy": "без прокси"}


def default_methods():
    m = {k: True for k in METHODS}
    m["noproxy"] = False
    return m


DEFAULT_ENTRIES = [
    {"name": "TikTok", "enabled": False, "programs": [],
     "domains": ["tiktok.com", "m.tiktok.com", "vm.tiktok.com", "tiktokv.com",
                 "tiktokcdn.com", "musical.ly", "byteoversea.com", "ibytedtos.com"]},
    {"name": "Instagram", "enabled": False, "programs": [],
     "domains": ["instagram.com", "i.instagram.com", "graph.instagram.com",
                 "cdninstagram.com", "scontent.cdninstagram.com"]},
    {"name": "YouTube", "enabled": False, "programs": [],
     "domains": ["youtube.com", "m.youtube.com", "youtu.be",
                 "youtubei.googleapis.com", "ytimg.com", "googlevideo.com"]},
    {"name": "Genshin Impact", "enabled": False,
     "programs": ["GenshinImpact.exe", "YuanShen.exe"],
     "domains": ["hoyoverse.com", "genshin.hoyoverse.com",
                 "sg-public-api.hoyoverse.com", "hk4e-api-os.hoyoverse.com",
                 "sdk-os-static.hoyoverse.com", "log-upload-os.hoyoverse.com",
                 "mihoyo.com", "hoyolab.com", "yuanshen.com",
                 "dispatchosglobal.yuanshen.com", "osasiadispatch.yuanshen.com",
                 "oseurodispatch.yuanshen.com", "osusadispatch.yuanshen.com"]},
]

# Официальные подсети Telegram: https://core.telegram.org/resources/cidr.txt
NEW_ENTRIES_V2 = [
    {"name": "Telegram", "enabled": False,
     "programs": ["Telegram.exe"],
     "domains": ["telegram.org", "web.telegram.org", "core.telegram.org",
                 "t.me", "telegram.me", "telegra.ph", "telesco.pe",
                 "cdn-telegram.org", "tdesktop.com"],
     "ips": ["91.105.192.0/23", "91.108.4.0/22", "91.108.8.0/22",
             "91.108.12.0/22", "91.108.16.0/22", "91.108.20.0/22",
             "91.108.56.0/22", "149.154.160.0/20", "185.76.151.0/24",
             "2001:67c:4e8::/48", "2001:b28:f23c::/48", "2001:b28:f23d::/48",
             "2001:b28:f23f::/48", "2a0a:f280::/32"],
     "methods": {"hosts": True, "browser": True, "firewall": True,
                 "kill": True, "noproxy": False}},
    {"name": "VPN и прокси (клиенты)", "enabled": False,
     "programs": ["openvpn.exe", "openvpn-gui.exe", "wireguard.exe",
                  "nordvpn.exe", "expressvpn.exe", "protonvpn.exe",
                  "windscribe.exe", "psiphon3.exe", "tor.exe", "v2rayn.exe",
                  "clash.exe", "clash-verge.exe", "hiddify.exe", "outline.exe",
                  "cloudflarewarp.exe", "warp-svc.exe"],
     "domains": [], "ips": [],
     "methods": {"hosts": False, "browser": False, "firewall": True,
                 "kill": True, "noproxy": True}},
]
DEFAULT_ENTRIES += NEW_ENTRIES_V2
SEED_VERSION = 2


def normalize(entries):
    for e in entries:
        e.setdefault("enabled", False)
        e.setdefault("domains", [])
        e.setdefault("programs", [])
        e.setdefault("ips", [])
        m = default_methods()
        m.update(e.get("methods") or {})
        e["methods"] = m
        lim = default_limit()
        lim.update(e.get("limit") or {})
        e["limit"] = lim
        if e["name"] == "Genshin Impact" and not e["programs"]:
            e["programs"] = ["GenshinImpact.exe", "YuanShen.exe"]
    # Убираем дубли по имени (могли появиться при добавлении с ПК и с телефона):
    # иначе список «прыгает» и в Compose ломаются ключи элементов.
    seen = set()
    unique = []
    for e in entries:
        key = str(e.get("name", "")).strip().lower()
        if key and key in seen:
            continue
        seen.add(key)
        unique.append(e)
    entries[:] = unique
    return entries


# ---------- права администратора ----------
def is_admin():
    try:
        if IS_WIN:
            return bool(ctypes.windll.shell32.IsUserAnAdmin())
        return os.geteuid() == 0
    except Exception:
        return False


def relaunch_as_admin():
    if IS_WIN:
        if getattr(sys, "frozen", False):
            params = " ".join(f'"{a}"' for a in sys.argv[1:])
        else:
            params = " ".join(f'"{a}"' for a in [os.path.abspath(sys.argv[0])] + sys.argv[1:])
        ctypes.windll.shell32.ShellExecuteW(None, "runas", sys.executable, params, None, 1)
    else:
        os.execvp("sudo", ["sudo", sys.executable] + sys.argv)
    sys.exit(0)


# ---------- конфиг и пароль ----------
def hash_password(password, salt=None):
    salt = salt or secrets.token_bytes(16)
    h = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, 200_000)
    return salt.hex(), h.hex()


def check_password(password, salt_hex, hash_hex):
    _, h = hash_password(password, bytes.fromhex(salt_hex))
    return hmac.compare_digest(h, hash_hex)


_secured = False


def secure_conf_dir():
    """Оставляет доступ к папке конфига только Администраторам и SYSTEM."""
    global _secured
    if _secured or not IS_WIN:
        return
    run_hidden(["icacls", CONF_DIR, "/inheritance:r", "/grant:r",
                "*S-1-5-32-544:(OI)(CI)F", "*S-1-5-18:(OI)(CI)F"])
    _secured = True


_LAST_GOOD_CONFIG = None


def load_config():
    global _LAST_GOOD_CONFIG
    # 1) Основной файл. Если он существует, но временно недоступен (антивирус/другой
    # процесс), НЕ подменяем его старым конфигом из %APPDATA% — иначе список правил
    # «прыгает» между двумя версиями настроек.
    if os.path.exists(CONF_FILE):
        for _ in range(3):
            try:
                with open(CONF_FILE, "r", encoding="utf-8") as f:
                    cfg = json.load(f)
                _LAST_GOOD_CONFIG = cfg
                return cfg
            except Exception:
                time.sleep(0.05)
        if _LAST_GOOD_CONFIG is not None:
            return json.loads(json.dumps(_LAST_GOOD_CONFIG))
        return None
    # 2) Старое расположение — только если основного файла ещё нет (миграция).
    try:
        with open(OLD_CONF_FILE, "r", encoding="utf-8") as f:
            cfg = json.load(f)
        try:
            save_config(cfg)
        except Exception:
            pass
        _LAST_GOOD_CONFIG = cfg
        return cfg
    except Exception:
        return None


def save_config(cfg):
    global _LAST_GOOD_CONFIG
    os.makedirs(CONF_DIR, exist_ok=True)
    secure_conf_dir()
    tmp = CONF_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)
    # На Windows os.replace может временно упасть, если файл читает другой процесс.
    last_err = None
    for _ in range(6):
        try:
            os.replace(tmp, CONF_FILE)  # атомарно: конфиг никогда не окажется «битым»
            _LAST_GOOD_CONFIG = json.loads(json.dumps(cfg))
            return
        except PermissionError as ex:
            last_err = ex
            time.sleep(0.1)
    if last_err:
        raise last_err


_PC_ID_CACHE = ""
_PC_ID_DISABLED = False


def pc_id():
    """Стабильный случайный ID этого ПК: телефон отбрасывает пакеты чужих компьютеров
    с тем же кодом привязки (по умолчанию 101-701 таких много на публичном брокере)."""
    global _PC_ID_CACHE, _PC_ID_DISABLED
    if _PC_ID_DISABLED:
        return ""
    if _PC_ID_CACHE:
        return _PC_ID_CACHE
    cfg = load_config() or {}
    pid = str(cfg.get("pc_id") or "").strip()
    if not pid:
        pid = secrets.token_hex(8)
        cfg["pc_id"] = pid
        try:
            save_config(cfg)
        except Exception:
            # Конфиг недоступен на запись — не привязываем телефон к временному ID.
            _PC_ID_DISABLED = True
            return ""
    _PC_ID_CACHE = pid
    return pid


# ---------- разбор ввода ----------
def parse_domains(text):
    out = []
    for d in re.split(r"[\s,;]+", text.strip().lower()):
        d = re.sub(r"^https?://", "", d).split("/")[0].strip(".")
        if d and d not in out:
            out.append(d)
    return out


def parse_programs(text):
    out = []
    for line in text.splitlines():
        p = line.strip().strip('"')
        if p and p not in out:
            out.append(p)
    return out


def parse_ips(text):
    out = []
    for x in re.split(r"[\s,;]+", text.strip()):
        if not x:
            continue
        try:
            n = str(ipaddress.ip_network(x, strict=False))
        except ValueError:
            raise ValueError(f"Неверный IP/подсеть: {x}")
        if n not in out:
            out.append(n)
    return out


def expand_domains(domains):
    out = []
    for d in domains:
        for x in (d, "www." + d if d.count(".") == 1 else None):
            if x and x not in out:
                out.append(x)
    return out


def active(entries, method):
    return [e for e in entries if e["enabled"] and e["methods"].get(method)]


def run_hidden(cmd):
    return subprocess.run(cmd, capture_output=True,
                          creationflags=NO_WINDOW if IS_WIN else 0)


# ---------- способ 1: hosts ----------
def apply_hosts(entries):
    backup = HOSTS + ".hostsblocker.bak"
    if not os.path.exists(backup):
        shutil.copy2(HOSTS, backup)

    with open(HOSTS, "r", encoding="utf-8", errors="ignore") as f:
        lines = f.read().splitlines()

    kept, skip = [], False
    for line in lines:
        s = line.strip()
        if s == BEGIN:
            skip = True
            continue
        if s == END:
            skip = False
            continue
        if not skip:
            kept.append(line)
    while kept and not kept[-1].strip():
        kept.pop()

    block = []
    for e in active(entries, "hosts"):
        if not e["domains"]:
            continue
        block.append(f"# {e['name']}")
        for d in expand_domains(e["domains"]):
            block.append(f"0.0.0.0 {d}")
            block.append(f":: {d}")
    if block:
        kept += ["", BEGIN] + block + [END]

    with open(HOSTS, "w", encoding="utf-8", newline="\r\n" if IS_WIN else "\n") as f:
        f.write("\n".join(kept) + "\n")

    if IS_WIN:
        run_hidden(["ipconfig", "/flushdns"])


# ---------- способ 2: политики браузеров ----------
CHROMIUM_KEYS = [
    r"SOFTWARE\Policies\Google\Chrome",
    r"SOFTWARE\Policies\Microsoft\Edge",
    r"SOFTWARE\Policies\BraveSoftware\Brave",
]
FIREFOX_KEY = r"SOFTWARE\Policies\Mozilla\Firefox"
ACCESS = lambda: winreg.KEY_SET_VALUE | winreg.KEY_WOW64_64KEY


def _reg_set(path, name, value, kind=None):
    kind = kind or winreg.REG_SZ
    with winreg.CreateKeyEx(winreg.HKEY_LOCAL_MACHINE, path, 0, ACCESS()) as k:
        winreg.SetValueEx(k, name, 0, kind, value)


def _reg_del_value(path, name):
    try:
        with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, path, 0, ACCESS()) as k:
            winreg.DeleteValue(k, name)
    except FileNotFoundError:
        pass


def _reg_del_key(path):
    try:
        winreg.DeleteKeyEx(winreg.HKEY_LOCAL_MACHINE, path, winreg.KEY_WOW64_64KEY, 0)
    except FileNotFoundError:
        pass


def apply_browser_policies(entries):
    if not IS_WIN:
        return
    doh_off = bool(active(entries, "hosts") or active(entries, "browser"))
    noproxy = bool(active(entries, "noproxy"))
    domains = []
    for e in active(entries, "browser"):
        for d in e["domains"]:
            if d not in domains:
                domains.append(d)

    for base in CHROMIUM_KEYS:
        _reg_del_key(base + r"\URLBlocklist")
        if doh_off:
            _reg_set(base, "DnsOverHttpsMode", "off")
        else:
            _reg_del_value(base, "DnsOverHttpsMode")
        for i, d in enumerate(domains, 1):
            _reg_set(base + r"\URLBlocklist", str(i), d)
        if noproxy:
            _reg_set(base, "ProxyMode", "direct")
        else:
            _reg_del_value(base, "ProxyMode")

    # Firefox
    _reg_del_key(FIREFOX_KEY + r"\WebsiteFilter\Block")
    _reg_del_key(FIREFOX_KEY + r"\DNSOverHTTPS")
    if doh_off:
        _reg_set(FIREFOX_KEY + r"\DNSOverHTTPS", "Enabled", 0, winreg.REG_DWORD)
        _reg_set(FIREFOX_KEY + r"\DNSOverHTTPS", "Locked", 1, winreg.REG_DWORD)
    for i, d in enumerate(domains, 1):
        _reg_set(FIREFOX_KEY + r"\WebsiteFilter\Block", str(i), f"*://*.{d}/*")
    _reg_del_key(FIREFOX_KEY + r"\Proxy")
    if noproxy:
        _reg_set(FIREFOX_KEY + r"\Proxy", "Mode", "none")
        _reg_set(FIREFOX_KEY + r"\Proxy", "Locked", 1, winreg.REG_DWORD)


# ---------- способ 3: брандмауэр ----------
def apply_firewall(entries):
    if not IS_WIN:
        return
    run_hidden(["netsh", "advfirewall", "firewall", "delete", "rule", f"name={FW_NAME}"])
    failed = []
    for e in active(entries, "firewall"):
        v4 = [i for i in e["ips"] if ":" not in i]
        v6 = [i for i in e["ips"] if ":" in i]
        for group in (v4, v6):
            if not group:
                continue
            for direction in ("out", "in"):
                r = run_hidden(["netsh", "advfirewall", "firewall", "add", "rule",
                                f"name={FW_NAME}", f"dir={direction}", "action=block",
                                "remoteip=" + ",".join(group), "enable=yes"])
                if r.returncode != 0:
                    failed.append(f"{e['name']} (IP)")
        for p in e["programs"]:
            if not os.path.isabs(p):
                continue  # без полного пути правило создать нельзя
            for direction in ("out", "in"):
                r = run_hidden(["netsh", "advfirewall", "firewall", "add", "rule",
                                f"name={FW_NAME}", f"dir={direction}", "action=block",
                                f"program={p}", "enable=yes"])
                if r.returncode != 0:
                    failed.append(p)
    if failed:
        raise RuntimeError("не удалось создать правило для: " + ", ".join(set(failed)))


# ---------- способ 4: закрытие процессов ----------
def kill_processes(names):
    try:
        out = run_hidden(["tasklist", "/FO", "CSV", "/NH"]).stdout.decode("utf-8", "ignore")
        running = {row[0].lower() for row in csv.reader(out.splitlines()) if row}
        for n in names & running:
            run_hidden(["taskkill", "/F", "/T", "/IM", n])
    except Exception:
        pass


# ====================== ЧЁТКОСТЬ (DPI) ======================
SCALE = 1.0


def S(v):
    """Масштабирует пиксели под DPI экрана."""
    return int(round(v * SCALE))


def enable_dpi():
    """Без этого Windows растягивает окно как картинку — отсюда «мутность»."""
    if not IS_WIN:
        return
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(1)
    except Exception:
        try:
            ctypes.windll.user32.SetProcessDPIAware()
        except Exception:
            pass


# ====================== ЛОГОТИПЫ ======================
# Картинки встроены в файл (см. ASSETS внизу) — сторонние библиотеки не нужны.
BRAND_BY_DOMAIN = {
    "youtube.com": "youtube", "youtu.be": "youtube", "instagram.com": "instagram",
    "tiktok.com": "tiktok", "telegram.org": "telegram", "t.me": "telegram",
    "hoyoverse.com": "genshin", "yuanshen.com": "genshin",
}
BRAND_DOMAINS = {  # для «📊 Активность»: угадать домены по заголовку окна
    "youtube": ["youtube.com", "youtu.be"], "instagram": ["instagram.com"],
    "tiktok": ["tiktok.com"], "telegram": ["telegram.org", "t.me"],
    "genshin": ["hoyoverse.com", "yuanshen.com"],
}
BRAND_BY_NAME = {
    "youtube": "youtube", "instagram": "instagram", "tiktok": "tiktok",
    "telegram": "telegram", "genshin impact": "genshin", "genshin": "genshin",
    "vpn и прокси (клиенты)": "vpn",
}


def brand_of(e):
    b = BRAND_BY_NAME.get(e["name"].strip().lower())
    if b:
        return b
    for d in e["domains"]:
        for dom, key in BRAND_BY_DOMAIN.items():
            if d == dom or d.endswith("." + dom):
                return key
    return None


def exe_pixels(path, px):
    """Иконка .exe из Windows в виде строки для PhotoImage.put. None при неудаче."""
    if not IS_WIN:
        return None
    try:
        from ctypes import wintypes
        u32, g32 = ctypes.windll.user32, ctypes.windll.gdi32
        P = ctypes.c_void_p
        u32.PrivateExtractIconsW.argtypes = [
            wintypes.LPCWSTR, ctypes.c_int, ctypes.c_int, ctypes.c_int,
            ctypes.POINTER(P), ctypes.POINTER(wintypes.UINT),
            wintypes.UINT, wintypes.UINT]
        u32.PrivateExtractIconsW.restype = wintypes.UINT
        u32.DrawIconEx.argtypes = [P, ctypes.c_int, ctypes.c_int, P, ctypes.c_int,
                                   ctypes.c_int, wintypes.UINT, P, wintypes.UINT]
        u32.DestroyIcon.argtypes = [P]
        g32.CreateCompatibleDC.argtypes = [P]
        g32.CreateCompatibleDC.restype = P
        g32.CreateDIBSection.argtypes = [P, P, wintypes.UINT, ctypes.POINTER(P),
                                         P, wintypes.DWORD]
        g32.CreateDIBSection.restype = P
        g32.SelectObject.argtypes = [P, P]
        g32.SelectObject.restype = P
        g32.DeleteObject.argtypes = [P]
        g32.DeleteDC.argtypes = [P]

        class BIH(ctypes.Structure):
            _fields_ = [("biSize", wintypes.DWORD), ("biWidth", wintypes.LONG),
                        ("biHeight", wintypes.LONG), ("biPlanes", wintypes.WORD),
                        ("biBitCount", wintypes.WORD), ("biCompression", wintypes.DWORD),
                        ("biSizeImage", wintypes.DWORD), ("biXPels", wintypes.LONG),
                        ("biYPels", wintypes.LONG), ("biClrUsed", wintypes.DWORD),
                        ("biClrImportant", wintypes.DWORD)]

        hicon, iid = P(), wintypes.UINT()
        n = u32.PrivateExtractIconsW(path, 0, px, px, ctypes.byref(hicon),
                                     ctypes.byref(iid), 1, 0)
        if n < 1 or not hicon.value:
            return None
        bi = BIH(ctypes.sizeof(BIH), px, -px, 1, 32, 0, 0, 0, 0, 0, 0)
        hdc = g32.CreateCompatibleDC(None)
        bits = P()
        bmp = g32.CreateDIBSection(hdc, ctypes.byref(bi), 0, ctypes.byref(bits), None, 0)
        old = g32.SelectObject(hdc, bmp)
        size = px * px * 4
        # фон = цвет карточки (#1f2937 в BGRA), иконка рисуется поверх
        ctypes.memmove(bits.value, bytes([0x37, 0x29, 0x1F, 0xFF]) * (px * px), size)
        u32.DrawIconEx(hdc, 0, 0, hicon, px, px, 0, None, 3)
        data = ctypes.string_at(bits.value, size)
        g32.SelectObject(hdc, old)
        g32.DeleteObject(bmp)
        g32.DeleteDC(hdc)
        u32.DestroyIcon(hicon)
        rows = []
        for y in range(px):
            r = data[y * px * 4:(y + 1) * px * 4]
            rows.append("{" + " ".join("#%02x%02x%02x" % (r[i + 2], r[i + 1], r[i])
                                       for i in range(0, px * 4, 4)) + "}")
        return " ".join(rows)
    except Exception:
        return None


class Icons:
    """Подбирает логотип записи: .exe → известный бренд → значок сайта → буква."""

    def __init__(self, app):
        self.app = app
        self.tried = set()

    def widget(self, parent, e, px):
        app = self.app
        for p in e["programs"]:
            if os.path.isabs(p) and os.path.exists(p):
                data = exe_pixels(p, px)
                if data:
                    ph = tk.PhotoImage(width=px, height=px)
                    ph.put(data)
                    app.keep.append(ph)
                    return tk.Label(parent, image=ph, bg=CARD, bd=0)
        b = brand_of(e)
        if b:
            return tk.Label(parent, image=app.img(b, px), bg=CARD, bd=0)
        if e["domains"]:
            ph = self._favicon(e["domains"][0], px)
            if ph:
                fr = tk.Frame(parent, bg="white", width=px, height=px)
                fr.pack_propagate(False)
                tk.Label(fr, image=ph, bg="white", bd=0).pack(expand=True)
                return fr
            self._fetch(e["domains"][0], px)
        tile = app.img("tile%d" % (sum(map(ord, e["name"])) % 7), px)
        return tk.Label(parent, image=tile, bg=CARD, bd=0,
                        text=(e["name"].strip()[:1] or "?").upper(), compound="center",
                        fg="white", font=("Segoe UI", -int(px * 0.46), "bold"))

    def _path(self, domain):
        return os.path.join(CONF_DIR, "icons", domain + ".png")

    def _favicon(self, domain, px):
        try:
            ph = tk.PhotoImage(file=self._path(domain))
            if ph.width() < 24:
                return None
            k = -(-ph.width() // int(px * 0.9))  # потолок: уменьшаем целым шагом
            if k > 1:
                ph = ph.subsample(k)
            self.app.keep.append(ph)
            return ph
        except Exception:
            return None

    def _fetch(self, domain, px):
        if domain in self.tried:
            return
        self.tried.add(domain)
        sz = 64 if px >= 56 else 32

        def work():
            try:
                import urllib.request
                url = f"https://www.google.com/s2/favicons?domain={domain}&sz={sz}"
                req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
                data = urllib.request.urlopen(req, timeout=6).read()
                os.makedirs(os.path.join(CONF_DIR, "icons"), exist_ok=True)
                with open(self._path(domain), "wb") as f:
                    f.write(data)
                self.app.after(0, self.app.render)
            except Exception:
                pass

        threading.Thread(target=work, daemon=True).start()


# ====================== ТУМБЛЕР ======================
class Switch(tk.Label):
    def __init__(self, master, value, command, app, bg=CARD):
        self.imgs = {True: app.img("sw_on", S(48)), False: app.img("sw_off", S(48))}
        super().__init__(master, image=self.imgs[value], bg=bg, bd=0, cursor="hand2")
        self.value = value
        self.command = command
        self.bind("<Button-1>", self.toggle)

    def toggle(self, _event=None):
        new = not self.value
        if self.command(new) is not False:
            self.value = new
            self.config(image=self.imgs[new])


# ====================== ПРИЛОЖЕНИЕ ======================
class App(tk.Tk):
    def __init__(self):
        super().__init__()
        global SCALE
        SCALE = max(1.0, self.winfo_fpixels("1i") / 96.0)
        self.tk.call("tk", "scaling", SCALE * 96 / 72)
        self.title("Hosts Blocker")
        self.geometry(f"{S(720)}x{S(680)}")
        self.minsize(S(640), S(480))
        self.configure(bg=BG)
        self.keep = []
        self.icons = Icons(self)
        try:
            self.app_icon = self.img("app", 64)
            self.iconphoto(True, self.app_icon)
        except Exception:
            pass

        st = ttk.Style(self)
        st.theme_use("clam")
        st.configure("Vertical.TScrollbar", background=GRAY, troughcolor=BG,
                     bordercolor=BG, arrowcolor=TEXT, lightcolor=GRAY, darkcolor=GRAY)

        self.cfg = load_config() or {"salt": None, "hash": None, "seed": SEED_VERSION,
                                     "web": {"enabled": True, "port": 8765, "pairing_code": "101-701"},
                                     "entries": json.loads(json.dumps(DEFAULT_ENTRIES))}
        self.cfg.setdefault("web", {"enabled": True, "port": 8765, "pairing_code": "101-701"})
        self.cfg["web"].setdefault("enabled", True)
        self.cfg["web"].setdefault("port", 8765)
        self.cfg["web"].setdefault("pairing_code", "101-701")
        normalize(self.cfg["entries"])
        if self.cfg.get("seed", 1) < SEED_VERSION:
            have = {e["name"] for e in self.cfg["entries"]}
            for e in NEW_ENTRIES_V2:
                if e["name"] not in have:
                    self.cfg["entries"].append(json.loads(json.dumps(e)))
            normalize(self.cfg["entries"])
            self.cfg["seed"] = SEED_VERSION
            if self.cfg.get("hash"):
                save_config(self.cfg)
        self.frame = None
        self.kill_started = False
        if self.cfg.get("hash"):
            self.show_login()
        else:
            self.show_setup()

    def img(self, key, px):
        """PhotoImage из встроенного набора: берётся ближайший готовый размер."""
        sizes = ASSETS[key]
        best = min(sizes, key=lambda s: abs(s - px))
        ph = tk.PhotoImage(data=sizes[best])
        self.keep.append(ph)
        return ph

    def switch_frame(self, frame):
        if self.frame is not None:
            self.frame.destroy()
        self.frame = frame
        self.frame.pack(fill="both", expand=True)

    def button(self, parent, text, cmd, bg=ACCENT, fg="white", **kw):
        return tk.Button(parent, text=text, command=cmd, bg=bg, fg=fg,
                         activebackground=bg, activeforeground=fg, relief="flat",
                         bd=0, padx=S(16), pady=S(8), font=("Segoe UI", 10, "bold"),
                         cursor="hand2", **kw)

    # --- вход ---
    def password_form(self, title, subtitle, fields, button_text, on_submit):
        self.keep = []
        f = tk.Frame(self, bg=BG)
        box = tk.Frame(f, bg=CARD, padx=S(34), pady=S(30))
        box.place(relx=0.5, rely=0.45, anchor="center")
        tk.Label(box, image=self.img("app72", S(72)), bg=CARD).pack()
        tk.Label(box, text=title, bg=CARD, fg=TEXT,
                 font=("Segoe UI", 16, "bold")).pack(pady=(S(8), S(2)))
        tk.Label(box, text=subtitle, bg=CARD, fg=MUTED,
                 font=("Segoe UI", 9)).pack(pady=(0, S(16)))
        entries = []
        for label in fields:
            tk.Label(box, text=label, bg=CARD, fg=MUTED, anchor="w",
                     font=("Segoe UI", 9)).pack(fill="x")
            e = tk.Entry(box, show="•", width=26, bg=BG, fg=TEXT, insertbackground=TEXT,
                         relief="flat", font=("Segoe UI", 12))
            e.pack(fill="x", ipady=S(7), pady=(S(2), S(10)))
            entries.append(e)
        entries[0].focus_set()
        submit = lambda _e=None: on_submit(*[e.get() for e in entries])
        for e in entries:
            e.bind("<Return>", submit)
        self.button(box, button_text, submit).pack(fill="x", pady=(S(6), 0))
        self.switch_frame(f)

    def show_setup(self):
        def submit(p1, p2):
            if len(p1) < 4:
                return messagebox.showwarning("Пароль", "Минимум 4 символа.")
            if p1 != p2:
                return messagebox.showwarning("Пароль", "Пароли не совпадают.")
            self.cfg["salt"], self.cfg["hash"] = hash_password(p1)
            save_config(self.cfg)
            self.show_main()

        self.password_form("Создайте пароль", "Он будет запрашиваться при каждом входе",
                           ["Новый пароль", "Повторите пароль"], "Сохранить и войти", submit)

    def show_login(self):
        def submit(p):
            if check_password(p, self.cfg["salt"], self.cfg["hash"]):
                self.show_main()
            else:
                messagebox.showerror("Ошибка", "Неверный пароль.")

        self.password_form("Hosts Blocker", "Введите пароль для входа",
                           ["Пароль"], "Войти", submit)

    # --- главный экран ---
    def show_main(self):
        f = tk.Frame(self, bg=BG)
        head = tk.Frame(f, bg=BG)
        head.pack(fill="x", padx=S(20), pady=(S(18), S(10)))
        self.keep = []
        tk.Label(head, image=self.img("app", S(40)), bg=BG).pack(side="left", padx=(0, S(10)))
        tk.Label(head, text="Блокировка", bg=BG, fg=TEXT,
                 font=("Segoe UI", 17, "bold")).pack(side="left")
        self.button(head, "🔑 Пароль", self.change_password,
                    bg=CARD, fg=TEXT).pack(side="right", padx=(S(8), 0))
        self.button(head, "⚙ Служба", self.service_menu,
                    bg=CARD, fg=TEXT).pack(side="right", padx=(S(8), 0))
        self.button(head, "📱 Панель", self.panel_dialog,
                    bg=CARD, fg=TEXT).pack(side="right", padx=(S(8), 0))
        self.button(head, "📊 Активность", self.activity_dialog,
                    bg=CARD, fg=TEXT).pack(side="right", padx=(S(8), 0))
        self.lock_btn = self.button(head, "🔒 Блокировка ПК", self.full_lock_dialog,
                                    bg="#7f1d1d", fg="white")
        self.lock_btn.pack(side="right", padx=(S(8), 0))
        self.button(head, "＋ Добавить", lambda: self.edit_dialog(None)).pack(side="right")
        self.head_keep = list(self.keep)

        cols = tk.Frame(f, bg=BG)
        cols.pack(fill="x", padx=S(28))
        tk.Label(cols, text="САЙТ / ПРОГРАММА", bg=BG, fg=MUTED,
                 font=("Segoe UI", 8, "bold")).pack(side="left")
        tk.Label(cols, text="СТАТУС", bg=BG, fg=MUTED,
                 font=("Segoe UI", 8, "bold")).pack(side="right", padx=(0, S(92)))

        wrap = tk.Frame(f, bg=BG)
        wrap.pack(fill="both", expand=True, padx=S(20), pady=(S(6), S(6)))
        self.canvas = tk.Canvas(wrap, bg=BG, highlightthickness=0)
        sb = ttk.Scrollbar(wrap, orient="vertical", command=self.canvas.yview)
        self.list_frame = tk.Frame(self.canvas, bg=BG)
        self.list_frame.bind("<Configure>", lambda e: self.canvas.configure(
            scrollregion=self.canvas.bbox("all")))
        self.win = self.canvas.create_window((0, 0), window=self.list_frame, anchor="nw")
        self.canvas.bind("<Configure>",
                         lambda e: self.canvas.itemconfigure(self.win, width=e.width))
        self.canvas.configure(yscrollcommand=sb.set)
        self.canvas.pack(side="left", fill="both", expand=True)
        sb.pack(side="right", fill="y")
        self.bind_all("<MouseWheel>",
                      lambda e: self.canvas.yview_scroll(int(-e.delta / 120), "units"))

        self.status = tk.Label(f, bg=BG, fg=MUTED, font=("Segoe UI", 9), anchor="w")
        self.status.pack(fill="x", padx=S(22), pady=(0, S(14)))
        self.switch_frame(f)
        self.render()

        if not self.kill_started:
            self.kill_started = True
            self.kill_tick()
            self.ensure_background()
            self.after(15000, self.refresh_loop)

    def render(self):
        self.keep = list(getattr(self, "head_keep", []))
        for w in self.list_frame.winfo_children():
            w.destroy()
        entries = self.cfg["entries"]
        usage = load_usage()
        if not entries:
            tk.Label(self.list_frame, text="Список пуст. Нажмите «＋ Добавить».",
                     bg=BG, fg=MUTED, font=("Segoe UI", 10)).pack(pady=S(30))
        for i, e in enumerate(entries):
            row = tk.Frame(self.list_frame, bg=CARD, padx=S(14), pady=S(12))
            row.pack(fill="x", pady=S(4))
            self.icons.widget(row, e, S(44)).pack(side="left", padx=(0, S(14)))
            info = tk.Frame(row, bg=CARD)
            info.pack(side="left", fill="x", expand=True)
            tk.Label(info, text=e["name"], bg=CARD, fg=TEXT, anchor="w",
                     font=("Segoe UI", 12, "bold")).pack(fill="x")
            used = ", ".join(METHOD_SHORT[k] for k, v in e["methods"].items() if v) or "—"
            tk.Label(info, text=f"доменов: {len(e['domains'])} · программ: "
                                f"{len(e['programs'])} · IP: {len(e['ips'])} · {used}",
                     bg=CARD, fg=MUTED, anchor="w", font=("Segoe UI", 8)).pack(fill="x")
            lt = limit_text(e, usage)
            if lt:
                tk.Label(info, text=lt, bg=CARD, fg="#f59e0b", anchor="w",
                         font=("Segoe UI", 8, "bold")).pack(fill="x")
            tk.Button(row, text="✕", command=lambda i=i: self.delete_entry(i),
                      bg=CARD, fg=MUTED, activebackground=CARD, activeforeground=RED,
                      relief="flat", bd=0, cursor="hand2",
                      font=("Segoe UI", 12)).pack(side="right", padx=(S(8), 0))
            tk.Button(row, text="✎", command=lambda i=i: self.edit_dialog(i),
                      bg=CARD, fg=MUTED, activebackground=CARD, activeforeground=TEXT,
                      relief="flat", bd=0, cursor="hand2",
                      font=("Segoe UI", 13)).pack(side="right", padx=(S(6), 0))
            Switch(row, e["enabled"], lambda v, i=i: self.set_enabled(i, v), self).pack(
                side="right", padx=(S(10), S(8)))
        self.update_status()

    def update_status(self):
        entries = self.cfg["entries"]
        n = sum(1 for e in entries if e["enabled"])
        self.status.config(text=f"Заблокировано: {n} из {len(entries)}")

    # --- применение ---
    def commit(self, silent=False):
        errors = apply_all(self.cfg["entries"], load_usage())
        if errors:
            if not silent:
                messagebox.showerror("Ошибка", "\n".join(errors))
            return False
        save_config(self.cfg)
        return True

    def set_enabled(self, idx, value):
        old = self.cfg["entries"][idx]["enabled"]
        self.cfg["entries"][idx]["enabled"] = value
        if not self.commit():
            self.cfg["entries"][idx]["enabled"] = old
            self.commit(silent=True)
            return False
        self.after(50, self.update_status)

    def delete_entry(self, idx):
        name = self.cfg["entries"][idx]["name"]
        if not messagebox.askyesno("Удалить", f"Удалить «{name}» из списка?"):
            return
        removed = self.cfg["entries"].pop(idx)
        if not self.commit():
            self.cfg["entries"].insert(idx, removed)
            self.commit(silent=True)
            return
        self.render()

    def ensure_background(self):
        """Ставит движок (SYSTEM) и агента: блокировки и лимиты работают без окна."""
        if not IS_WIN:
            return

        def work():
            try:
                errs = install_background()
            except Exception as ex:
                errs = [str(ex)]
            if errs:
                self.after(0, lambda: messagebox.showwarning(
                    "Служба", "Не удалось настроить фоновую работу:\n" + "\n".join(errs)))

        threading.Thread(target=work, daemon=True).start()

    def service_menu(self):
        r = messagebox.askyesnocancel(
            "Фоновая служба",
            "Служба работает в фоне, даже когда окно закрыто: блокирует в реальном "
            "времени и считает лимиты.\n\nДа — переустановить и запустить\n"
            "Нет — удалить службу\nОтмена — ничего не менять")
        if r is True:
            self.ensure_background()
        elif r is False:
            uninstall_background()
            messagebox.showinfo("Служба", "Служба удалена. Блокировки в hosts, политиках "
                                          "и брандмауэре остаются до выключения записей.")

    def refresh_loop(self):
        try:
            if not any(isinstance(w, tk.Toplevel) for w in self.winfo_children()):
                try:
                    mt = os.path.getmtime(CONF_FILE)
                except OSError:
                    mt = 0
                if mt != getattr(self, "seen_mtime", None):  # изменили с телефона
                    new = load_config()
                    if new:
                        normalize(new["entries"])
                        self.cfg = new
                    self.seen_mtime = mt
                self.render()
        except Exception:
            pass
        self.after(15000, self.refresh_loop)

    def panel_dialog(self):
        web = self.cfg.get("web") or {"enabled": True, "port": 8765, "pairing_code": "101-701"}
        dlg = tk.Toplevel(self)
        dlg.title("Веб-панель и KidsGuard Pro")
        dlg.configure(bg=CARD)
        dlg.geometry(f"{S(540)}x{S(480)}")
        dlg.transient(self)
        dlg.grab_set()
        pad = {"padx": S(22)}
        tk.Label(dlg, text="📱 Управление с телефона (KidsGuard Pro и Веб)", bg=CARD, fg=TEXT,
                 font=("Segoe UI", 13, "bold")).pack(anchor="w", pady=(S(18), S(6)), **pad)
        tk.Label(dlg, text="Управление доступно из приложения KidsGuard Pro (по домашнему Wi-Fi "
                           "и удалённо через P2P по коду привязки) или через браузер телефона. "
                           "Для работы должна быть установлена служба (⚙ Служба).",
                 bg=CARD, fg=MUTED, wraplength=S(480), justify="left",
                 font=("Segoe UI", 9)).pack(anchor="w", **pad)
        var = tk.BooleanVar(value=bool(web.get("enabled", True)))
        tk.Checkbutton(dlg, text="Включить удалённое управление и веб-панель", variable=var, bg=CARD, fg=TEXT,
                       selectcolor=BG, activebackground=CARD, activeforeground=TEXT,
                       font=("Segoe UI", 10)).pack(anchor="w", pady=S(10), **pad)
        row = tk.Frame(dlg, bg=CARD)
        row.pack(fill="x", **pad)
        tk.Label(row, text="Порт LAN:", bg=CARD, fg=TEXT, font=("Segoe UI", 10)).pack(side="left")
        port = tk.Entry(row, width=7, bg=BG, fg=TEXT, insertbackground=TEXT,
                        relief="flat", font=("Segoe UI", 11))
        port.insert(0, str(web.get("port", 8765)))
        port.pack(side="left", padx=S(8))
        tk.Label(row, text="Код привязки KidsGuard:", bg=CARD, fg=TEXT, font=("Segoe UI", 10)).pack(side="left", padx=(S(10), 0))
        pcode = tk.Entry(row, width=10, bg=BG, fg=TEXT, insertbackground=TEXT,
                         relief="flat", font=("Segoe UI", 11))
        pcode.insert(0, str(web.get("pairing_code", "101-701")))
        pcode.pack(side="left", padx=S(8))
        addr = tk.Label(dlg, text="", bg=CARD, fg=ACCENT, font=("Segoe UI", 12, "bold"))
        addr.pack(anchor="w", pady=S(14), **pad)

        def show():
            addr.config(text=f"Wi-Fi: http://{local_ip()}:{port.get().strip() or 8765}  |  Код P2P: {pcode.get().strip() or '101-701'}")

        show()

        def save():
            try:
                p = int(port.get())
                if not 1024 <= p <= 65535:
                    raise ValueError
            except ValueError:
                return messagebox.showwarning("Порт", "Порт: число от 1024 до 65535.", parent=dlg)
            code_val = pcode.get().strip() or "101-701"
            self.cfg["web"] = {"enabled": var.get(), "port": p, "pairing_code": code_val}
            save_config(self.cfg)
            show()
            messagebox.showinfo(
                "Готово", ("Панель и P2P-мост запустятся в течение пары секунд.\n\n"
                           + addr.cget("text")) if var.get() else "Панель выключена.", parent=dlg)
            dlg.destroy()

        self.button(dlg, "Сохранить", save).pack(fill="x", pady=S(10), **pad)

    def kill_tick(self):
        names = set()
        for e in active(effective(self.cfg["entries"], load_usage()), "kill"):
            for p in e["programs"]:
                names.add(os.path.basename(p.replace("/", "\\")).lower())
        if names and IS_WIN:
            threading.Thread(target=kill_processes, args=(names,), daemon=True).start()
        self.after(3000, self.kill_tick)

    # --- диалог записи ---
    def edit_dialog(self, idx, prefill=None):
        editing = idx is not None
        entry = self.cfg["entries"][idx] if editing else None

        dlg = tk.Toplevel(self)
        dlg.title("Изменить" if editing else "Добавить")
        dlg.configure(bg=CARD)
        dlg.transient(self)
        dlg.grab_set()
        pad = {"padx": S(22)}
        dlg.geometry(f"{S(560)}x{min(S(900), self.winfo_screenheight() - S(90))}")
        foot = tk.Frame(dlg, bg=CARD)
        foot.pack(side="bottom", fill="x")
        cv = tk.Canvas(dlg, bg=CARD, highlightthickness=0)
        sbar = ttk.Scrollbar(dlg, orient="vertical", command=cv.yview)
        body = tk.Frame(cv, bg=CARD)
        body.bind("<Configure>", lambda e: cv.configure(scrollregion=cv.bbox("all")))
        wid = cv.create_window((0, 0), window=body, anchor="nw")
        cv.bind("<Configure>", lambda e: cv.itemconfigure(wid, width=e.width))
        cv.configure(yscrollcommand=sbar.set)
        cv.pack(side="left", fill="both", expand=True)
        sbar.pack(side="right", fill="y")
        dlg.bind("<MouseWheel>", lambda e: cv.yview_scroll(int(-e.delta / 120), "units"))

        def label(text, top=12):
            tk.Label(body, text=text, bg=CARD, fg=MUTED,
                     font=("Segoe UI", 9)).pack(anchor="w", pady=(S(top), S(2)), **pad)

        def text_box(h, font=("Consolas", 10), wrap="word"):
            t = tk.Text(body, height=h, bg=BG, fg=TEXT, insertbackground=TEXT,
                        relief="flat", font=font, wrap=wrap, padx=S(8), pady=S(6))
            t.pack(fill="x", **pad)
            return t

        label("Название", 18)
        name = tk.Entry(body, bg=BG, fg=TEXT, insertbackground=TEXT, relief="flat",
                        font=("Segoe UI", 11))
        name.pack(fill="x", ipady=S(7), **pad)

        label("Домены (через пробел, запятую или с новой строки)")
        dom = text_box(6)
        label("Программы (по одной в строке: имя.exe или полный путь)")
        prog = text_box(4, wrap="none")
        label("IP-адреса / подсети (CIDR) — блокируются файрволом")
        ips = text_box(3)

        def browse():
            p = filedialog.askopenfilename(parent=dlg, filetypes=[("Программы", "*.exe")])
            if p:
                cur = prog.get("1.0", "end").strip()
                prog.delete("1.0", "end")
                prog.insert("1.0", (cur + "\n" if cur else "") + p.replace("/", "\\"))

        tk.Button(body, text="📂 Выбрать .exe…", command=browse, bg=CARD, fg=ACCENT,
                  activebackground=CARD, activeforeground=ACCENT, relief="flat",
                  bd=0, cursor="hand2", font=("Segoe UI", 9)).pack(anchor="w", **pad)

        label("Способы блокировки")
        vars_ = {}
        for key, text in METHODS.items():
            v = tk.BooleanVar(value=entry["methods"].get(key, True) if editing else True)
            vars_[key] = v
            tk.Checkbutton(body, text=text, variable=v, bg=CARD, fg=TEXT,
                           selectcolor=BG, activebackground=CARD,
                           activeforeground=TEXT, anchor="w",
                           font=("Segoe UI", 9)).pack(fill="x", **pad)

        label("Лимиты времени (пусто или 0 — без лимита)")
        lim0 = entry["limit"] if editing else default_limit()
        grid = tk.Frame(body, bg=CARD)
        grid.pack(fill="x", **pad)
        lv = {}
        for i, (key, text) in enumerate((("daily", "В день, мин:"), ("total", "Всего, мин:"),
                                         ("from", "Разрешено с (ЧЧ:ММ):"), ("to", "до (ЧЧ:ММ):"))):
            tk.Label(grid, text=text, bg=CARD, fg=TEXT, anchor="w",
                     font=("Segoe UI", 9)).grid(row=i // 2, column=(i % 2) * 2,
                                                sticky="w", padx=(0, S(6)), pady=S(3))
            en = tk.Entry(grid, width=7, bg=BG, fg=TEXT, insertbackground=TEXT,
                          relief="flat", font=("Segoe UI", 10))
            en.grid(row=i // 2, column=(i % 2) * 2 + 1, sticky="w", padx=(0, S(16)))
            if lim0[key] not in (0, ""):
                en.insert(0, str(lim0[key]))
            lv[key] = en
        close_var = tk.BooleanVar(value=lim0.get("close_tab", True))
        tk.Checkbutton(body, text="Закрывать открытую вкладку с сайтом (за пару секунд)",
                       variable=close_var, bg=CARD, fg=TEXT, selectcolor=BG,
                       activebackground=CARD, activeforeground=TEXT, anchor="w",
                       font=("Segoe UI", 9)).pack(fill="x", **pad)
        if editing:
            tk.Button(body, text="↺ Сбросить счётчик времени",
                      command=lambda: (send_reset(entry["name"]),
                                       messagebox.showinfo("Готово", "Счётчик будет сброшен "
                                                           "в течение пары секунд.", parent=dlg)),
                      bg=CARD, fg=ACCENT, activebackground=CARD, activeforeground=ACCENT,
                      relief="flat", bd=0, cursor="hand2",
                      font=("Segoe UI", 9)).pack(anchor="w", **pad)

        if editing:
            name.insert(0, entry["name"])
            dom.insert("1.0", "\n".join(entry["domains"]))
            prog.insert("1.0", "\n".join(entry["programs"]))
            ips.insert("1.0", "\n".join(entry["ips"]))
        elif prefill:  # запись создаётся из «📊 Активность» с уже подставленными данными
            name.insert(0, prefill.get("name", ""))
            dom.insert("1.0", "\n".join(prefill.get("domains", [])))
            prog.insert("1.0", "\n".join(prefill.get("programs", [])))
        name.focus_set()

        def save():
            n = name.get().strip()
            d = parse_domains(dom.get("1.0", "end"))
            p = parse_programs(prog.get("1.0", "end"))
            try:
                ip = parse_ips(ips.get("1.0", "end"))
            except ValueError as ex:
                return messagebox.showwarning("Данные", str(ex), parent=dlg)
            if not n or not (d or p or ip):
                return messagebox.showwarning(
                    "Данные", "Укажите название и хотя бы один домен, программу или IP.",
                    parent=dlg)
            methods = {k: v.get() for k, v in vars_.items()}
            try:
                lim = {"daily": int(lv["daily"].get() or 0), "total": int(lv["total"].get() or 0),
                       "from": lv["from"].get().strip(), "to": lv["to"].get().strip(),
                       "close_tab": close_var.get()}
            except ValueError:
                return messagebox.showwarning("Данные", "Минуты — целые числа.", parent=dlg)
            if bool(lim["from"]) != bool(lim["to"]) or any(
                    lim[k] and _hm(lim[k]) is None for k in ("from", "to")):
                return messagebox.showwarning(
                    "Данные", "Время укажите парой «с» и «до» в формате ЧЧ:ММ.", parent=dlg)
            if editing:
                old = {k: entry[k] for k in ("name", "domains", "programs", "ips", "methods", "limit")}
                entry.update(name=n, domains=d, programs=p, ips=ip, methods=methods, limit=lim)
                if not self.commit():
                    entry.update(old)
                    self.commit(silent=True)
                    return
            else:
                self.cfg["entries"].append({"name": n, "enabled": False, "domains": d,
                                            "programs": p, "ips": ip, "methods": methods,
                                            "limit": lim})
                save_config(self.cfg)
            dlg.destroy()
            self.render()

        self.button(foot, "Сохранить", save).pack(fill="x", pady=S(18), **pad)

    def full_lock_dialog(self):
        cfg = self.cfg
        fl = dict(default_full_lock(), **(cfg.get("full_lock") or {}))
        u = load_usage()
        dlg = tk.Toplevel(self)
        dlg.title("Блокировка компьютера")
        dlg.configure(bg=CARD)
        dlg.geometry(f"{S(540)}x{S(560)}")
        dlg.transient(self)
        dlg.grab_set()
        pad = {"padx": S(22)}
        tk.Label(dlg, text="🔒 Блокировка компьютера", bg=CARD, fg=TEXT,
                 font=("Segoe UI", 13, "bold")).pack(anchor="w", pady=(S(18), S(4)), **pad)
        tk.Label(dlg, text="Отключает вход в Windows-аккаунт ребёнка и завершает его "
                           "текущий сеанс. Просто «запереть экран» недостаточно: ребёнок, "
                           "знающий свой пароль, сразу его снимет.",
                 bg=CARD, fg=MUTED, wraplength=S(480), justify="left",
                 font=("Segoe UI", 9)).pack(anchor="w", **pad)

        tk.Label(dlg, text="Имя учётной записи Windows (не отображаемое, а системное)",
                 bg=CARD, fg=MUTED, font=("Segoe UI", 9)).pack(anchor="w", pady=(S(14), S(2)), **pad)
        acct = tk.Entry(dlg, bg=BG, fg=TEXT, insertbackground=TEXT, relief="flat",
                        font=("Segoe UI", 11))
        default_acct = cfg.get("child_account", "") or detect_active_username(cfg)
        acct.insert(0, default_acct)
        acct.pack(fill="x", ipady=S(6), **pad)
        tk.Button(dlg, text="Показать список учётных записей",
                  command=lambda: messagebox.showinfo("Учётные записи Windows",
                                                       list_local_accounts() or "Недоступно.",
                                                       parent=dlg),
                  bg=CARD, fg=ACCENT, activebackground=CARD, activeforeground=ACCENT,
                  relief="flat", bd=0, cursor="hand2",
                  font=("Segoe UI", 9)).pack(anchor="w", **pad)

        status = tk.Label(dlg, text=("🔒 " + full_lock_text(cfg, u)) if full_blocked(cfg, u)
                          else ("✅ " + full_lock_text(cfg, u)),
                          bg=CARD, fg=("#ef4444" if full_blocked(cfg, u) else "#22c55e"),
                          font=("Segoe UI", 10, "bold"))
        status.pack(anchor="w", pady=(S(14), S(4)), **pad)

        def toggle_now():
            fl["now"] = not fl["now"]
            entered_acct = acct.get().strip() or detect_active_username(cfg)
            cfg["child_account"] = entered_acct
            cfg["full_lock"] = fl
            save_config(cfg)
            locked_state = full_blocked(cfg, u)
            _write_blocked_flag(locked_state)
            threading.Thread(target=apply_full_lock, args=(entered_acct, locked_state), daemon=True).start()
            dlg.destroy()

        self.button(dlg, "🔓 Снять блокировку сейчас" if fl["now"] else "🔒 Заблокировать сейчас",
                   toggle_now, bg="#374151" if fl["now"] else "#7f1d1d").pack(fill="x", pady=S(6), **pad)

        tk.Label(dlg, text="Расписание (необязательно, пусто или 0 — без лимита)",
                 bg=CARD, fg=MUTED, font=("Segoe UI", 9)).pack(anchor="w", pady=(S(14), S(2)), **pad)
        grid = tk.Frame(dlg, bg=CARD)
        grid.pack(fill="x", **pad)
        lv = {}
        for i, (key, text) in enumerate((("daily", "В день, мин:"), ("total", "Всего, мин:"),
                                         ("from", "Разрешено с (ЧЧ:ММ):"), ("to", "до (ЧЧ:ММ):"))):
            tk.Label(grid, text=text, bg=CARD, fg=TEXT, anchor="w",
                     font=("Segoe UI", 9)).grid(row=i // 2, column=(i % 2) * 2,
                                                sticky="w", padx=(0, S(6)), pady=S(3))
            en = tk.Entry(grid, width=7, bg=BG, fg=TEXT, insertbackground=TEXT,
                          relief="flat", font=("Segoe UI", 10))
            en.grid(row=i // 2, column=(i % 2) * 2 + 1, sticky="w", padx=(0, S(16)))
            if fl.get(key) not in (0, ""):
                en.insert(0, str(fl[key]))
            lv[key] = en

        def save_schedule():
            try:
                new_fl = {"now": fl["now"], "daily": int(lv["daily"].get() or 0),
                         "total": int(lv["total"].get() or 0), "from": lv["from"].get().strip(),
                         "to": lv["to"].get().strip()}
            except ValueError:
                return messagebox.showwarning("Данные", "Минуты — целые числа.", parent=dlg)
            if bool(new_fl["from"]) != bool(new_fl["to"]) or any(
                    new_fl[k] and _hm(new_fl[k]) is None for k in ("from", "to")):
                return messagebox.showwarning(
                    "Данные", "Время укажите парой «с» и «до» в формате ЧЧ:ММ.", parent=dlg)
            cfg["child_account"] = acct.get().strip() or detect_active_username(cfg)
            cfg["full_lock"] = new_fl
            save_config(cfg)
            dlg.destroy()
            messagebox.showinfo("Готово", "Расписание сохранено.")

        self.button(dlg, "Сохранить расписание", save_schedule).pack(fill="x", pady=S(14), **pad)

    def activity_dialog(self):
        act = load_activity()
        dlg = tk.Toplevel(self)
        dlg.title("Активность за сегодня")
        dlg.configure(bg=CARD)
        dlg.geometry(f"{S(560)}x{min(S(680), self.winfo_screenheight() - S(90))}")
        dlg.transient(self)
        pad = {"padx": S(20)}
        tk.Label(dlg, text="📊 Активность за сегодня", bg=CARD, fg=TEXT,
                 font=("Segoe UI", 13, "bold")).pack(anchor="w", pady=(S(16), S(4)), **pad)
        tk.Label(dlg, text="По программам и по заголовкам активных окон браузера. "
                           "Это не адрес сайта, а то, что видно в заголовке вкладки.",
                 bg=CARD, fg=MUTED, wraplength=S(500), justify="left",
                 font=("Segoe UI", 9)).pack(anchor="w", **pad)

        cv = tk.Canvas(dlg, bg=CARD, highlightthickness=0)
        sbar = ttk.Scrollbar(dlg, orient="vertical", command=cv.yview)
        body = tk.Frame(cv, bg=CARD)
        body.bind("<Configure>", lambda e: cv.configure(scrollregion=cv.bbox("all")))
        wid = cv.create_window((0, 0), window=body, anchor="nw")
        cv.bind("<Configure>", lambda e: cv.itemconfigure(wid, width=e.width))
        cv.configure(yscrollcommand=sbar.set)
        cv.pack(side="left", fill="both", expand=True, padx=(S(16), 0), pady=S(8))
        sbar.pack(side="right", fill="y")
        dlg.bind("<MouseWheel>", lambda e: cv.yview_scroll(int(-e.delta / 120), "units"))

        def row(parent, text, seconds, on_block):
            r = tk.Frame(parent, bg=BG, padx=S(10), pady=S(7))
            r.pack(fill="x", pady=S(3), padx=S(4))
            tk.Label(r, text=text, bg=BG, fg=TEXT, anchor="w",
                     font=("Segoe UI", 10)).pack(side="left", fill="x", expand=True)
            tk.Label(r, text=f"{int(seconds) // 60} мин", bg=BG, fg=MUTED,
                     font=("Segoe UI", 9)).pack(side="right", padx=(S(8), S(10)))
            tk.Button(r, text="🚫 Заблокировать", command=on_block, bg=BG, fg="#ef4444",
                     activebackground=BG, activeforeground="#ef4444", relief="flat",
                     bd=0, cursor="hand2", font=("Segoe UI", 9)).pack(side="right")

        if not act:
            tk.Label(body, text="Пока нет данных. Компьютер должен быть включён, ребёнок — "
                                "в системе, а фоновая служба — запущена (⚙ Служба).",
                     bg=CARD, fg=MUTED, wraplength=S(480), justify="left",
                     font=("Segoe UI", 10)).pack(pady=S(30), **pad)

        for user, d in act.items():
            tk.Label(body, text=f"👤 {user}", bg=CARD, fg=TEXT,
                     font=("Segoe UI", 11, "bold")).pack(anchor="w", pady=(S(12), S(4)), **pad)
            procs, titles = top_activity(d)
            if procs:
                tk.Label(body, text="Программы", bg=CARD, fg=MUTED,
                         font=("Segoe UI", 8, "bold")).pack(anchor="w", **pad)
                for proc, sec in procs:
                    row(body, proc, sec, lambda p=proc: self._block_from_activity(
                        dlg, {"name": p.rsplit(".", 1)[0].title(), "programs": [p]}))
            if titles:
                tk.Label(body, text="Заголовки окон (вероятные сайты)", bg=CARD, fg=MUTED,
                         font=("Segoe UI", 8, "bold")).pack(anchor="w", pady=(S(6), 0), **pad)
                for title, sec in titles:
                    row(body, title, sec, lambda t=title: self._block_from_activity(
                        dlg, self._guess_from_title(t)))

    def _guess_from_title(self, title):
        low = title.lower()
        for brand, domains in BRAND_DOMAINS.items():
            if brand in low:
                return {"name": brand.title(), "domains": domains}
        return {"name": title[:40]}

    def _block_from_activity(self, activity_dlg, prefill):
        activity_dlg.destroy()
        self.edit_dialog(None, prefill=prefill)

    def change_password(self):
        cur = simpledialog.askstring("Смена пароля", "Текущий пароль:", show="•", parent=self)
        if cur is None:
            return
        if not check_password(cur, self.cfg["salt"], self.cfg["hash"]):
            return messagebox.showerror("Ошибка", "Текущий пароль неверный.")
        p1 = simpledialog.askstring("Смена пароля", "Новый пароль:", show="•", parent=self)
        if p1 is None:
            return
        if len(p1) < 4:
            return messagebox.showwarning("Пароль", "Минимум 4 символа.")
        p2 = simpledialog.askstring("Смена пароля", "Повторите новый пароль:", show="•", parent=self)
        if p2 != p1:
            return messagebox.showwarning("Пароль", "Пароли не совпадают.")
        self.cfg["salt"], self.cfg["hash"] = hash_password(p1)
        save_config(self.cfg)
        messagebox.showinfo("Готово", "Пароль изменён.")


# ====================== ЛИМИТЫ, ФОНОВЫЙ ДВИЖОК И АГЕНТ ======================
# Схема (всё в одном файле, режимы запуска выбираются аргументом):
#   hosts_blocker.exe            — окно управления (администратор)
#   hosts_blocker.exe --engine   — движок от имени SYSTEM (задача планировщика):
#                                  каждые 2 с применяет блокировки, закрывает
#                                  программы, считает лимиты; работает без окна
#   hosts_blocker.exe --agent    — агент в сеансе пользователя (автозапуск при входе):
#                                  видит активное окно и закрывает вкладку с сайтом
if IS_WIN:
    PUB_DIR = os.path.join(os.environ.get("PROGRAMDATA") or r"C:\ProgramData", APP + "Pub")
else:
    PUB_DIR = os.path.join(CONF_DIR, "pub")
USAGE_FILE = os.path.join(CONF_DIR, "usage.json")
RESET_FILE = os.path.join(CONF_DIR, "reset.json")
BIN_DIR = os.path.join(CONF_DIR, "bin")
LOG_FILE = os.path.join(CONF_DIR, "error.log")
ENGINE_TASKS = (("HostsBlockerEngine", ["/SC", "ONSTART"]),
                ("HostsBlockerWatchdog", ["/SC", "MINUTE", "/MO", "5"]))
RUN_KEY = r"SOFTWARE\Microsoft\Windows\CurrentVersion\Run"
RUN_NAME = "HostsBlockerAgent"
BROWSERS = {"chrome.exe", "msedge.exe", "firefox.exe", "brave.exe", "opera.exe",
            "browser.exe", "vivaldi.exe"}
TICK = 2
_MUTEXES = []


def log_error(text):
    try:
        os.makedirs(CONF_DIR, exist_ok=True)
        with open(LOG_FILE, "a", encoding="utf-8") as f:
            f.write(text.rstrip() + "\n")
    except Exception:
        pass


# ---------- правила лимитов ----------
def default_limit():
    return {"daily": 0, "total": 0, "from": "", "to": "", "close_tab": True}


def has_limit(e):
    L = e["limit"]
    return bool(L["daily"] or L["total"] or (L["from"] and L["to"]))


def _hm(s):
    try:
        h, m = str(s).strip().split(":")
        return int(h) * 60 + int(m)
    except Exception:
        return None


def in_window(L, now=None):
    a, b = _hm(L["from"]), _hm(L["to"])
    if a is None or b is None:
        return True
    now = now or datetime.datetime.now()
    t = now.hour * 60 + now.minute
    return a <= t < b if a <= b else (t >= a or t < b)


def blocked_now(e, u):
    """Переключатель включён: без лимитов — блок всегда; с лимитами — блок,
    когда лимит исчерпан или сейчас не разрешённое время."""
    if not e["enabled"]:
        return False
    L = e["limit"]
    if not has_limit(e):
        return True
    if L["from"] and L["to"] and not in_window(L):
        return True
    n = e["name"]
    if L["daily"] and u["today"].get(n, 0) >= L["daily"] * 60:
        return True
    if L["total"] and u["total"].get(n, 0) >= L["total"] * 60:
        return True
    return False


def effective(entries, u):
    return [dict(e, enabled=blocked_now(e, u)) for e in entries]


def limit_text(e, u):
    if not e["enabled"] or not has_limit(e):
        return ""
    if blocked_now(e, u):
        return "🔒 заблокировано (лимит исчерпан или не разрешённое время)"
    L, n, parts = e["limit"], e["name"], []
    if L["daily"]:
        parts.append(f"сегодня осталось {max(0, L['daily'] * 60 - int(u['today'].get(n, 0))) // 60} мин")
    if L["total"]:
        parts.append(f"всего осталось {max(0, L['total'] * 60 - int(u['total'].get(n, 0))) // 60} мин")
    if L["from"] and L["to"]:
        parts.append(f"разрешено {L['from']}–{L['to']}")
    return " · ".join(parts)


def load_usage():
    try:
        with open(USAGE_FILE, "r", encoding="utf-8") as f:
            u = json.load(f)
    except Exception:
        u = {}
    u.setdefault("today", {})
    u.setdefault("total", {})
    day = datetime.date.today().isoformat()
    if u.get("day") != day:
        u["day"], u["today"] = day, {}
    return u


def save_usage(u):
    os.makedirs(CONF_DIR, exist_ok=True)
    tmp = USAGE_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(u, f)
    os.replace(tmp, USAGE_FILE)


def send_reset(name):
    try:
        try:
            with open(RESET_FILE, "r", encoding="utf-8") as f:
                names = json.load(f)
        except Exception:
            names = []
        names.append(name)
        with open(RESET_FILE, "w", encoding="utf-8") as f:
            json.dump(names, f)
    except Exception as ex:
        log_error(f"reset: {ex}")


def apply_all(entries, u):
    """Применяет hosts + политики + файрвол по ЭФФЕКТИВНОМУ состоянию."""
    errors = []
    eff = effective(entries, u)
    for label, fn in (("hosts", apply_hosts), ("браузеры", apply_browser_policies),
                      ("файрвол", apply_firewall)):
        try:
            fn(eff)
        except PermissionError:
            errors.append(f"{label}: нет доступа (нужны права администратора)")
        except Exception as ex:
            errors.append(f"{label}: {ex}")
    return errors


def entry_keywords(e):
    """Слова, по которым узнаём сайт/игру в заголовке окна."""
    kws = {e["name"].lower()}
    for d in e["domains"]:
        parts = d.split(".")
        if len(parts) >= 2 and len(parts[-2]) >= 4:
            kws.add(parts[-2])
    return {k for k in kws if len(k) >= 3}


# ---------- агент (сеанс пользователя) ----------
def foreground_info():
    try:
        u32, k32 = ctypes.windll.user32, ctypes.windll.kernel32
        u32.GetForegroundWindow.restype = ctypes.c_void_p
        hwnd = u32.GetForegroundWindow()
        if not hwnd:
            return "", ""
        h = ctypes.c_void_p(hwnd)
        n = u32.GetWindowTextLengthW(h)
        buf = ctypes.create_unicode_buffer(n + 1)
        u32.GetWindowTextW(h, buf, n + 1)
        pid = ctypes.c_ulong()
        u32.GetWindowThreadProcessId(h, ctypes.byref(pid))
        k32.OpenProcess.restype = ctypes.c_void_p
        hp = k32.OpenProcess(0x1000, False, pid.value)
        name = ""
        if hp:
            size = ctypes.c_ulong(520)
            pb = ctypes.create_unicode_buffer(520)
            k32.QueryFullProcessImageNameW(ctypes.c_void_p(hp), 0, pb, ctypes.byref(size))
            name = os.path.basename(pb.value).lower()
            k32.CloseHandle(ctypes.c_void_p(hp))
        return buf.value, name
    except Exception:
        return "", ""


def close_tab():
    """Ctrl+W в активном окне браузера — закрывает текущую вкладку."""
    k = ctypes.windll.user32.keybd_event
    k(0x11, 0, 0, 0)
    k(0x57, 0, 0, 0)
    k(0x57, 0, 2, 0)
    k(0x11, 0, 2, 0)


def single_instance(name):
    if not IS_WIN:
        return True
    k32 = ctypes.WinDLL("kernel32", use_last_error=True)
    k32.CreateMutexW.restype = ctypes.c_void_p
    h = k32.CreateMutexW(None, False, name)
    if ctypes.get_last_error() == 183:  # ERROR_ALREADY_EXISTS
        return False
    _MUTEXES.append(h)
    return True


def ensure_pub_dir():
    os.makedirs(PUB_DIR, exist_ok=True)
    if IS_WIN:  # пользователи могут писать только сюда (отчёты агента)
        run_hidden(["icacls", PUB_DIR, "/grant", "*S-1-5-32-545:(OI)(CI)M"])


# ====================== ПОЛНАЯ БЛОКИРОВКА КОМПЬЮТЕРА / АККАУНТА ======================
# Два режима: ручной (кнопка «Заблокировать сейчас») и по расписанию (лимит минут
# в день/всего или разрешённые часы — та же идея, что и у обычных записей).
# «Заблокировать» = Windows-аккаунт ребёнка отключается (net user /active:no) и его
# текущий сеанс принудительно завершается (WTSLogoffSession). Экран просто «запереть»
# недостаточно: ребёнок, знающий свой пароль, его сразу снимет.
def default_full_lock():
    return {"now": False, "daily": 0, "total": 0, "from": "", "to": ""}


def full_blocked(cfg, u):
    fl = cfg.get("full_lock") or {}
    if fl.get("now"):
        return True
    daily, total = fl.get("daily") or 0, fl.get("total") or 0
    frm, to = fl.get("from") or "", fl.get("to") or ""
    if not (daily or total or (frm and to)):
        return False
    if frm and to and not in_window({"from": frm, "to": to}):
        return True
    if daily and u["today"].get("__computer__", 0) >= daily * 60:
        return True
    if total and u["total"].get("__computer__", 0) >= total * 60:
        return True
    return False


def full_lock_text(cfg, u):
    fl = cfg.get("full_lock") or {}
    if fl.get("now"):
        return "заблокирован вручную"
    if full_blocked(cfg, u):
        return "заблокирован по расписанию"
    daily, total = fl.get("daily") or 0, fl.get("total") or 0
    parts = []
    if daily:
        parts.append(f"сегодня осталось {max(0, daily * 60 - int(u['today'].get('__computer__', 0))) // 60} мин")
    if total:
        parts.append(f"всего осталось {max(0, total * 60 - int(u['total'].get('__computer__', 0))) // 60} мин")
    if fl.get("from") and fl.get("to"):
        parts.append(f"доступ {fl['from']}–{fl['to']}")
    return " · ".join(parts) or "без ограничений"


def _console_decode(b):
    for enc in ("cp866", "cp1251", "utf-8"):
        try:
            return b.decode(enc)
        except Exception:
            continue
    return b.decode("utf-8", "ignore")


def list_local_accounts():
    if not IS_WIN:
        return ""
    r = run_hidden(["net", "user"])
    return _console_decode(r.stdout)


def set_account_active(username, active):
    if not IS_WIN or not username:
        return False
    r = run_hidden(["net", "user", username, "/active:%s" % ("yes" if active else "no")])
    if r.returncode != 0:
        log_error("net user %s: %s" % (username, _console_decode(r.stderr)))
    return r.returncode == 0


def detect_active_username(cfg=None):
    """Автоматически определяет имя активного Windows-пользователя за ПК."""
    try:
        for a in fresh_agents():
            u = str(a.get("user") or "").strip()
            if u and u.lower() not in ("system", "local service", "network service"):
                return u
    except Exception:
        pass
    try:
        for u in load_activity().keys():
            if u and u.lower() not in ("system", "local service", "network service"):
                return u
    except Exception:
        pass
    if IS_WIN:
        try:
            from ctypes import wintypes
            k32 = ctypes.windll.kernel32
            wts = ctypes.WinDLL("wtsapi32")
            sid = k32.WTSGetActiveConsoleSessionId()
            if sid != 0xFFFFFFFF:
                buf, n = wintypes.LPWSTR(), wintypes.DWORD()
                if wts.WTSQuerySessionInformationW(None, sid, 5, ctypes.byref(buf), ctypes.byref(n)):
                    name = (buf.value or "").strip()
                    wts.WTSFreeMemory(buf)
                    if name and name.lower() != "system":
                        return name
        except Exception:
            pass
    env_u = os.environ.get("USERNAME", "").strip()
    if env_u and env_u.lower() not in ("system", ""):
        return env_u
    if cfg and cfg.get("child_account"):
        return str(cfg["child_account"]).strip()
    return ""


def _write_blocked_flag(full_locked, kws=None):
    try:
        os.makedirs(PUB_DIR, exist_ok=True)
        bpath = os.path.join(PUB_DIR, "blocked.json")
        if kws is None:
            try:
                with open(bpath, "r", encoding="utf-8") as f:
                    kws = json.load(f).get("keywords", [])
            except Exception:
                kws = []
        with open(bpath + ".tmp", "w", encoding="utf-8") as f:
            json.dump({"keywords": kws, "full_locked": bool(full_locked)}, f)
        os.replace(bpath + ".tmp", bpath)
    except Exception:
        pass


def logoff_user_sessions(username=""):
    """Немедленно блокирует экран активного сеанса (WTSDisconnectSession) и завершает сеанс пользователя."""
    if not IS_WIN:
        return
    target = (username or "").strip().lower()
    try:
        from ctypes import wintypes
        wts = ctypes.WinDLL("wtsapi32")
        k32 = ctypes.windll.kernel32
        P = ctypes.c_void_p

        class SESSION_INFO(ctypes.Structure):
            _fields_ = [("SessionId", wintypes.DWORD), ("pWinStationName", wintypes.LPWSTR),
                        ("State", ctypes.c_int)]

        wts.WTSEnumerateSessionsW.argtypes = [P, wintypes.DWORD, wintypes.DWORD,
                                              ctypes.POINTER(ctypes.POINTER(SESSION_INFO)),
                                              ctypes.POINTER(wintypes.DWORD)]
        wts.WTSQuerySessionInformationW.argtypes = [P, wintypes.DWORD, ctypes.c_int,
                                                    ctypes.POINTER(wintypes.LPWSTR),
                                                    ctypes.POINTER(wintypes.DWORD)]
        wts.WTSDisconnectSession.argtypes = [P, wintypes.DWORD, wintypes.BOOL]
        wts.WTSLogoffSession.argtypes = [P, wintypes.DWORD, wintypes.BOOL]

        console_sid = k32.WTSGetActiveConsoleSessionId()
        if console_sid != 0xFFFFFFFF:
            wts.WTSDisconnectSession(None, console_sid, False)
            run_hidden(["tsdiscon", str(console_sid)])

        pinfo = ctypes.POINTER(SESSION_INFO)()
        count = wintypes.DWORD()
        if wts.WTSEnumerateSessionsW(None, 0, 1, ctypes.byref(pinfo), ctypes.byref(count)):
            WTS_UserName = 5
            for i in range(count.value):
                sid = pinfo[i].SessionId
                state = pinfo[i].State
                buf, n = wintypes.LPWSTR(), wintypes.DWORD()
                name = ""
                if wts.WTSQuerySessionInformationW(None, sid, WTS_UserName,
                                                   ctypes.byref(buf), ctypes.byref(n)):
                    name = (buf.value or "").strip()
                    wts.WTSFreeMemory(buf)
                if state == 0 or (target and name.lower() == target):
                    wts.WTSDisconnectSession(None, sid, False)
                    if target and name.lower() == target:
                        wts.WTSLogoffSession(None, sid, False)
            wts.WTSFreeMemory(pinfo)
    except Exception:
        log_error("logoff_user_sessions: " + traceback.format_exc())
    try:
        ctypes.windll.user32.LockWorkStation()
    except Exception:
        pass
    run_hidden(["rundll32.exe", "user32.dll,LockWorkStation"])


def apply_full_lock(username, locked):
    cfg = load_config() or {}
    saved_user = str(cfg.get("child_account") or "").strip()
    eff_user = (username or "").strip()
    if not eff_user or eff_user.lower() == "user":
        detected = detect_active_username(cfg)
        if detected:
            eff_user = detected
    _write_blocked_flag(locked)
    if locked:
        if eff_user:
            set_account_active(eff_user, False)
        logoff_user_sessions(eff_user)
    else:
        for u_name in {eff_user, saved_user, detect_active_username(cfg)}:
            if u_name and u_name.lower() not in ("", "user", "system"):
                set_account_active(u_name, True)


# ====================== ОТСЛЕЖИВАНИЕ АКТИВНОСТИ ======================
# Копит время по программам и по заголовкам активных окон (в заголовке браузера
# обычно видно сайт/видео — «YouTube», «ВКонтакте» и т.п.). Данные за сегодня,
# хранятся в открытом для пользователя месте (PUB_DIR), поэтому это наблюдение
# «как есть» внутри семьи, а не защищённый от обхода системный журнал: технически
# подкованный ребёнок с правами в своём профиле теоретически может стереть файл —
# в таком случае отчёт за день просто окажется неполным, без ошибки.
ACTIVITY_SKIP = {"explorer.exe", "searchhost.exe", "shellexperiencehost.exe",
                 "applicationframehost.exe", "textinputhost.exe", "lockapp.exe",
                 "hostsblockerengine.exe", "hostsblockeragent.exe", "hostsblockergui.exe",
                 "hostsblocker.exe", "python.exe", "pythonw.exe"}


def _activity_path(user):
    return os.path.join(PUB_DIR, f"activity_{user}.json")


def _activity_history_path():
    return os.path.join(PUB_DIR, "activity_history.json")


def load_activity_history():
    """История активности по дням: {YYYY-MM-DD: {логин: {"proc":{}, "titles":{}, "ts": ms}}}."""
    try:
        hp = _activity_history_path()
        if os.path.exists(hp):
            with open(hp, "r", encoding="utf-8") as f:
                d = json.load(f)
            if isinstance(d, dict):
                return d
    except Exception:
        pass
    return {}


def save_activity_history_day(day_iso, user, proc_sec, title_sec):
    try:
        os.makedirs(PUB_DIR, exist_ok=True)
        hist = load_activity_history()
        day_entry = hist.setdefault(day_iso, {})
        day_entry[user] = {
            "date": day_iso,
            "proc": dict(proc_sec),
            "titles": dict(title_sec),
            "ts": int(time.time() * 1000)
        }
        # Храним последние 30 дней
        if len(hist) > 30:
            for old_day in sorted(hist.keys())[:-30]:
                hist.pop(old_day, None)
        hp = _activity_history_path()
        with open(hp + ".tmp", "w", encoding="utf-8") as f:
            json.dump(hist, f, ensure_ascii=False)
        os.replace(hp + ".tmp", hp)
    except Exception:
        pass


def load_activity(target_date=None):
    """Все пользователи, у кого есть отчёт за указанную дату (по умолчанию сегодня): {логин: {"proc":{},"titles":{}}}."""
    res = {}
    day_iso = target_date or datetime.date.today().isoformat()
    try:
        hist = load_activity_history()
        if day_iso in hist and isinstance(hist[day_iso], dict):
            for u, d in hist[day_iso].items():
                if isinstance(d, dict):
                    res[u] = d
        for fn in os.listdir(PUB_DIR):
            if not (fn.startswith("activity_") and fn.endswith(".json") and fn != "activity_history.json"):
                continue
            try:
                with open(os.path.join(PUB_DIR, fn), "r", encoding="utf-8") as f:
                    d = json.load(f)
            except Exception:
                continue
            if d.get("date") == day_iso:
                res[fn[len("activity_"):-len(".json")]] = d
    except Exception:
        pass
    return res



def top_activity(d, n=8, min_seconds=15):
    procs = sorted(((p, s) for p, s in d.get("proc", {}).items()
                    if p not in ACTIVITY_SKIP and s >= min_seconds),
                   key=lambda kv: -kv[1])[:n]
    titles = sorted(((t, s) for t, s in d.get("titles", {}).items() if s >= min_seconds),
                    key=lambda kv: -kv[1])[:n]
    return procs, titles


def _grab_screen_bgra(dst_w, dst_h):
    """Снимок экрана монитора через GDI (ctypes) в raw-буфер BGRA dst_w*dst_h*4."""
    if not IS_WIN:
        return None
    try:
        from ctypes import wintypes
        u32, g32 = ctypes.windll.user32, ctypes.windll.gdi32
        P = ctypes.c_void_p

        class BIH(ctypes.Structure):
            _fields_ = [("biSize", wintypes.DWORD), ("biWidth", wintypes.LONG),
                        ("biHeight", wintypes.LONG), ("biPlanes", wintypes.WORD),
                        ("biBitCount", wintypes.WORD), ("biCompression", wintypes.DWORD),
                        ("biSizeImage", wintypes.DWORD), ("biXPelsPerMeter", wintypes.LONG),
                        ("biYPelsPerMeter", wintypes.LONG), ("biClrUsed", wintypes.DWORD),
                        ("biClrImportant", wintypes.DWORD)]

        u32.GetDC.argtypes, u32.GetDC.restype = [P], P
        u32.ReleaseDC.argtypes = [P, P]
        g32.CreateCompatibleDC.argtypes, g32.CreateCompatibleDC.restype = [P], P
        # ВАЖНО: раньше здесь в restype попадал весь список из 6 типов -> ValueError при
        # каждом вызове, и снимок экрана молча не работал вообще (возвращался None).
        g32.CreateDIBSection.argtypes = [P, ctypes.POINTER(BIH), wintypes.UINT,
                                         ctypes.POINTER(P), P, wintypes.DWORD]
        g32.CreateDIBSection.restype = P
        g32.SelectObject.argtypes, g32.SelectObject.restype = [P, P], P
        g32.SetStretchBltMode.argtypes = [P, ctypes.c_int]
        g32.StretchBlt.argtypes = [P, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int,
                                   P, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, wintypes.DWORD]
        g32.DeleteObject.argtypes = [P]
        g32.DeleteDC.argtypes = [P]

        src_w = u32.GetSystemMetrics(0) or 1920
        src_h = u32.GetSystemMetrics(1) or 1080
        sdc = u32.GetDC(None)
        if not sdc:
            return None
        mdc = g32.CreateCompatibleDC(sdc)
        bi = BIH(ctypes.sizeof(BIH), dst_w, -dst_h, 1, 32, 0, 0, 0, 0, 0, 0)
        bits = P()
        bmp = g32.CreateDIBSection(mdc, ctypes.byref(bi), 0, ctypes.byref(bits), None, 0)
        if not bmp or not bits.value:
            g32.DeleteDC(mdc)
            u32.ReleaseDC(None, sdc)
            return None
        old = g32.SelectObject(mdc, bmp)
        g32.SetStretchBltMode(mdc, 3)  # COLORONCOLOR
        g32.StretchBlt(mdc, 0, 0, dst_w, dst_h, sdc, 0, 0, src_w, src_h, 0x00CC0020)  # SRCCOPY
        raw = ctypes.string_at(bits.value, dst_w * dst_h * 4)
        g32.SelectObject(mdc, old)
        g32.DeleteObject(bmp)
        g32.DeleteDC(mdc)
        u32.ReleaseDC(None, sdc)
        return raw
    except Exception:
        return None


def _encode_png_bgra(raw, dst_w, dst_h, level=6):
    """BGRA-буфер -> PNG (фильтр 0 на каждую строку), без внешних библиотек."""
    try:
        rows = bytearray()
        stride = dst_w * 4
        for y in range(dst_h):
            rows.append(0)
            line = raw[y * stride:(y + 1) * stride]
            for i in range(0, stride, 4):
                rows.append(line[i + 2])
                rows.append(line[i + 1])
                rows.append(line[i])

        def chunk(tag, data):
            return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

        ihdr = struct.pack(">IIBBBBB", dst_w, dst_h, 8, 2, 0, 0, 0)
        return (b"\x89PNG\r\n\x1a\n"
                + chunk(b"IHDR", ihdr)
                + chunk(b"IDAT", zlib.compress(bytes(rows), level))
                + chunk(b"IEND", b""))
    except Exception:
        return None


def capture_screen_png(dst_w=640, dst_h=360, level=6):
    """Снимок экрана монитора в PNG без внешних библиотек (ctypes GDI + zlib)."""
    raw = _grab_screen_bgra(dst_w, dst_h)
    if not raw:
        return None
    return _encode_png_bgra(raw, dst_w, dst_h, level)


def capture_screen_frame(dst_w=800, dst_h=450, quality=55, prefer_jpeg=True):
    """Кадр экрана -> (bytes, content_type).

    С Pillow кодируется JPEG (в десятки раз быстрее и меньше PNG), без Pillow —
    честный PNG-резерв, чтобы программа работала на «чистом» Python.
    """
    raw = _grab_screen_bgra(dst_w, dst_h)
    if not raw:
        return None
    if prefer_jpeg and _PILImage is not None:
        try:
            img = _PILImage.frombuffer("RGB", (dst_w, dst_h), raw, "raw", "BGRX", 0, 1)
            out = io.BytesIO()
            img.save(out, "JPEG", quality=int(quality), optimize=False, subsampling=2)
            return out.getvalue(), "image/jpeg"
        except Exception:
            pass
    png = _encode_png_bgra(raw, dst_w, dst_h, 1)
    if not png:
        return None
    return png, "image/png"


# ====================== ЖИВАЯ ТРАНСЛЯЦИЯ ЭКРАНА ПК ======================
# Экран передаётся кадрами (JPEG) двумя путями:
#   1) MJPEG-поток по домашнему Wi-Fi: GET /api/stream  (multipart/x-mixed-replace);
#   2) кадры через P2P-мост (Интернет) на топик .../pc_stream/<код>, пока открыта «аренда» потока.
# Аренда продлевается телефоном (/api/stream_start) и автоматически истекает, чтобы
# компьютер не кодировал видео бесконечно и не тратил батарею/трафик впустую.
STREAM_LOCK = threading.RLock()
# until_p2p — аренда для кадров через Интернет; until_lan — для MJPEG по домашнему Wi-Fi.
# Разделяем их, чтобы при просмотре по Wi-Fi ПК не кодировал те же кадры ещё и в P2P.
STREAM_ST = {"until": 0.0, "until_p2p": 0.0, "until_lan": 0.0,
             "fps": 4.0, "w": 640, "h": 360, "quality": 50, "binary": False}


def stream_touch(duration=25.0, fps=None, w=None, h=None, quality=None, channel="p2p", binary=None):
    """Продлевает аренду живого потока и (опционально) меняет параметры кадров."""
    with STREAM_LOCK:
        now = time.time()
        try:
            if fps is not None:
                STREAM_ST["fps"] = max(1.0, min(12.0, float(fps)))
            if w is not None:
                STREAM_ST["w"] = max(320, min(1600, int(w)))
            if h is not None:
                STREAM_ST["h"] = max(180, min(900, int(h)))
            if quality is not None:
                STREAM_ST["quality"] = max(25, min(90, int(quality)))
            if binary is not None:
                STREAM_ST["binary"] = bool(binary)
        except (TypeError, ValueError):
            pass
        try:
            dur = float(duration)
        except (TypeError, ValueError):
            dur = 25.0
        until = now + max(3.0, min(180.0, dur))
        if channel == "lan":
            STREAM_ST["until_lan"] = until
        else:
            STREAM_ST["until_p2p"] = until
        STREAM_ST["until"] = max(float(STREAM_ST.get("until_lan") or 0.0),
                                 float(STREAM_ST.get("until_p2p") or 0.0))
        _write_stream_flag()
        return dict(STREAM_ST)


def stream_stop():
    with STREAM_LOCK:
        STREAM_ST["until"] = 0.0
        STREAM_ST["until_p2p"] = 0.0
        STREAM_ST["until_lan"] = 0.0
    _clear_stream_flag()


def stream_is_active():
    with STREAM_LOCK:
        return time.time() < float(STREAM_ST.get("until") or 0.0)


def stream_p2p_active():
    with STREAM_LOCK:
        return time.time() < float(STREAM_ST.get("until_p2p") or 0.0)


def stream_params():
    with STREAM_LOCK:
        p = dict(STREAM_ST)
    fps = max(1.0, min(12.0, float(p.get("fps") or 4.0)))
    w = max(320, min(1600, int(p.get("w") or 640))) // 2 * 2
    h = max(180, min(900, int(p.get("h") or 360))) // 2 * 2
    q = max(25, min(90, int(p.get("quality") or 50)))
    return {"fps": fps, "w": w, "h": h, "quality": q, "binary": bool(p.get("binary"))}


STREAM_FLAG_FILE = os.path.join(PUB_DIR, "stream.json")


def _write_stream_flag():
    """Сообщает агенту пользовательской сессии, что нужен живой захват экрана."""
    try:
        os.makedirs(PUB_DIR, exist_ok=True)
        p = stream_params()
        p["ts"] = time.time()
        tmp = STREAM_FLAG_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(p, f)
        os.replace(tmp, STREAM_FLAG_FILE)
    except Exception:
        pass


def _clear_stream_flag():
    try:
        os.remove(STREAM_FLAG_FILE)
    except Exception:
        pass


def agent_screen_frame(max_age=2.0):
    """Свежий кадр от агента (сеанс пользователя). Для потока из SYSTEM-движка:
    прямой GDI-захват в сессии 0 даёт чёрный экран, а агент видит рабочий стол."""
    path = os.path.join(PUB_DIR, "screen.png")
    try:
        if time.time() - os.path.getmtime(path) > max_age:
            return None
        with open(path, "rb") as f:
            data = f.read()
        if data.startswith(b"\xff\xd8"):
            return data, "image/jpeg"
        if data.startswith(b"\x89PNG\r\n\x1a\n"):
            return data, "image/png"
    except Exception:
        pass
    return None


def agent_main():
    if not single_instance("Local\\HostsBlockerAgent"):
        return
    user = re.sub(r"[^\w.-]", "_", os.environ.get("USERNAME", "user"))
    path = os.path.join(PUB_DIR, f"agent_{user}.json")
    act_path = _activity_path(user)
    scr_path = os.path.join(PUB_DIR, "screen.png")
    req_path = os.path.join(PUB_DIR, "screen_req.flag")
    stream_flag = os.path.join(PUB_DIR, "stream.json")
    today = datetime.date.today().isoformat()
    proc_sec, title_sec = {}, {}
    try:
        if os.path.exists(act_path):
            with open(act_path, "r", encoding="utf-8") as f:
                saved_act = json.load(f)
            if saved_act.get("date") == today:
                proc_sec = dict(saved_act.get("proc") or {})
                title_sec = dict(saved_act.get("titles") or {})
            elif saved_act.get("date"):
                save_activity_history_day(
                    str(saved_act.get("date")),
                    user,
                    saved_act.get("proc") or {},
                    saved_act.get("titles") or {}
                )
    except Exception:
        proc_sec, title_sec = {}, {}
    last_close, last_act_save, last_scr, last_agent_write = 0.0, 0.0, 0.0, 0.0
    last_hist_save = 0.0
    while True:
        title, proc = foreground_info()
        stream_cfg = None
        try:
            if os.path.exists(stream_flag) and time.time() - os.path.getmtime(stream_flag) < 8:
                with open(stream_flag, "r", encoding="utf-8") as f:
                    stream_cfg = json.load(f)
        except Exception:
            stream_cfg = None
        day = datetime.date.today().isoformat()
        if day != today:  # новый день — архивируем вчерашний день и считаем активность заново
            save_activity_history_day(today, user, proc_sec, title_sec)
            today, proc_sec, title_sec = day, {}, {}
        if proc:
            proc_sec[proc] = proc_sec.get(proc, 0) + 1
        key = title.strip()[:70]
        if key:
            title_sec[key] = title_sec.get(key, 0) + 1
        # Файл активного окна обновляем раз в 3 секунды — этого достаточно, а диск не дёргаем ежесекундно.
        if time.time() - last_agent_write >= 3:
            last_agent_write = time.time()
            try:
                os.makedirs(PUB_DIR, exist_ok=True)
                with open(path + ".tmp", "w", encoding="utf-8") as f:
                    json.dump({"ts": time.time(), "title": title, "proc": proc, "user": user}, f)
                os.replace(path + ".tmp", path)
            except Exception:
                pass
        try:
            with open(os.path.join(PUB_DIR, "blocked.json"), "r", encoding="utf-8") as f:
                bjson = json.load(f)
                kws = bjson.get("keywords", [])
                full_locked = bool(bjson.get("full_locked", False))
        except Exception:
            kws, full_locked = [], False
        if full_locked and IS_WIN:
            try:
                ctypes.windll.user32.LockWorkStation()
            except Exception:
                pass
        low = title.lower()
        if proc in BROWSERS and kws and any(k in low for k in kws) and time.time() - last_close > 3:
            last_close = time.time()
            try:
                close_tab()
            except Exception:
                pass
        # Снимок для превью: по запросу телефона/панели либо раз в 20 секунд (а не каждые 4 с).
        want_scr = os.path.exists(req_path) or (time.time() - last_scr > 20)
        if stream_cfg:
            # Живой поток: агент пользовательской сессии снимает экран чаще и пишет
            # screen.png, а SYSTEM-движок отдаёт этот кадр в MJPEG/P2P.
            try:
                sw = int(stream_cfg.get("w") or 640)
                sh = int(stream_cfg.get("h") or 360)
                sq = int(stream_cfg.get("quality") or 55)
                frame = capture_screen_frame(sw, sh, quality=sq)
                if frame:
                    with open(scr_path + ".tmp", "wb") as f:
                        f.write(frame[0])
                    os.replace(scr_path + ".tmp", scr_path)
                last_scr = time.time()
            except Exception:
                pass
            want_scr = False
        if want_scr:
            last_scr = time.time()
            try:
                if os.path.exists(req_path):
                    os.remove(req_path)
            except Exception:
                pass
            frame = capture_screen_frame(800, 450, quality=65)
            if frame:
                try:
                    with open(scr_path + ".tmp", "wb") as f:
                        f.write(frame[0])
                    os.replace(scr_path + ".tmp", scr_path)
                except Exception:
                    pass
        if time.time() - last_act_save > 5:  # отчёт об активности — раз в 5 секунд
            last_act_save = time.time()
            if len(title_sec) > 50:  # не даём файлу расти бесконечно
                for k, _ in sorted(title_sec.items(), key=lambda kv: kv[1])[:len(title_sec) - 50]:
                    title_sec.pop(k, None)
            try:
                with open(act_path + ".tmp", "w", encoding="utf-8") as f:
                    json.dump({"date": today, "proc": proc_sec, "titles": title_sec}, f,
                              ensure_ascii=False)
                os.replace(act_path + ".tmp", act_path)
                # 30-дневный архив пишем раз в минуту, а не каждые 5 секунд (бережём диск ПК).
                if time.time() - last_hist_save > 60:
                    last_hist_save = time.time()
                    save_activity_history_day(today, user, proc_sec, title_sec)
            except Exception:
                pass
        if stream_cfg:
            try:
                fps = max(1.0, min(12.0, float(stream_cfg.get("fps") or 4.0)))
            except (TypeError, ValueError):
                fps = 4.0
            time.sleep(max(0.12, 1.0 / fps))
        else:
            time.sleep(1)


# ---------- движок (SYSTEM) ----------
def running_names():
    try:
        out = run_hidden(["tasklist", "/FO", "CSV", "/NH"]).stdout.decode("utf-8", "ignore")
        return {r[0].lower() for r in csv.reader(out.splitlines()) if r}
    except Exception:
        return set()


def fresh_agents():
    res = []
    try:
        for fn in os.listdir(PUB_DIR):
            if fn.startswith("agent_") and fn.endswith(".json"):
                with open(os.path.join(PUB_DIR, fn), "r", encoding="utf-8") as f:
                    d = json.load(f)
                if time.time() - d.get("ts", 0) < 10:
                    res.append(d)
    except Exception:
        pass
    return res


def in_use(e, procs, agents):
    names = {os.path.basename(p.replace("/", "\\")).lower() for p in e["programs"]}
    if names & procs:
        return True
    if e["domains"]:
        kws = entry_keywords(e)
        for a in agents:
            if a.get("proc") in BROWSERS and any(k in a.get("title", "").lower() for k in kws):
                return True
        if not agents and (procs & BROWSERS):
            return True  # агента нет — проверить нельзя, время считаем
    return False


def engine_tick(st):
    now = time.time()
    dt = min(now - st["t"], 10)
    st["t"] = now
    try:
        mt = os.path.getmtime(CONF_FILE)
    except OSError:
        mt = 0
    if st["cfg"] is None or mt != st["mtime"]:
        cfg = load_config()
        if cfg:
            normalize(cfg["entries"])
            st["cfg"], st["mtime"] = cfg, mt
    if not st["cfg"]:
        return
    entries, u = st["cfg"]["entries"], st["u"]
    panel_sync(st)

    if os.path.exists(RESET_FILE):  # просьба GUI сбросить счётчик
        try:
            with open(RESET_FILE, "r", encoding="utf-8") as f:
                names = json.load(f)
            os.remove(RESET_FILE)
            for n in names:
                u["today"].pop(n, None)
                u["total"].pop(n, None)
            st["dirty"] = True
        except Exception:
            pass
    day = datetime.date.today().isoformat()
    if u.get("day") != day:
        u["day"], u["today"] = day, {}
        st["dirty"] = True

    procs, agents = running_names(), fresh_agents()
    if agents:  # кто-то реально активен за компьютером — считаем это время
        u["today"]["__computer__"] = u["today"].get("__computer__", 0) + dt
        u["total"]["__computer__"] = u["total"].get("__computer__", 0) + dt
        st["dirty"] = True

    fl_now = full_blocked(st["cfg"], u)
    fl_acct = st["cfg"].get("child_account", "") or detect_active_username(st["cfg"])
    if (fl_now, fl_acct) != st.get("full_prev") or (fl_now and now - st.get("last_lock_ts", 0) >= 4.0):
        st["full_prev"] = (fl_now, fl_acct)
        st["last_lock_ts"] = now
        try:
            apply_full_lock(fl_acct, fl_now)
        except Exception:
            log_error("full_lock: " + traceback.format_exc())

    for e in entries:  # считаем время только пока запись реально используется
        if e["enabled"] and has_limit(e) and not blocked_now(e, u) and in_use(e, procs, agents):
            n = e["name"]
            u["today"][n] = u["today"].get(n, 0) + dt
            u["total"][n] = u["total"].get(n, 0) + dt
            st["dirty"] = True

    eff = effective(entries, u)
    sig = (tuple(sorted(e["name"] for e in eff if e["enabled"])), st["mtime"])
    if sig != st["sig"]:  # состояние изменилось — применяем всё сразу
        st["sig"] = sig
        for err in apply_all(entries, u):
            log_error("apply: " + err)

    names = set()  # закрываем запущенные программы в реальном времени
    for e in eff:
        if e["enabled"] and e["methods"].get("kill"):
            for p in e["programs"]:
                names.add(os.path.basename(p.replace("/", "\\")).lower())
    hit = names & procs
    if hit:
        kill_processes(hit)

    kws = sorted({k for e in eff if e["enabled"] and e["domains"]
                  and e["limit"].get("close_tab", True) for k in entry_keywords(e)})
    if (kws, fl_now) != st["kws"]:
        st["kws"] = (kws, fl_now)
        _write_blocked_flag(fl_now, kws)

    if st["dirty"] and now - st["saved"] > 10:
        save_usage(u)
        st["dirty"], st["saved"] = False, now


def engine_main():
    if not single_instance("Global\\HostsBlockerEngine"):
        return
    ensure_pub_dir()
    st = {"t": time.time(), "cfg": None, "mtime": 0, "u": load_usage(), "sig": None,
          "kws": None, "dirty": False, "saved": time.time(), "full_prev": None}
    PANEL_ST["u"] = st["u"]
    threading.Thread(target=p2p_bridge_loop, args=(st,), daemon=True).start()
    threading.Thread(target=p2p_stream_loop, args=(st,), daemon=True).start()
    threading.Thread(target=udp_discovery_loop, args=(st,), daemon=True).start()
    while True:
        try:
            engine_tick(st)
        except Exception:
            log_error(traceback.format_exc())
        time.sleep(TICK)


# ====================== ВЕБ-ПАНЕЛЬ (управление с телефона по Wi-Fi) ======================
# Работает внутри движка. Только локальная сеть: чужие IP отклоняются, а правило
# брандмауэра пускает лишь адреса домашней подсети. Вход по паролю программы.
PANEL_LOCK = threading.Lock()
PANEL_SESSIONS = {}   # токен -> {"exp": время, "csrf": строка}
PANEL_FAILS = {}      # ip -> [число ошибок, заблокирован до]
PANEL_ST = {}         # ссылка на состояние движка (счётчики времени)
PANEL_RULE = "HostsBlockerPanel"

PANEL_HTML = r'''<!doctype html><html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Hosts Blocker</title>
<style>
:root{--bg:#111827;--card:#1f2937;--t:#f3f4f6;--m:#9ca3af;--a:#6366f1;--g:#22c55e}
*{box-sizing:border-box}
body{margin:0 auto;background:var(--bg);color:var(--t);font:16px/1.4 system-ui,"Segoe UI",sans-serif;padding:16px;max-width:560px}
h1{font-size:20px;margin:6px 0 14px;display:flex;justify-content:space-between;align-items:center}
.card{background:var(--card);border-radius:14px;padding:14px;margin:10px 0}
.row{display:flex;align-items:center;gap:12px}.grow{flex:1;min-width:0}
.name{font-weight:700}.sub{color:var(--m);font-size:13px}.warn{color:#f59e0b;font-size:13px;font-weight:600}
.sw{width:52px;height:30px;border-radius:15px;background:#4b5563;position:relative;border:0;flex:none;cursor:pointer}
.sw::after{content:"";position:absolute;top:3px;left:3px;width:24px;height:24px;border-radius:50%;background:#fff;transition:.2s}
.sw.on{background:var(--g)}.sw.on::after{left:25px}
input[type=text],input[type=password],input[type=number]{width:100%;background:var(--bg);color:var(--t);border:1px solid #374151;border-radius:8px;padding:10px;font-size:16px}
button.b{background:var(--a);color:#fff;border:0;border-radius:8px;padding:10px 14px;font-weight:600;font-size:15px}
button.g{background:#374151}
button.danger{background:#7f1d1d;color:#fff;width:100%;padding:14px;font-size:16px;border:0;border-radius:10px;font-weight:700}
details{margin-top:10px}summary{color:var(--m);cursor:pointer}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin:10px 0}
label{font-size:12px;color:var(--m);display:block;margin-bottom:3px}
.btns{display:flex;gap:8px;margin-top:10px}
</style></head><body>
<div id="login" hidden><h1>🛡 Hosts Blocker</h1><div class="card"><label>Пароль</label>
<input id="pw" type="password" autocomplete="current-password"><p class="warn" id="err"></p>
<button class="b" onclick="login()">Войти</button></div></div>
<div id="app" hidden><h1><span>🛡 Блокировка</span><button class="b g" onclick="logout()">Выйти</button></h1><div id="full"></div><div id="list"></div></div>
<script>
let csrf="",timer=null;
const $=s=>document.querySelector(s);
function el(t,c,x){const e=document.createElement(t);if(c)e.className=c;if(x!==undefined)e.textContent=x;return e}
async function api(p,b){
  const r=await fetch(p,{method:b===undefined?"GET":"POST",headers:{"Content-Type":"application/json","X-CSRF":csrf},body:b===undefined?undefined:JSON.stringify(b)});
  if(r.status===401){showLogin();throw new Error("auth")}
  return r.json();
}
function showLogin(){$("#login").hidden=false;$("#app").hidden=true;clearInterval(timer)}
async function login(){
  const r=await fetch("/api/login",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({password:$("#pw").value})});
  const j=await r.json();
  if(j.ok){$("#pw").value="";$("#err").textContent="";start()}else $("#err").textContent=j.error||"Ошибка";
}
async function logout(){try{await api("/api/logout",{})}catch(e){}showLogin()}
function start(){$("#login").hidden=true;$("#app").hidden=false;load(true);clearInterval(timer);timer=setInterval(()=>load(false),5000)}
async function load(force){
  if(!force&&(document.querySelector("details[open]")||document.activeElement.tagName=="INPUT"))return;
  const s=await api("/api/state");csrf=s.csrf;renderFull(s.full);render(s.entries);
}
function renderFull(f){
  const c=$("#full");c.replaceChildren();
  const card=el("div","card");
  card.append(el("div","name","🔒 Блокировка компьютера"));
  card.append(el("div","sub",f.has_account?f.text:"Сначала укажите аккаунт на компьютере (🔒 Блокировка ПК)"));
  const b=el("button","danger",f.now?"Снять блокировку":"Заблокировать компьютер сейчас");
  b.disabled=!f.has_account;
  b.onclick=async()=>{await api("/api/fulllock",{now:!f.now});load(true)};
  card.append(b);c.append(card);
}
function render(es){
  const L=$("#list");L.replaceChildren();
  for(const e of es){
    const c=el("div","card"),r=el("div","row"),g=el("div","grow");
    g.append(el("div","name",e.name));
    g.append(el("div","sub",e.blocked?"🔒 заблокировано":(e.enabled?"✅ доступно в рамках лимита":"блокировка выключена")));
    if(e.text&&!e.blocked)g.append(el("div","warn",e.text));
    const sw=el("button","sw"+(e.enabled?" on":""));
    sw.onclick=async()=>{await api("/api/toggle",{name:e.name,enabled:!e.enabled});load(true)};
    r.append(g,sw);c.append(r);
    const d=el("details");d.append(el("summary","","Лимиты времени"));
    const grid=el("div","grid"),f={};
    for(const [k,t,ty] of [["daily","В день, мин","number"],["total","Всего, мин","number"],["from","Разрешено с (ЧЧ:ММ)","text"],["to","до (ЧЧ:ММ)","text"]]){
      const w=el("div");w.append(el("label","",t));
      const i=el("input");i.type=ty;if(ty=="number")i.min=0;i.value=e.limit[k]||"";
      w.append(i);grid.append(w);f[k]=i;
    }
    const lb=el("label"),ch=el("input");ch.type="checkbox";ch.checked=e.limit.close_tab;lb.append(ch," закрывать вкладку с сайтом");
    const bs=el("button","b","Сохранить"),br=el("button","b g","Сбросить счётчик");
    bs.onclick=async()=>{
      const j=await api("/api/limit",{name:e.name,daily:+f.daily.value||0,total:+f.total.value||0,from:f.from.value,to:f.to.value,close_tab:ch.checked});
      if(j.ok){load(true)}else alert(j.error||"Ошибка");
    };
    br.onclick=async()=>{if(confirm("Сбросить счётчик времени?")){await api("/api/reset",{name:e.name});alert("Счётчик сбросится за пару секунд")}};
    const bt=el("div","btns");bt.append(bs,br);
    d.append(grid,lb,bt);c.append(d);L.append(c);
  }
}
document.addEventListener("keydown",ev=>{if(ev.key=="Enter"&&!$("#login").hidden)login()});
start();
</script></body></html>'''


class PanelHandler(http.server.BaseHTTPRequestHandler):
    server_version = "HB"

    def log_message(self, *args):
        pass

    def _allowed_ip(self):
        try:
            ip = ipaddress.ip_address(self.client_address[0].split("%")[0])
            return ip.is_private or ip.is_loopback or ip.is_link_local
        except ValueError:
            return False

    def _send(self, code, data, ctype="application/json; charset=utf-8", cookie=None):
        body = data if isinstance(data, bytes) else json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Security-Policy", "default-src 'self' 'unsafe-inline'")
        if cookie:
            self.send_header("Set-Cookie", cookie)
        self.end_headers()
        self.wfile.write(body)

    def _session(self):
        for part in self.headers.get("Cookie", "").split(";"):
            k, _, v = part.strip().partition("=")
            if k == "hb_session":
                s = PANEL_SESSIONS.get(v)
                if s and s["exp"] > time.time():
                    return v, s
        return None, None

    def do_GET(self):
        if not self._allowed_ip():
            return self._send(403, {"ok": False})
        if self.path == "/":
            return self._send(200, PANEL_HTML.encode("utf-8"), "text/html; charset=utf-8")
        if self.path == "/api/state":
            _, s = self._session()
            if not s:
                return self._send(401, {"ok": False})
            return self._send(200, panel_state(s["csrf"], full=False))
        if self.path == "/api/screen":
            _, s = self._session()
            if not s:
                return self._send(401, {"ok": False})
            return self._send(200, panel_screen())
        if self.path.split("?")[0] == "/api/stream":
            _, s = self._session()
            if not s:
                return self._send(401, {"ok": False})
            return self._stream_mjpeg()
        if self.path == "/api/ping":
            cfg = load_config() or {}
            web = cfg.get("web") or {}
            return self._send(200, {"ok": True, "app": "HostsBlocker", "pc_name": socket.gethostname(),
                                    "script_version": SCRIPT_VERSION, "script_build": SCRIPT_BUILD,
                                    "ip": local_ip(), "port": int(web.get("port") or 8765),
                                    "pairing_code": str(web.get("pairing_code") or "101-701")})
        self._send(404, {"ok": False})

    def do_POST(self):
        if not self._allowed_ip():
            return self._send(403, {"ok": False})
        try:
            n = int(self.headers.get("Content-Length", 0))
            data = json.loads(self.rfile.read(min(n, 65536)) or b"{}")
        except Exception:
            return self._send(400, {"ok": False, "error": "Неверный запрос"})
        if self.path == "/api/login":
            return self._login(data)
        tok, s = self._session()
        if not s:
            return self._send(401, {"ok": False})
        if self.headers.get("X-CSRF") != s["csrf"]:
            return self._send(403, {"ok": False, "error": "CSRF"})
        if self.path == "/api/logout":
            PANEL_SESSIONS.pop(tok, None)
            return self._send(200, {"ok": True})
        if self.path == "/api/screen":
            return self._send(200, panel_screen(force=True))
        if self.path == "/api/stream_start":
            params = stream_touch(
                duration=data.get("duration", 25.0),
                fps=data.get("fps"),
                w=data.get("w"),
                h=data.get("h"),
                quality=data.get("quality"),
                binary=data.get("binary"),
                channel="lan"
            )
            return self._send(200, {"ok": True, "stream": stream_params(), "lease_until": params["until"]})
        if self.path == "/api/stream_stop":
            stream_stop()
            return self._send(200, {"ok": True})
        if self.path in ("/api/toggle", "/api/limit", "/api/reset", "/api/fulllock",
                         "/api/fulllock_schedule", "/api/add", "/api/delete"):
            return self._send(200, panel_mutate(self.path, data))
        self._send(404, {"ok": False})

    def _stream_mjpeg(self):
        """Непрерывный MJPEG-поток экрана ПК для приложения родителя (Wi-Fi, без P2P)."""
        boundary = "kidsguardframe"
        interval = 0.0
        try:
            # Клиент подключился к потоку — держим аренду живой, пока он читает кадры.
            stream_touch(duration=10.0, channel="lan")
            self.send_response(200)
            self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=%s" % boundary)
            self.send_header("Cache-Control", "no-store")
            self.send_header("Pragma", "no-cache")
            self.send_header("Connection", "close")
            self.end_headers()
            self.close_connection = True
            try:
                self.connection.settimeout(8.0)  # чтобы «мёртвый» клиент не держал поток вечно
            except Exception:
                pass
            deadline = time.time() + 180.0
            while time.time() < deadline:
                params = stream_params()
                frame = agent_screen_frame() or capture_screen_frame(
                    params["w"], params["h"], quality=params["quality"]
                )
                if not frame:
                    time.sleep(0.25)
                    continue
                data, ctype = frame
                self.wfile.write(b"--" + boundary.encode("ascii") + b"\r\n")
                self.wfile.write(
                    ("Content-Type: %s\r\nContent-Length: %d\r\n\r\n" % (ctype, len(data))).encode("ascii")
                )
                self.wfile.write(data)
                self.wfile.write(b"\r\n")
                self.wfile.flush()
                stream_touch(duration=10.0, channel="lan")  # живой клиент продлевает аренду
                interval = 1.0 / max(1.0, params["fps"])
                time.sleep(interval)
        except Exception:
            pass
        finally:
            try:
                self.wfile.flush()
            except Exception:
                pass

    def _login(self, data):
        ip = self.client_address[0]
        f = PANEL_FAILS.get(ip, [0, 0])
        if f[1] > time.time():
            return self._send(429, {"ok": False, "error": "Слишком много попыток. Подождите 5 минут."})
        cfg = load_config() or {}
        ok = bool(cfg.get("hash")) and check_password(str(data.get("password", "")),
                                                      cfg["salt"], cfg["hash"])
        if not ok:
            f[0] += 1
            if f[0] >= 5:
                f = [0, time.time() + 300]
            PANEL_FAILS[ip] = f
            time.sleep(1)  # замедляем перебор
            return self._send(401, {"ok": False, "error": "Неверный пароль"})
        PANEL_FAILS.pop(ip, None)
        now = time.time()
        for k in [k for k, v in PANEL_SESSIONS.items() if v["exp"] < now]:
            PANEL_SESSIONS.pop(k, None)
        tok = secrets.token_urlsafe(32)
        csrf_tok = secrets.token_urlsafe(16)
        PANEL_SESSIONS[tok] = {"exp": now + 12 * 3600, "csrf": csrf_tok}
        self._send(200, {"ok": True, "csrf": csrf_tok, "session": tok},
                   cookie=f"hb_session={tok}; HttpOnly; SameSite=Strict; Path=/; Max-Age=43200")


def panel_screen(force=False, w=640, h=360, quality=60):
    """Один кадр экрана ПК (Base64). force=True — запросить свежий кадр прямо сейчас."""
    req_path = os.path.join(PUB_DIR, "screen_req.flag")
    scr_path = os.path.join(PUB_DIR, "screen.png")
    started = time.time()
    try:
        os.makedirs(PUB_DIR, exist_ok=True)
        agent_online = bool(fresh_agents())
        if force:
            try:
                with open(req_path, "w", encoding="utf-8") as f:
                    f.write("1")
            except Exception:
                pass
            # Агент пользовательской сессии просыпается раз в секунду — ждём его кадр.
            wait_until = started + (1.5 if agent_online else 0.0)
            while agent_online and time.time() < wait_until:
                if os.path.exists(scr_path) and os.path.getmtime(scr_path) >= started:
                    break
                time.sleep(0.08)
        got_fresh = os.path.exists(scr_path) and os.path.getmtime(scr_path) >= started
        stale = (not os.path.exists(scr_path)) or (time.time() - os.path.getmtime(scr_path) > 8)
        # Прямой захват из этого процесса нужен, только если агента нет (иначе из сессии
        # SYSTEM получился бы чёрный кадр и затёр настоящий рабочий стол).
        if force and not got_fresh and not agent_online:
            frame = capture_screen_frame(w, h, quality=quality)
            if frame:
                with open(scr_path + ".tmp", "wb") as f:
                    f.write(frame[0])
                os.replace(scr_path + ".tmp", scr_path)
        elif not force and stale and not agent_online:
            frame = capture_screen_frame(w, h, quality=quality)
            if frame:
                with open(scr_path + ".tmp", "wb") as f:
                    f.write(frame[0])
                os.replace(scr_path + ".tmp", scr_path)
        if os.path.exists(scr_path):
            with open(scr_path, "rb") as f:
                blob = f.read()
            return {
                "ok": True,
                "screen_b64": base64.b64encode(blob).decode("ascii"),
                "fmt": "image/jpeg" if blob[:3] == b"\xff\xd8\xff" else "image/png",
                "ts": int(os.path.getmtime(scr_path) * 1000)
            }
    except Exception as ex:
        return {"ok": False, "error": str(ex)}
    return {"ok": False, "error": "Скриншот пока недоступен (сеанс пользователя не активен)"}


def panel_state(csrf, full=True):
    """Состояние ПК для панели/телефона.

    full=False — «лёгкий» пакет без 30-дневной истории активности: он уходит по P2P
    каждые несколько секунд, чтобы не гонять сотни килобайт и не будить радио телефона.
    """
    cfg = load_config() or {"entries": []}
    normalize(cfg["entries"])
    u = PANEL_ST.get("u") or load_usage()
    fl = dict(default_full_lock(), **(cfg.get("full_lock") or {}))
    web = cfg.get("web") or {}
    today_iso = datetime.date.today().isoformat()
    act_raw = load_activity(today_iso)
    act_out = {}
    for user, d in act_raw.items():
        procs, titles = top_activity(d, n=12, min_seconds=5)
        act_out[user] = {
            "date": today_iso,
            "procs": [{"name": p, "seconds": int(s)} for p, s in procs],
            "titles": [{"title": t, "seconds": int(s)} for t, s in titles]
        }
    act_by_date = {}
    if full:
        hist_raw = load_activity_history()
        for day_key, users_map in hist_raw.items():
            if not isinstance(users_map, dict):
                continue
            day_users_out = {}
            for user, d in users_map.items():
                if not isinstance(d, dict):
                    continue
                procs, titles = top_activity(d, n=12, min_seconds=5)
                day_users_out[user] = {
                    "date": day_key,
                    "procs": [{"name": p, "seconds": int(s)} for p, s in procs],
                    "titles": [{"title": t, "seconds": int(s)} for t, s in titles]
                }
            if day_users_out:
                act_by_date[day_key] = day_users_out
    if act_out:
        act_by_date[today_iso] = act_out
    agents = fresh_agents()
    return {
        "csrf": csrf,
        "pc_name": socket.gethostname(),
        "pc_id": pc_id(),
        "script_version": SCRIPT_VERSION,
        "script_build": SCRIPT_BUILD,
        "local_ip": local_ip(),
        "port": int(web.get("port") or 8765),
        "pairing_code": str(web.get("pairing_code") or "101-701"),
        "computer_today_sec": int(u["today"].get("__computer__", 0)),
        "computer_total_sec": int(u["total"].get("__computer__", 0)),
        "active_windows": agents,
        "full": {
            "now": fl["now"],
            "blocked": full_blocked(cfg, u),
            "text": full_lock_text(cfg, u),
            "has_account": True,
            "child_account": cfg.get("child_account", "") or detect_active_username(cfg),
            "daily": int(fl.get("daily") or 0),
            "total": int(fl.get("total") or 0),
            "from": str(fl.get("from") or ""),
            "to": str(fl.get("to") or "")
        },
        "entries": [
            {
                "name": e["name"],
                "enabled": e["enabled"],
                "blocked": blocked_now(e, u),
                "text": limit_text(e, u),
                "limit": e["limit"],
                "domains_count": len(e["domains"]),
                "programs_count": len(e["programs"]),
                "ips_count": len(e["ips"]),
                "domains": e["domains"][:15],
                "programs": e["programs"][:15],
                "ips": e["ips"][:15],
                "today_sec": int(u["today"].get(e["name"], 0)),
                "total_sec": int(u["total"].get(e["name"], 0))
            }
            for e in cfg["entries"]
        ],
        "activity_date": today_iso,
        "activity": act_out,
        "activity_by_date": act_by_date,
        "stream_active": stream_is_active(),
        "stream_params": stream_params(),
        "ts": int(time.time() * 1000)
    }


def panel_mutate(path, data):
    name = str(data.get("name", "")).strip()
    if path == "/api/fulllock":
        with PANEL_LOCK:
            cfg = load_config()
            if not cfg:
                return {"ok": False, "error": "Нет конфигурации"}
            raw_acct = str(data.get("child_account", "")).strip()
            if raw_acct and raw_acct.lower() != "user":
                cfg["child_account"] = raw_acct
            elif not cfg.get("child_account") or str(cfg.get("child_account")).lower() == "user":
                detected = detect_active_username(cfg)
                if detected:
                    cfg["child_account"] = detected
            fl = dict(default_full_lock(), **(cfg.get("full_lock") or {}))
            fl["now"] = bool(data.get("now"))
            cfg["full_lock"] = fl
            save_config(cfg)
            u = PANEL_ST.get("u") or load_usage()
            locked_now = full_blocked(cfg, u)
            _write_blocked_flag(locked_now)
            threading.Thread(
                target=apply_full_lock,
                args=(cfg.get("child_account", ""), locked_now),
                daemon=True
            ).start()
            return {"ok": True}
    if path == "/api/fulllock_schedule":
        with PANEL_LOCK:
            cfg = load_config()
            if not cfg:
                return {"ok": False, "error": "Нет конфигурации"}
            acct = str(data.get("child_account", "")).strip()
            if acct:
                cfg["child_account"] = acct
            fl = dict(default_full_lock(), **(cfg.get("full_lock") or {}))
            try:
                fl["daily"] = max(0, min(100000, int(data.get("daily") or 0)))
                fl["total"] = max(0, min(10000000, int(data.get("total") or 0)))
                fl["from"] = str(data.get("from", "")).strip()[:5]
                fl["to"] = str(data.get("to", "")).strip()[:5]
                if "now" in data:
                    fl["now"] = bool(data.get("now"))
            except (TypeError, ValueError):
                return {"ok": False, "error": "Минуты — целые числа."}
            if bool(fl["from"]) != bool(fl["to"]) or any(
                    fl[k] and _hm(fl[k]) is None for k in ("from", "to")):
                return {"ok": False, "error": "Время укажите парой «с» и «до» в формате ЧЧ:ММ."}
            cfg["full_lock"] = fl
            save_config(cfg)
            return {"ok": True}
    if path == "/api/reset":
        send_reset(name or "__computer__")
        return {"ok": True}
    if path == "/api/add":
        with PANEL_LOCK:
            cfg = load_config()
            if not cfg:
                return {"ok": False, "error": "Нет конфигурации"}
            normalize(cfg["entries"])
            raw_dom = data.get("domains") or ""
            raw_prog = data.get("programs") or ""
            raw_ips = data.get("ips") or ""
            d = parse_domains("\n".join(raw_dom) if isinstance(raw_dom, list) else str(raw_dom))
            p = parse_programs("\n".join(raw_prog) if isinstance(raw_prog, list) else str(raw_prog))
            try:
                ip = parse_ips("\n".join(raw_ips) if isinstance(raw_ips, list) else str(raw_ips))
            except ValueError as ex:
                return {"ok": False, "error": str(ex)}
            if not name or not (d or p or ip):
                return {"ok": False, "error": "Укажите название и хотя бы один домен, .exe или IP."}
            for e in cfg["entries"]:
                if e["name"].lower() == name.lower():
                    e["domains"] = sorted(set(e["domains"]) | set(d))
                    e["programs"] = sorted(set(e["programs"]) | set(p))
                    e["ips"] = sorted(set(e["ips"]) | set(ip))
                    e["enabled"] = bool(data.get("enabled", True))
                    save_config(cfg)
                    return {"ok": True}
            cfg["entries"].append({
                "name": name,
                "enabled": bool(data.get("enabled", True)),
                "domains": d,
                "programs": p,
                "ips": ip,
                "methods": {k: True for k in METHODS},
                "limit": default_limit()
            })
            save_config(cfg)
            return {"ok": True}
    if path == "/api/delete":
        with PANEL_LOCK:
            cfg = load_config()
            if not cfg:
                return {"ok": False, "error": "Нет конфигурации"}
            normalize(cfg["entries"])
            before = len(cfg["entries"])
            cfg["entries"] = [e for e in cfg["entries"] if e["name"] != name]
            if len(cfg["entries"]) == before:
                return {"ok": False, "error": "Запись не найдена"}
            save_config(cfg)
            return {"ok": True}
    lim = None
    if path == "/api/limit":
        try:
            lim = {"daily": max(0, min(100000, int(data.get("daily") or 0))),
                   "total": max(0, min(10000000, int(data.get("total") or 0))),
                   "from": str(data.get("from", "")).strip()[:5],
                   "to": str(data.get("to", "")).strip()[:5],
                   "close_tab": bool(data.get("close_tab", True))}
        except (TypeError, ValueError):
            return {"ok": False, "error": "Минуты — целые числа."}
        if bool(lim["from"]) != bool(lim["to"]) or any(
                lim[k] and _hm(lim[k]) is None for k in ("from", "to")):
            return {"ok": False, "error": "Время укажите парой «с» и «до» в формате ЧЧ:ММ."}
    with PANEL_LOCK:
        cfg = load_config()
        if not cfg:
            return {"ok": False, "error": "Нет конфигурации"}
        normalize(cfg["entries"])
        for e in cfg["entries"]:
            if e["name"] == name:
                if path == "/api/toggle":
                    e["enabled"] = bool(data.get("enabled"))
                else:
                    e["limit"] = lim
                save_config(cfg)  # движок подхватит изменение за ~2 секунды
                return {"ok": True}
    return {"ok": False, "error": "Запись не найдена"}


def panel_start(port):
    srv = http.server.ThreadingHTTPServer(("0.0.0.0", port), PanelHandler)
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    if IS_WIN:  # входящие разрешены только из локальной подсети
        run_hidden(["netsh", "advfirewall", "firewall", "delete", "rule", f"name={PANEL_RULE}"])
        run_hidden(["netsh", "advfirewall", "firewall", "add", "rule", f"name={PANEL_RULE}",
                    "dir=in", "action=allow", "protocol=TCP", f"localport={port}",
                    "remoteip=LocalSubnet", "profile=any"])
    return {"srv": srv, "port": port}


def panel_sync(st):
    web = st["cfg"].get("web") or {"enabled": True, "port": 8765}
    try:
        want = (bool(web.get("enabled", True)), int(web.get("port") or 8765))
    except (TypeError, ValueError):
        want = (True, 8765)
    cur = st.get("panel")
    if cur and (not want[0] or cur["port"] != want[1]):
        try:
            cur["srv"].shutdown()
            cur["srv"].server_close()
        except Exception:
            pass
        st["panel"] = None
        if IS_WIN:
            run_hidden(["netsh", "advfirewall", "firewall", "delete", "rule", f"name={PANEL_RULE}"])
    if want[0] and not st.get("panel") and st.get("panel_try") != (want, st["mtime"]):
        st["panel_try"] = (want, st["mtime"])
        try:
            st["panel"] = panel_start(want[1])
        except Exception:
            log_error("panel: " + traceback.format_exc())


def local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


# ====================== УДАЛЁННЫЙ P2P-МОСТ ЧЕРЕЗ ИНТЕРНЕТ И WI-FI (MDM) ======================
# Позволяет родителю управлять политиками блокировки ПК из приложения KidsGuard Pro
# как в домашней сети Wi-Fi, так и через Интернет (мобильная сеть 4G/5G, работа, другой город)
# по общему коду привязки (pairing_code, по умолчанию 101-701) и паролю администратора.
P2P_TOPIC_ROOT = "kidsguard/eb6ecfcf/v33_p2p"
P2P_TOPIC_ROOT_V34 = "kidsguard/eb6ecfcf/v34_p2p"
# Публичные MQTT-брокеры. Держим соединение с ОБОИМИ одновременно: телефон может
# подключиться к любому из них, и раньше стороны могли «разойтись» по разным брокерам
# (команды и кадры экрана просто не доходили).
P2P_BROKERS = (("broker.hivemq.com", 1883), ("broker.emqx.io", 1883))


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
    var = _mqtt_utf8("MQTT") + bytes([0x04, 0x02, 0x00, 0x1E]) + _mqtt_utf8(cid)
    return bytes([0x10]) + _mqtt_rem_len(len(var)) + var


def _mqtt_sub_pkt(pid, topic):
    var = bytes([(pid >> 8) & 0xFF, pid & 0xFF]) + _mqtt_utf8(topic) + bytes([0x00])
    return bytes([0x82]) + _mqtt_rem_len(len(var)) + var


def _mqtt_pub_pkt(topic, payload_str, retain=False):
    var = _mqtt_utf8(topic) + payload_str.encode("utf-8")
    hdr = 0x31 if retain else 0x30
    return bytes([hdr]) + _mqtt_rem_len(len(var)) + var


def _mqtt_pub_pkt_bytes(topic, payload_bytes, retain=False):
    """PUBLISH с бинарным payload (кадр JPEG без Base64 — меньше трафика и нагрузки)."""
    var = _mqtt_utf8(topic) + payload_bytes
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


def p2p_bridge_loop(st):
    """Поднимает по одному P2P-мосту на каждый публичный брокер (hivemq + emqx)."""
    for host, port in P2P_BROKERS:
        threading.Thread(target=p2p_bridge_worker, args=(st, host, port), daemon=True).start()


def p2p_bridge_worker(st, host, port):
    """Удалённый P2P-мост MDM для синхронизации правил и лимитов ПК через Интернет."""
    roots = [P2P_TOPIC_ROOT, P2P_TOPIC_ROOT_V34]
    fails = 0
    while True:
        cfg = st.get("cfg") or load_config() or {}
        web = cfg.get("web") or {}
        if not web.get("enabled", True):
            time.sleep(5)
            continue
        code = str(web.get("pairing_code") or "101-701").strip() or "101-701"
        sock = None
        try:
            sock = socket.create_connection((host, port), timeout=10)
            sock.settimeout(2.5)
            cid = f"hb_pc_{code}_{secrets.token_hex(4)}"
            sock.sendall(_mqtt_connect_pkt(cid))
            hdr = _mqtt_read_exact(sock, 1)[0]
            rl = _mqtt_read_rem_len(sock)
            if rl > 0:
                _mqtt_read_exact(sock, rl)
            if (hdr & 0xF0) != 0x20:
                raise RuntimeError("CONNACK failed")
            fails = 0

            for idx, r in enumerate(roots, 1):
                sock.sendall(_mqtt_sub_pkt(idx, f"{r}/pc_cmd/{code}"))

            last_pub = 0.0
            last_full_pub = 0.0
            last_screen_pub = 0.0
            while True:
                now = time.time()
                cur_cfg = st.get("cfg") or load_config() or {}
                cur_web = cur_cfg.get("web") or {}
                if not cur_web.get("enabled", True):
                    break
                cur_code = str(cur_web.get("pairing_code") or "101-701").strip() or "101-701"
                if cur_code != code:
                    break

                if now - last_pub >= 4.0:
                    last_pub = now
                    # Полную историю за 30 дней отдаём редко; в обычном пакете только «сегодня».
                    want_full = (now - last_full_pub) >= 60.0
                    if want_full:
                        last_full_pub = now
                    state_payload = panel_state("p2p", full=want_full)
                    state_payload["type"] = "PC_STATE"
                    state_json_str = json.dumps(state_payload, ensure_ascii=False)
                    for r in roots:
                        # Лёгкий пакет retained (его получают новые подписчики), полный — только живьём.
                        sock.sendall(_mqtt_pub_pkt(f"{r}/pc_state/{code}", state_json_str, retain=not want_full))
                    # Одиночный кадр для превью (не чаще раза в 30 с и не во время потока).
                    if not stream_is_active() and (now - last_screen_pub) >= 30.0:
                        last_screen_pub = now
                        scr_auto = panel_screen(force=False)
                        if scr_auto.get("ok"):
                            scr_json_str = json.dumps({
                                "type": "PC_SCREEN",
                                "pairing_code": code,
                                "pc_id": pc_id(),
                                "screen_b64": scr_auto["screen_b64"],
                                "fmt": scr_auto.get("fmt", "image/png"),
                                "ts": scr_auto.get("ts", int(time.time() * 1000))
                            }, ensure_ascii=False)
                            for r in roots:
                                sock.sendall(_mqtt_pub_pkt(f"{r}/pc_screen/{code}", scr_json_str, retain=True))

                try:
                    h = _mqtt_read_exact(sock, 1)[0]
                    rem = _mqtt_read_rem_len(sock)
                    body = _mqtt_read_exact(sock, rem) if rem > 0 else b""
                    ptype = h & 0xF0
                    if ptype == 0x30 and len(body) >= 2:
                        tlen = (body[0] << 8) | body[1]
                        pos = 2 + tlen
                        if ((h >> 1) & 0x03) > 0:
                            pos += 2
                        raw_msg = body[pos:].decode("utf-8", "ignore")
                        if raw_msg.startswith("{"):
                            cmd_json = json.loads(raw_msg)
                            action = str(cmd_json.get("action", ""))
                            data = cmd_json.get("data") or {}
                            pwd = str(cmd_json.get("password", ""))
                            auth_error = ""
                            if action in ("/api/toggle", "/api/limit", "/api/reset", "/api/fulllock",
                                          "/api/fulllock_schedule", "/api/add", "/api/delete"):
                                # Если на ПК задан пароль панели — команда ОБЯЗАНА его содержать.
                                # Раньше пустой пароль проходил проверку, и чужое приложение
                                # с кодом по умолчанию 101-701 могло менять правила на этом ПК.
                                if cur_cfg.get("hash") and not (
                                        pwd and check_password(pwd, cur_cfg["salt"], cur_cfg["hash"])):
                                    auth_error = "ПК защищён паролем: укажите пароль панели в настройках ПК"
                                else:
                                    res = panel_mutate(action, data)
                                    if not res.get("ok", False):
                                        auth_error = str(res.get("error", "Ошибка выполнения команды"))
                                st["mtime"] = 0
                                st["sig"] = None
                            elif action == "/api/stream_start":
                                # Просмотр разрешаем по коду привязки (пароль нужен только
                                # для ИЗМЕНЕНИЯ правил), иначе поток пропадал у пользователей,
                                # которые не вводили пароль панели в приложении.
                                st["stream_broker"] = (host, port)
                                stream_touch(
                                    duration=data.get("duration", 25.0),
                                    fps=data.get("fps"),
                                    w=data.get("w"),
                                    h=data.get("h"),
                                    quality=data.get("quality"),
                                    binary=data.get("binary")
                                )
                            if action == "/api/stream_stop":
                                stream_stop()
                            if action == "/api/screen":
                                # Снимок — просмотр, не изменение правил: пароль панели не требуем.
                                scr_res = panel_screen(force=True)
                                if scr_res.get("ok"):
                                    scr_json_str = json.dumps({
                                        "type": "PC_SCREEN",
                                        "pairing_code": code,
                                        "pc_id": pc_id(),
                                        "screen_b64": scr_res["screen_b64"],
                                        "fmt": scr_res.get("fmt", "image/png"),
                                        "ts": scr_res.get("ts", int(time.time() * 1000))
                                    }, ensure_ascii=False)
                                    for r in roots:
                                        sock.sendall(_mqtt_pub_pkt(f"{r}/pc_screen/{code}", scr_json_str, retain=True))
                                last_screen_pub = time.time()
                            if action in ("/api/toggle", "/api/limit", "/api/reset", "/api/fulllock",
                                          "/api/fulllock_schedule", "/api/add", "/api/delete", "/api/sync",
                                          "/api/stream_start", "/api/screen"):
                                is_sync = action == "/api/sync"
                                state_payload = panel_state("p2p", full=is_sync)
                                state_payload["type"] = "PC_STATE"
                                if auth_error:
                                    state_payload["auth_error"] = auth_error
                                state_json_str = json.dumps(state_payload, ensure_ascii=False)
                                for r in roots:
                                    sock.sendall(_mqtt_pub_pkt(f"{r}/pc_state/{code}", state_json_str, retain=True))
                                if is_sync:
                                    scr_now = panel_screen(force=True)
                                    if scr_now.get("ok"):
                                        scr_json_str = json.dumps({
                                            "type": "PC_SCREEN",
                                            "pairing_code": code,
                                            "pc_id": pc_id(),
                                            "screen_b64": scr_now["screen_b64"],
                                            "fmt": scr_now.get("fmt", "image/png"),
                                            "ts": scr_now.get("ts", int(time.time() * 1000))
                                        }, ensure_ascii=False)
                                        for r in roots:
                                            sock.sendall(_mqtt_pub_pkt(f"{r}/pc_screen/{code}", scr_json_str, retain=True))
                                    last_screen_pub = time.time()
                                last_pub = time.time()
                                last_full_pub = time.time() if is_sync else last_full_pub
                except socket.timeout:
                    sock.sendall(bytes([0xC0, 0x00]))
        except Exception:
            # Мёртвый брокер не должен долбиться каждые 3 секунды — плавный backoff.
            fails += 1
            time.sleep(min(45, 3 * fails))
        finally:
            if sock:
                try:
                    sock.close()
                except Exception:
                    pass


def p2p_stream_loop(st):
    """Поднимает отправителя живого потока на каждый брокер (кадры уйдут только в тот,
    через который телефон реально запросил просмотр)."""
    for host, port in P2P_BROKERS:
        threading.Thread(target=p2p_stream_worker, args=(st, host, port), daemon=True).start()


def p2p_stream_worker(st, host, port):
    """Отдельное P2P-соединение для ЖИВОГО ПОТОКА экрана ПК.

    Держим его отдельно от командного канала, чтобы кодирование/отправка кадров
    (5–10 кадров в секунду) не задерживала команды блокировки и синхронизации.
    Работает только пока жива аренда потока (stream_p2p_active()).
    """
    roots = [P2P_TOPIC_ROOT_V34]  # новые версии приложения слушают v34 — видео не дублируем
    while True:
        if not stream_p2p_active():
            time.sleep(0.5)
            continue
        # Если телефон запросил поток через конкретный брокер — второй не дублирует трафик.
        target = st.get("stream_broker")
        if target and target != (host, port):
            time.sleep(0.4)
            continue
        cfg = st.get("cfg") or load_config() or {}
        web = cfg.get("web") or {}
        if not web.get("enabled", True):
            time.sleep(2)
            continue
        code = str(web.get("pairing_code") or "101-701").strip() or "101-701"
        sock = None
        try:
            sock = socket.create_connection((host, port), timeout=10)
            sock.settimeout(5.0)
            sock.sendall(_mqtt_connect_pkt(f"hb_stream_{code}_{secrets.token_hex(4)}"))
            hdr = _mqtt_read_exact(sock, 1)[0]
            rl = _mqtt_read_rem_len(sock)
            if rl > 0:
                _mqtt_read_exact(sock, rl)
            if (hdr & 0xF0) != 0x20:
                raise RuntimeError("CONNACK failed (stream)")
            try:
                sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            except Exception:
                pass
            seq = 0
            next_frame = time.time()
            while stream_p2p_active():
                cur_cfg = st.get("cfg") or cfg or {}
                cur_web = cur_cfg.get("web") or {}
                if not cur_web.get("enabled", True):
                    break
                if (str(cur_web.get("pairing_code") or "101-701").strip() or "101-701") != code:
                    break
                cur_target = st.get("stream_broker")
                if cur_target and cur_target != (host, port):
                    break
                # Держим ровный темп: если сеть не успевает — кадр пропускаем, но НЕ копим
                # очередь, иначе видео начинает отставать на секунды и «зависает».
                now = time.time()
                if now < next_frame:
                    time.sleep(min(0.15, next_frame - now))
                    continue
                params = stream_params()
                frame = agent_screen_frame() or capture_screen_frame(
                    params["w"], params["h"], quality=params["quality"]
                )
                if not frame:
                    next_frame = time.time() + 0.2
                    continue
                data, ctype = frame
                seq += 1
                ts = int(time.time() * 1000)
                if params.get("binary"):
                    # 4 байта seq + 8 байт timestamp (мс) + JPEG
                    payload_bytes = struct.pack(">IQ", seq, ts) + data
                    for r in roots:
                        sock.sendall(_mqtt_pub_pkt_bytes(f"{r}/pc_stream_bin/{code}/{pc_id()}", payload_bytes, retain=False))
                else:
                    payload = json.dumps({
                        "type": "PC_STREAM_FRAME",
                        "pairing_code": code,
                        "pc_id": pc_id(),
                        "seq": seq,
                        "ts": ts,
                        "w": params["w"],
                        "h": params["h"],
                        "fmt": ctype,
                        "b64": base64.b64encode(data).decode("ascii")
                    }, ensure_ascii=False)
                    for r in roots:
                        sock.sendall(_mqtt_pub_pkt(f"{r}/pc_stream/{code}", payload, retain=False))
                _write_stream_flag()  # агент продолжает снимать экран, пока идёт поток
                interval = 1.0 / max(1.0, params["fps"])
                next_frame = max(next_frame + interval, time.time())
        except Exception:
            time.sleep(2)
        finally:
            if sock:
                try:
                    sock.close()
                except Exception:
                    pass


def udp_discovery_loop(st):
    """Отвечает на локальные UDP-пинг запросы от приложения KidsGuard Pro в домашней Wi-Fi сети."""
    while True:
        try:
            u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            u.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            while True:
                cfg = st.get("cfg") or load_config() or {}
                web = cfg.get("web") or {}
                if web.get("enabled", True):
                    pkt = json.dumps({
                        "type": "PC_BEACON",
                        "pc_name": socket.gethostname(),
                        "pc_id": pc_id(),
                        "ip": local_ip(),
                        "port": int(web.get("port") or 8765),
                        "pairing_code": str(web.get("pairing_code") or "101-701")
                    }).encode("utf-8")
                    try:
                        u.sendto(pkt, ("255.255.255.255", 49153))
                    except Exception:
                        pass
                time.sleep(5)
        except Exception:
            time.sleep(5)


# ---------- установка фоновой работы ----------
def engine_command():
    """Копирует программу в защищённую папку и возвращает команду запуска."""
    os.makedirs(BIN_DIR, exist_ok=True)
    if getattr(sys, "frozen", False):
        src, dst = sys.executable, os.path.join(BIN_DIR, "HostsBlockerEngine.exe")
        cmd = f'"{dst}"'
    else:
        src, dst = os.path.abspath(__file__), os.path.join(BIN_DIR, "hosts_blocker.py")
        py = sys.executable
        if py.lower().endswith("python.exe"):
            py = py[:-10] + "pythonw.exe"
        cmd = f'"{py}" "{dst}"'
    run_hidden(["taskkill", "/F", "/IM", "HostsBlockerEngine.exe"])  # освободить файл
    try:
        shutil.copy2(src, dst)
    except Exception as ex:
        log_error(f"copy engine: {ex}")
    # SYSTEM и администраторы — полный доступ, обычные пользователи — только чтение
    run_hidden(["icacls", BIN_DIR, "/inheritance:r", "/grant:r",
                "*S-1-5-32-544:(OI)(CI)F", "*S-1-5-18:(OI)(CI)F",
                "*S-1-5-32-545:(OI)(CI)RX"])
    return cmd


def install_background():
    """Задачи планировщика (SYSTEM) + автозапуск агента. Возвращает список ошибок."""
    if not IS_WIN:
        return []
    errs = []
    cmd = engine_command()
    ensure_pub_dir()
    for name, sched in ENGINE_TASKS:
        r = run_hidden(["schtasks", "/Create", "/TN", name, "/TR", cmd + " --engine",
                        "/RU", "SYSTEM", "/RL", "HIGHEST", "/F"] + sched)
        if r.returncode != 0:
            errs.append(name)
    try:
        with winreg.CreateKeyEx(winreg.HKEY_LOCAL_MACHINE, RUN_KEY, 0,
                                winreg.KEY_SET_VALUE | winreg.KEY_WOW64_64KEY) as k:
            winreg.SetValueEx(k, RUN_NAME, 0, winreg.REG_SZ, cmd + " --agent")
    except Exception as ex:
        errs.append(f"автозапуск агента: {ex}")
    run_hidden(["schtasks", "/Run", "/TN", ENGINE_TASKS[0][0]])
    try:
        subprocess.Popen(cmd + " --agent", creationflags=NO_WINDOW)  # агент в этом сеансе
    except Exception as ex:
        errs.append(f"агент: {ex}")
    return errs


def uninstall_background():
    if not IS_WIN:
        return
    for name, _ in ENGINE_TASKS:
        run_hidden(["schtasks", "/End", "/TN", name])
        run_hidden(["schtasks", "/Delete", "/TN", name, "/F"])
    run_hidden(["taskkill", "/F", "/IM", "HostsBlockerEngine.exe"])
    try:
        with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, RUN_KEY, 0,
                            winreg.KEY_SET_VALUE | winreg.KEY_WOW64_64KEY) as k:
            winreg.DeleteValue(k, RUN_NAME)
    except Exception:
        pass


# Встроенные картинки (PNG, base64): логотипы, плитки и переключатели.
# Размеры подобраны под масштаб экрана 100%–300%, поэтому Pillow не нужен.
ASSETS = {
    "youtube": {
        44: "iVBORw0KGgoAAAANSUhEUgAAACwAAAAsCAMAAAApWqozAAADAFBMVEX/AAAAAAD/AAD//v7/AAD/AAD/AAD/Jib/AAD/AAD/AAD/GBj/Nzf/Vlb/mJj/uLj/eXn/1NT/6ur/gID/qKgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACc1K6TAAABAHRSTlP9AAP/uEtw/9Mtif////////////8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAALBSDcgAAAOxJREFUeNrllckOwyAMRI0NCZC1af//WwsEVSnELOfMCUVP1sTYA4gOwVNgLOofxnLJiEM8o7KSkVUYq8FZdtBQ0oCBBs+ihIp0oMH/WpX1tPPqYGGhQdKBgMJAk5RAB8s22BlxNpKP08zQxnlOXYy0TowPECqD6dhvm30PE72WDphomztgyqwXYTreHTB9pmY4dV2A837w8N7eunVkLiW/7u1+OjycDtK48IPUNaKYmeaWVvh5bivtlzAsrG6Ajd/BEAW6iY0hg5UFl7+QCZFkBsmU19KaS3zF4CtE6DUYT5KPUMzDvBLOT3itvudFCGGu6v9SAAAAAElFTkSuQmCC",
        55: "iVBORw0KGgoAAAANSUhEUgAAADcAAAA3CAMAAACfBSJ0AAADAFBMVEX/AAAAAAD/AAD//f3/AAD/AAD/pqb/AAD/AAD/AAD/AAD/GBj/trb/SEj/VVX/aWn/enr/19f/Jib/NDT/5+f/jo7/nZ3/zMwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAsdbf4AAABAHRSTlP+AAX/17D/LJFObf////////////////8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAATHMzdAAAAShJREFUeNrtltuSwiAMhgMBgdbW1l3d93/T5WCnVTklV174z/Sm8A2TBP4EBE/w5T6Nk21luOPPovZNsP8wzuqa7GB2MnGB0tCWdnHvxkkhLfRJywRCwhR0y0QQYh4J2AMMnLAUDFQoCHjMAU2DSJwicuAT4j9DxcKBnhsyK/NYLUbkcgU//16qmQn5zIV3QlynWoBlDvH8w+MQbyOPw0KYTQ7xb+JxiKcrj0OcWdw6s85bWPHdR04+S5Wvc+uFdV+WsXY/C+8hV7UXLucuy9R+f4713gXRBTd/EXmjqMkmPyMbWvLP4NckzD38WtCs0G39IYK6k1Jm70exIw1dQVp57H+xGUrfblVV1smtbR76e3M0eGrw8DRONIaCwwZ4W+oYQr5z3cdw/7DDDA8hX3XoAAAAAElFTkSuQmCC",
        66: "iVBORw0KGgoAAAANSUhEUgAAAEIAAABCCAMAAADUivDaAAADAFBMVEX/AAAAAAD/AAD//v7/AAD/AAD/AAD/AAD/NDT/AAD/AAD/xsb/Fxf/Jyf/rKz/Zmb/d3f/iIj/3d3/UVH/SUn/np7/4uIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAD3bkWxAAABAHRSTlP+AAT/15xsrv80Tf///////////////wAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAK5M1ZwAAAVZJREFUeNrt19tygyAQBuBlOQkxjTm07/+oRcRg0iILO9PphTuTi0TnE0HZPyDYBQdxEH9AILkKxOuB/dqem4nwIzqvCWUcbhHILBoF1NIuXnNLhK8GmkrjakAS0EJjKZcMSIKC9koGLGvZI6zGQtguAVR8QmC+DQ+dpUUkggTdNd8KcAaxDGMmbD8BYTHj5/c6TwTCR8IVjg7y9KgSJhKmSEj5ca4QtkpIeR3ZhLxd2ISUp4FNhCkZ2USYEj5RmpIWIkzJnU3IiUt8jswbKS0smbjduYs6cR+t3VeNQuw93STi60J62X2ZqLzpy+a5s/EN1f1m3fhQKOb2G/qI6RdsagKMVuQjIVB0D8OuPbW3safWHjt7sZXUm0iOKK5feAYl1y3kuNYaU55RK4dGIXwDogy+h8YlijqjFaGscSLHzp8BGgUpf2M5xtMC+Mt5x7+ig/i/xDcu0BB+7EqHggAAAABJRU5ErkJggg==",
        77: "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",
        88: "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",
        110: "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",
        132: "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",
    },
    "instagram": {
        44: "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",
        55: "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",
        66: "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",
        77: "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",
        88: "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",
        110: "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",
        132: "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",
    },
    "tiktok": {
        44: "iVBORw0KGgoAAAANSUhEUgAAACwAAAAsCAMAAAApWqozAAADAFBMVEUSEhIAAAD9/v4RERESEhIOBwdq/fv+c48UzcgRERESEhLZG0AWFhb0HEcRERETWVct/v3r5+kSEhIYe3gpFhpMGCJoFyeL+vmpGDX93OMODg4XJiUTlZET1c8U7eY5GiBL//9sureWHDSQ29rN+vj2IUzvPWT+i6Tvmq39qbr9s8Ln1doYTk0Jo54f/voxCBAzMzMwWlknaGcrrqwtsq9pT1RtbW25OVO9QVq1UmfWGz/BwcDM397P4uH/Q2z/XoH/YH/+gJr/rsEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAALidjtAAABAHRSTlP+AP+axf////9Tsv8P/3H///8v/////////yn///////////////////////////8F////////////////////////AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAL146PgAAAUlJREFUeNrNldlWgzAQhkMSrIlhVShUa21rd/d9r/r+DyWFsgRSGO78rrj4zpyZCecfpLUApR8uIzsweyXZ5agGyoqyiRogIpMJaoSLrWwiADyRXQSCxbJyNn2DPOVNJPeU7sXd06N/XSqNNKase2ljHHaCo+JGIlm1Cut22MURvxOvOKJa7u/hhJmR16Y75P1UxtNRC/nHaCEvjAFcXhoeTF6GGM/zCevl7+nw/TUA9tw5kF68SZZ/jv8t94FyvABgZd26H384zgoi6ydnOKNB1g9tDJX149NuUX6rla2r86Js1MpSF7MXr76ynVcODR+Be15MBgi6DedrVEk7JMditmdnHVRiN5Ld0gs+jD+d1fzZVyRjFIy0+m+gUijGiI3MYJFrJmFOIC7dJr+gADc9E5rgoJOSnjZGm/qVjiYjnCrhhInyhYXwB9BOFG2BCgMyAAAAAElFTkSuQmCC",
        55: "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",
        66: "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",
        77: "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",
        88: "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",
        110: "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",
        132: "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",
    },
    "telegram": {
        44: "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",
        55: "iVBORw0KGgoAAAANSUhEUgAAADcAAAA3CAMAAACfBSJ0AAADAFBMVEUpqu4AAAD8/f4ttPkbpewA//9Vqv8pq+44u/3G2ekpq+4prO8pqewpq+9NufAAAP8Af/8tp+4Aqv/R6PZqxPMqrPGR0/Y8f7y24vnk7fSm3Pjb8fyDzvUAf38qsPUjodsssvYqsvd///8fn98nmNwxnPcrsPN/f/8AVf8Aqqo/v784xv8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADzPcoSAAABAHRSTlP+AP/9/wEDSwj/c84ykf8BAhQD//+2/wT//////wLNFzNHAggPCnUCAwMECQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAabVXLwAAAsVJREFUeNqNlomWqjAMhlO7UNlaUNBxRked9d77/u93U5bSQll6jh4RPpL8SdMAmS4u5HW4kJIHnoHJP3+E+f4n00cURbIwF6Xga1ypCJFRlgAAxQWQZJHE/8Uip0tC0gwaoFvmdxV9EF7Oc/jS6OYwA5vE6MoMpwRJqwDVkreI8GuI05rEEKZaMrs6Ji2nCcnmqYas3gd5wGLv1TKG4C21YMdpVSRrmPE17V1tOX4qq3UMQShMQJaTa7FZMJFPZTlB4m0Yghk59Rzn6QaCdWDUhAiNufXgGBzr3lOtGk6QaAVjLK9fd5fWIo1NMpD70cspQFPnHa5j5ykUSiH3S1K6ZqpZeS9NjNIAfuZz0Jsy63XIxY8mwFWxbsqsM7N3UsKhnFGFQX7euau2HDoqIVwqjL4dBuTFkwU3BtHAyURNNHVxLR08WRpFUZdiHJVnCo3tfVnaAEfc2BQa209kMbUGbvbGpnDtO2z35nIxcoOc9DyC0Mce88IbcU6Sex8ttqMLHGb64vk4YAdY4AxqSfRx/xKqlo4blxlWV+ejg3mytHp+BUqsNj66mCcLYFsDRQK7j9XopZcS/z7WS7A+2bEryrAsieLIhfZDPkrkhfk9TWJdh/YfbYOyrh6ZH14JM93MbFhqBApUS/Jp+ku4nZ3bCjGBTqola/qL4nKqKLt0jzJ6GMuCu0ibPngKdfljbiOq3R7RmGv7J3nyxQaKvnqyFOa8bvv8csNmOfVqUwznUbb5PKq4sOeYEjzZeP5hk9fOecuLbSCm/Ome7yW2mQ0gbiDpzxOnDSAdsGF+EeRzxVUzTcjpvFSSU7Zk0ow9f0PzmW6mullj/mTnzoM42H3ENDgPQlag6LPzJ6a0+E7AQ/FFSZaOJ9fRvKvM3XtctdNuN/Dev9BFvjJf82askfdH/B3H8SNtFBR6fS43I7z2roSaPvMfKZ8f9MeRIpcAAAAASUVORK5CYII=",
        66: "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",
        77: "iVBORw0KGgoAAAANSUhEUgAAAE0AAABNCAMAAADU1xmCAAADAFBMVEUpq+0AAAD9/v4stPobpewA//89vv7H2ekpq+5Vqv8prO8Af/8pq+8pqu0oqe0pq+5vxvNMuPDO5vUCqfwnp++U1PY/f78Af3/k7fSCzfW44/nb8PwqsPSk2/crltUAAP8/v78rsPR/f/9///8AqqoQtvYqme8qsfYzzP9Vqqofn98rsfYrsPUAVVUkkbYkttorsvgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAApMgG7AAABAHRSTlP+AP/+/wEF/5IDtAJzSy/T////BBL/BAL/////zP8JAQSTAgIDCAwzBQMIT7MDBwd0AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAImo3EwAAA/dJREFUeNqdmAlzszgMhi1ADuYMgZAmbdP7+o7d/f+/bmUIh7ExGM90poXkqS7L8svAvhBFzdtV1zEufJrZXsZcTJ688/JpEy3m0pKKp2nyEobhS5imFZcv6tKZJuRXsiTKmT8sxqL/vr7pBUcXWokS1ZCYsuhBHmbSwNW0mKL1O2JT0gB8SDg8iXW0Z4D0YQbVAVlyonQv0/AdssjKaoEPKQVkiUYfSNgiq7HvzydwO41DFa1hteZl0+SyCSx7WAuT5iXwhLM0ASlbD5O88IrxDK2ExIklcdEvQCPt6g6TuGoUOzaKWeoOk86OMtvTELItMMIlQ911NIwrto3G/BR+JjQO0UYYGVF1iWVdbSRbYYTL4W1MQ9wQtGAcOjGiCWc/A1Y8XoY/q/bIYLc94Duy9gfPewxGZSJ62muc+05mHT259oOvLGu6HXM1rTWrWYVSw6KnrY1ab1azlFefcGpofyFzNkuuS6DuCN7Q+KpaU82S66jQIogbWgzLOZiYpSVBFgmBGP0sORoExaOnr2K6+TnRlhwlsy4q5s6QBHIVBdGsGZXRup/YdHP5EEw++wHIED9mw6abRWt3Z0iCNC6DH4ZQzkZLM4u87GDTJMg2V7PavBGMZpF7PWyShLalG2lms6SXA+zedECwZy2lM2ZJL3dD0T0G2umFMeMQqrQZs6SXI5iWBGroJ9Boe29m7RSYV2i03EDTtmPvpQLzfLaKxgL/bPJShd2zlTSZh7Pm5W6nPLkEbE3cDLw7HeadA4NtOFe9kncYvJzCtJ1gqV6lp+1MMD0JLe0ElbVHmmEH4zhSz+/6lnf0TDB9J9xoIGJbfzvLJOjlp4dNtnJc6r2FeWMUhnLDV+q9pXUMnKGZkyDPLKxsZ9atBezPC0lgGdnVnKehhXa4xSkorEnw8ys25+m7zdXg2AU98A8LtdvOIXE576pMav/l43wSyNF2DrFntRhZ0ntruDbA621GQqxsSR1t8Ju3hgaSYjdx2Y1TY9TEUe/iUTtHN7Sn8nv1cBnsTUnIIB6m6NJhugz8i+EoFep9Idp+X+hH8o6Gr1W+GUfd4029Gf1svbQ1k9vz9A74sfVuRPms9fvpp3W7Wi5Z/76hTsP6e0MmfBrbhOkmjvjLGafCFJUghqujs1ISeZ5TMOiu5JQKP/oHxLy6gtxFEfGTEwqrjiRWSz8+Fe1UyNQ0rpWylM/CK4hl/Y2CV4ULPN+PMgBcpQ1SaWcvbFaDoxdRatYujbolkrs8MWqN9Ch/+ZL6iYOmKqVLKamOFdVGVf0TfqE8lxz1XuTyv1fZ7zCM8maFYZo2khGPt2jRvRSNeOokzxPHrcq2jCDnnSn0m1gQyv8HLVIy/OKamrAAAAAASUVORK5CYII=",
        88: "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",
        110: "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",
        132: "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",
    },
    "genshin": {
        44: "iVBORw0KGgoAAAANSUhEUgAAACwAAAAsCAMAAAApWqozAAADAFBMVEUnJlgzK2YAAADzx2A9MG4eIk8YGlgkJFU2LGj+0WBCNHK2lmHMqGDMqV4iHmWzlV49MG8eIU5TRWNuW2PZtF8AAH86NFtEPFqOdl2QeGFkVlsYFWNORFt1Y1yBamPZs2AAAFV3Y2Ojh2LjumAbIU8gHl4yKmMxK2NVAFVVVVWcgl6dgmKmi10AAP88KWcxKmM3N26DblwdHVodIk4cIUwdIk4rKFwsKV0qKFssKF5BMXRBMnRBMXNVVap/AH8AAD8JDlcqKlUuKV4/MHI+MG8/MXFCL3FCM3VBMXRfUlxgTme+oGAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABB/ZJhAAABAHRSTlP+/gD//v3/nMX//f//////xJj///8C/////////////wP///8t/1CwAwP///8BEo4G/whferQvTGbFdLnOAwIE/wykVZK5G1WQ////AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAGpHO9QAAApFJREFUeNqF1YlW6jAQANAkU0bSVdtC2REQEcF9355vX///f94krbRpi845Gk+8ncwkhTCho9US78TJUToy/XtfiIOrPYqdmri/pWQFLA5uGqwuuAr2cJdnbonrRhqMbXnk7DLDr2LvXar54vxI4X1xzT6ypL+KI8H2xUE1bzup1n4oloRvKhaHHaz02Tw/YVli4394HGBlTyg1E1esZBuIXoxYtvyM8B4zLGJysQYIOgOGhuWLFBdtz4PYA/BoGBqWN0u4wQJYD7An5QUmHXDbBZvhgo3jBBkGEgJapP0PQm7inUIRcYBqGSoj1jV1wd6GMYh1TwlQDNRffWe8BWMPkoYeJYAcznW50PXrsbfG9EgUPta9+SOozdwY6KWRjkRhF3VKC6I6jBfq2Np/OgHocLoRddd3HG6VMeLntdc7pn2QMsVqdKeR4/pp1dYGt3s6YebyUBPuamKbeBgUkhYwzYydUWhg1VfgqSdkXoYE15k4btafZTTYcXHOkl7H2TQYFhs0MBuA/jDN566ybnYYs1FdZsbG6YeJr9Q+T9OE0ebtMDEOIX0lI4UnWeLp2zaXMjPXQ4VDVYbeAH+Vn7a1a+I2dPtqfgwwU/n8iQzfEnNbY57rEBxOjTmSRoqVjDbWqmBuj2Fk9SdS0hLRGP7mtpJZzXZphx31M4Mpz20F62mf07tD2+yM7KIl/IPwPTe/S9QD7kyVbvHcWpb9nfBtxVr+1M1lZi37m2At8cBNSzHqFnBm7VPBluKOly0Pw3Jey34UL+rLnFrkW+PNPtOVRdfE8rL5kbXt55909ejb6stiq9Xetp9OxafN1XbYrFlaqTQehbIZbonzw7NFsxy7Kp5+/zrNbs0s81K8Gy/p8B/ski/R+vF7PwAAAABJRU5ErkJggg==",
        55: "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",
        66: "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",
        77: "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",
        88: "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",
        110: "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",
        132: "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",
    },
    "vpn": {
        44: "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",
        55: "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",
        66: "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",
        77: "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",
        88: "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",
        110: "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",
        132: "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",
    },
    "app": {
        44: "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",
        55: "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",
        66: "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",
        77: "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",
        88: "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",
        110: "iVBORw0KGgoAAAANSUhEUgAAAG4AAABuCAMAAADxhdbJAAADAFBMVEUAAABjZvH+/v5+fv5XW/BVVfwAAP9kZ/RjZvNkZvNjZvFmZvdiZvFjZvFdYPBlZcz///9xdPaXmvXV1vvm5vxVVao/P79WcufJyvqEhvS0tfhVZuipq/ddY+p+gPM/P/8/f/+/wPlaWtZ/f7+qqv8A//9eZe5/f38AAH8/X78/f79OYtcAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA6GAvEAAABAHRSTlMA/v8D/wQBz6suiw9qTf8FAf////8DBAn///8T/y7/BAT/CAQDAU8CAggEDQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAvXEYLgAAA8VJREFUeNrtmolu2zAMhilLsmTLZ+zcS9O7O97//SY7Wec0ii7LHrb1D1AgRtAPlEmKIgXwqf9LdIVxPBMrpvjExDOwVh0kT3lG5Rc8LRGX8o/IOJJiPOlMpFMt6htdAZR5zzqJZT1xOcULk4sHScrQpdK8Mzqw3+Bjt4hDwwYmpklQYu+J3/IU3RTPRChPxf0iZgzpxRPa/XaciS8da61exKtF7f1m5W8iPSq9Q0PMHwEqLxPjVdx5hz3r7Kk+fnPOHRx56OQ3DvmmC2fAGUO+4smzfIv0zYJV4Z7F0SidgrFPDVq3x47eoTExl4tarQxAkYVgDTKcxrY4SVFQ8QxXt3GQ1WFxNQMtDgWWHpfPiYthHxqXAdbg1p+4vxY3q6vInMoD4xIdroTASQwtIZ4Th+fFrf8FXCE/8+FI3WyJO24Jid+21kbRQmUfx0+67RyEl3GbSGqnsC+FUovzsY7sougGbwJccaZFUVMXk+PIInpXO7l1ZPubFu3ccFUlxtA25HpDeNJWmuC2JRR1pFlKhATgoLhmSCvc9h9nXLHR0wLjBrRImcV8caoMRb4MaAuCAuJqog24GzQTjqrLdpmm7oiOtlPTuHjWn5OVcd4nxQ+8C1pz67ylN06JK9ApKd4VFw+HtCIcrkDt+b9ePGwsaD64ulXlqAHt+rWOwJFGkROHD6MtQZNYJ3nFdcBpaEYc4Ht28939ss+ahnIwdFYwMGTgXdAeiP6E4I67DLENqa1pZuuoqtK8DOnWmsaEsfW3NPK0++klDYx9v4pSZslr68KwlKVF8xkLO15bE/+D69Bb9la8uyC0jndAZt4XLa1Owbpfq+6NkQd7GmL7srJvD6t7cY09bQ32NABh4LUG2iM4zROqMmYaXmtsb5TgpGqpCocCLSSwWRgC7hVW4Ch5slTZR4rtFulXUoa3x2RmCT+U61kYYHbJJFjvloPwGznd+/A4eE/VSneeLBf8Z3gYUsdOP63GDH5Lx45jgkeNmeXCuPASGDnUrijYz4QOMHraS18O1hMmv4Dz7JOlprLL1j0TO9pTmDm9sAk/mUxC3QsQsbHNyQ4Br69gU1uVUQh4dSU28Sg8h7zSUVHBtHuOgKBSF5/vJeUx9P2Y5W1eJreq8PeM4DC6gHXRUV0MMnE/ze2mpYonQ2Cqu1SKYpeVMN1dMQG8djx9jwr37x/CL3cvYF30FUrmctQfHw5iSHuEiTXoeHJcAszG41DNcUdTwOvoAtaN1+1G+xjDPMKYm5raQcMP9of5aH1xPSOtryY+9af1E61ZOFUKXZB4AAAAAElFTkSuQmCC",
        132: "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",
    },
    "app72": {
        72: "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",
        90: "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",
        108: "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",
        126: "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",
        144: "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",
        180: "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",
        216: "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",
    },
    "tile0": {
        44: "iVBORw0KGgoAAAANSUhEUgAAACwAAAAsCAMAAAApWqozAAADAFBMVEVjZvIAAABjZvBiZvFVVfx/f/8AAP9jZvJjZ/NjZPBjZvJkZPH///8A//9ZaOo/P79VVapfX99/f3+qqv8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAxUuWDAAABAHRSTlP+AJrFAwIBU7ItcQ0BARMEAwgCAwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA7ScmZgAAAL5JREFUeNrtlckOgzAMRD2xswe6/f+/1glVe2mJuVa8AyjiyTGXGcLAe+zQZHvTeOohR/eDknVYeMseOdEOHIGwycGj0AQnWIZ8gaMpqUpTWeZzh60iSchkIkJIkGwy10YwDu6jCdEqO5WdVU5HZD7lUz7lP5V7FJQjIXMkvjzYKNegKWoMu4JHD3PTL3K7BkKQm2ERXrV6tFMWrNM8T1UrbVSbtlbkyb7d3XpQb/DRJf5KcrEC90/DQrDL6/MTnVQEgMtCENwAAAAASUVORK5CYII=",
        55: "iVBORw0KGgoAAAANSUhEUgAAADcAAAA3CAMAAACfBSJ0AAADAFBMVEVjZvEAAABjZvBiZvF+fv9VVf4AAP9mZ/NjZvJkZPNjZfFjZvN/f7////9VVapscP8AAH8/P79VVdRdZu5/f3+qqv8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAMHJlaAAABAHRSTlP+AEh6AwMBK9MTk6wEAQP/AgQGHgIDAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAI4oDOwAAAPhJREFUeNrtlssSgzAIRSEB8qp99/9/tcRoOp3pVHGdu1AXOUPABQdwDRNuhVnWT1jeT6U4uX8JSc/RN8eIIUfYSHHXevLDMYYCexIDknSO0cHe+AZW7oQe9icLN85STXPJWkg5kgS2OC0FeJZi5GKaBHSUYI3OBgizmYsyAaZo5iBof8mO6WTA9hN6g4Mb3OAGN7jB/ebCAa7uIzrA6f57YTFjkep+dwfaI73n3byok4h6iHkyfvYXfXgTVqoTNj/LF4v10NQ4sThM4dldZ49U0MW9vTXlXXz3hslvk9E/Fm3tfl2dMjj/Ly4QrprcOb2rbPo8Unf+NydGBghIHDpdAAAAAElFTkSuQmCC",
        66: "iVBORw0KGgoAAAANSUhEUgAAAEIAAABCCAMAAADUivDaAAADAFBMVEVjZvEAAABiZvBrbP1VVf1/f/9jZvNjZfFjZvIAAP9iZe8/P79VVapjZvL///8/f/+qqv9aWtkAAH8/P/9dZu5tbdp/f79scP9tkf8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABPX4elAAABAHRSTlP+ADMJBAKtcc4BWAQDmQEEAwcCBB4HBP8HAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAJHUVNAAAAT1JREFUeNrt2MlywjAQBNBpaUaL5Y3s//+lGdmhYhOqbEs5qg8UpuCBxhzUImwiMgmOk6TfXtLv057zoz3K1/Jl/ISI+qL1ztFhgvMmK4+EAubEx+9xHTDtCQYPdCnOgLcEwwS6mu7HoFXoqCB+nUcmehiickOJUWwoI2iZhxIzhkKBwk2iEly6jHUpSYkEV04QI5LgrULQOzsr4WsIh0R166DAkXoJNQRZHaetEvSv0YhGNKIRjWhEIxrx/wRXEy/1G6XK7RrNqN40SlLC1BBaBkirRM0w8gYaH2V9Zs2gLYByYSz/GXaUhSifhm7i12KVSpcy3ItVrnclN/bTWY6bkllgDIJxV3UvF9UOMe4K9yt6fwUZLCQ+1P4euHXunOK8Bfjv4UM+OgBbcxj7nt84Pju/QJwmnArLuLn6BnhmCFmxVDPNAAAAAElFTkSuQmCC",
        77: "iVBORw0KGgoAAAANSUhEUgAAAE0AAABNCAMAAADU1xmCAAADAFBMVEVjZvEAAAB+fv5iZvFjZvF/f39VVf4AAP9iZfFjZ/JjZfFjZvJkZ/T///8/P78/f/9dZOtVVapscP+qqv8AAH8/P/9VVdRIbdpaYuZfYu9mZsz/AP8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABdRvruAAABAHRSTlP+AAOW2wIDAU4yEnSuAQQEJwP/AwIEBgcfQwUBAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA08TdtwAAAXpJREFUeNrtmNmOwyAMRW1DbSCky+zL///nOCXtJJ2OJgmW5oUrVVWReuSFFw7gPEIHWpoDy82/YfaLGNeF58ApjQgxxdC5hQkxDcD7ND2OzsOq5PiB1P+k6VnMsD4+TMqDa2FPHWxLTkhzGuNbhq3x75fqCo3wCDUJY3VnGkkCqMTxhSbEuZIGUWikMXa1MPDChXbCCPXphl6VxpQNaJC0V9BtWJQ2FFdozoTmX0RpkrwJDY54AjFqdGj1GfYYjGgOCcjgso2D63uwWoJmh4Y032iN1miN1miN1miN1miN1mj/TrN9y7DZO2ugiX5sEnAPyJJtaIwC+Gg0OHd+Owu9mjx3I/LZEpgUl0fnoF8Gk0u0G10N1z/ti/qBYrgq79yDK1qqOK7KvQ4wmfm3UHk5Zm6Q8Lh1FV5nJjfeUoe4TT64b9E4caraeepW79bFqaOd+l495dg5vzTOBcJx/ndcdH8oVX7u/k5PV+P8m9nWi0wkS7U20a0o/wIgcAztWQfQNAAAAABJRU5ErkJggg==",
        88: "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",
        110: "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",
        132: "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",
    },
    "tile1": {
        44: "iVBORw0KGgoAAAANSUhEUgAAACwAAAAsCAMAAAApWqozAAADAFBMVEUQuIEAAAAQxIkPuYAPuIAPu4IA//8AqlUAqqoQu4IA/38LunwA/wAAf38PvIMMq3kMuX8Pt34PuIIPtn4PuH8Pt38ZzH8AzJkA/6oAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACnWDmGAAABAHRSTlP+AP6axbQBAwNTAgsBAnEQLVMucI6kCgUDAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/+he9gAAANFJREFUeNrtlckOwyAMRG2zhK1Zu/3/l3Ygak4NIdcq7wBCerLMZYa4EAJXsG69qZx4jF7t0M8Yljb5xrMh2YW0x/RVToF7EqogooZsQ46sqmrRzbOzkDteDl3YD4jU8UgtiGcH2UiTrF+WeKY2ZMLOkzTKd8iqVTZnZH3Jl3zJfyrnKFjOhMx4Ir5C0o2jYyLHvi1Fe0QuMrrli6ItwpyTi8eLiI72ljvF8nCU52IGVFqpNvSQ11KBejThtwdRbtEro39ilI+5KreGZcdV3uv1Aeb+BvNYG6UjAAAAAElFTkSuQmCC",
        55: "iVBORw0KGgoAAAANSUhEUgAAADcAAAA3CAMAAACfBSJ0AAADAFBMVEUQuYEAAAARxYkQuYEOt34FvX4Af38AqqoPuYEPuoEAqlUPuYMA/wAA//8PuoEPu4ISuogHrH8RtH0Ptn8OyZMQxIkAfwAPt34QuH8A/38AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAoGQrtAAABAHRSTlP+AP56SAkCA0nTAy0BAZasFQspcQcnAoeuAgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAApnlYHgAAARpJREFUeNrtlumOhSAMRvsBLaC43GW293/RKepdZjK5Wv9N4pcQieYUwcQewi0NYy0x3qe0XEX0NqeUwt9JIb3Vwl8/OS3U9pncqxAN6R3xmYs4D6QPVuJcbiEPrkHaQE0keUhZOIbfRk1kX+LMRYTtmIIfupBygpFMcUE3RjoGZwMzdx01OBsxch5MjN7M5dIRrpnMaXV/ox3TkyHbR7hvkJB2cuHgDu7gDu7g/gW39z/f7uxHVztX+180t/fa4CsXdvV3yClbQS5SPcR4Mu6iqjT50sXkS5/VCWc/M6iIy6N0M1dkO+iG02SSs38KUnYb0KqRs4AuvssY/Yruat3sGfUln/y60dEm/yqTYMdfXo6Oy6rPg+U2+wZKRwjvx/dyhAAAAABJRU5ErkJggg==",
        66: "iVBORw0KGgoAAAANSUhEUgAAAEIAAABCCAMAAADUivDaAAADAFBMVEUPuYEAAAABto8OuX4QxIgCvX0AqlUAqqoPuoIPuoEAf38PuIIPuYEA//8PuoEA/wAPuoIA/6oPtX4Ptn4A/38Aqn8OwoUAf1UAn38TxHUMwoUk2pEk/7YAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADkkRWuAAABAHRSTlP+AAgz/gUDA63OAjZvAZkBWQNWdgIGMgYIDRUHBwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAMiu2/QAAAWVJREFUeNrt2NmOwyAMBVCbYLZAlm6z/v93jk01k7SKJg28YqlVI9FTTPrgG8BVBaKA+0Vk1pewfDRW3r/U/0Xf+cfsBjGyrKJzsFvexZsozwQDvQOt9T4hi9zE/TwSBml45esL4zo0a8Ji7w8AGYET2oWweIKDgiDney+Qu+iPA4vBRLp8+CICdD4PkDaGMgG0vwYhStvIRuRWgF+umAC48i4CqnIA9IQzEMaKTWiHBHV9gLcjmOBrCJDjrDmK/NeoJaARjWhEIxrRiEY04qF6Jt7rRhQmAtUNSgprxzWwWD00BoIL9jVHwRO0zOA1h6FwlBl8Kh/jB5wlTFxs+TZUCkIE7Eo3keOZBCvCky5sg37j3YxnXXJDyaa/kGkwHo6IeiBMq6g7Hw2qGiYc00PgJnyL/tW8zOt4CzQ+xX5OizQ5v98PL3DxE9FuPHyYJTervtupm5LbYNPW8wscifClMpRWVz9l8wq6O7nOJwAAAABJRU5ErkJggg==",
        77: "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",
        88: "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",
        110: "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",
        132: "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",
    },
    "tile2": {
        44: "iVBORw0KGgoAAAANSUhEUgAAACwAAAAsCAMAAAApWqozAAADAFBMVEX0nQoAAAD+pwvznQv0nQr//wD/qgD/fwDwlwL4oQr/AAD4oQr0nAr0nAr3oAr4oQv0nQrzmwr4oQp/fwC/fwCqqgDfnwDijQD4oQ0AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADpSfUSAAABAHRSTlP+AP6bxAEDAg+RAbMvUlTMsXFxAgQDCAkmAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAApGElEwAAANJJREFUeNrt1bkOgzAQBNDd9W0TG8j5/1+awURJg4xpI6YAIZ44mhniGmO4EWPXM9UjLl45BLWREOYBPH6x4yFJIyWDrxifMItQIyLhuWjgG6smrTx5a4AtT7sWegQkG4cOC31hS5bHPqy9Ie57cH008aUXK2DVi8cjWJ/4xCf+U7xUwXSkZI7Ul4u6rxjJR7RoX9nJhMpFR/f8omhzj4Tdeux/iBRvXB0g9mlvgJLHpNVpww7l0po2meNi1x3EG1wOqeiNlBSyZ77+FpYtN/O5/QY/tgcu6KDfNgAAAABJRU5ErkJggg==",
        55: "iVBORw0KGgoAAAANSUhEUgAAADcAAAA3CAMAAACfBSJ0AAADAFBMVEX0nQsAAAD+pwv0mwr0nQr+qQH/fwD//wD/AAD2mgj4ogr1ngv1nQr/vwD4oQv0nAn4oQv0nQn5ogu/fwD/VQD3owt/AADUqgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACR3u/2AAABAHRSTlP+AP5IegYCAQEQKZPUBNIucq6rBANLAgYAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAJf4+owAAARVJREFUeNrtltluhDAMRX1NnI2d6bT//6eNh2VUqQXc59wXUPCR5fDgQ9gjHlcRifsrbU/v9bg5S05a95MTILcD8VmIl+enVr45wbjol/OUimGEjwcnaC6hHXXwYeM83E1MyTbIymk3uh9u8VDOR2ELR9yUVoSPOJsw4i6FQOUqbVgBHXrq0Zq5LgZC6qwcUS7ziZnSmyHbTzgGrFzlKle5ylXudy7/cx8JmRvyiLKnZ/u+7SOJfcDXfof/Mi5q5hR88RBktraT1ZecyZdmdcLVzwwqwkNSsVMuKHjXB5f0cteXR0aPZrhBFgGdNuXdfPeBNHXnuqvWOqXVPt9+rU6ZGze5v/PMPXZNPrgyZLz0efSH838DJckI8TFMg8UAAAAASUVORK5CYII=",
        66: "iVBORw0KGgoAAAANSUhEUgAAAEIAAABCCAMAAADUivDaAAADAFBMVEX0nQoAAAD8owv+kgD0nAn/fwD+qQH//wD0nQr/vgD/AAD0nAvznAr1nQr5ogn4oQr/VQD4oQqqqgD0nQr4oQr5ogrUfwD5ogr/2gAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAetf1zAAABAHRSTlP+AP4IMwMFAc0EAVdyqzKvA28DmpjUBl4HAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAXmAVxwAAAWRJREFUeNrt2NtugzAMBmDbiR0SCpQetr3/k86BVtCuEuDsMpaKCoJPOdz4D+CqODLjdnG8rG9h+XuRfI1uo34mRT4QQR+6cRhgq6jpxj4r74QC/QC0t4bvxYCn4G5EsLsU8ShrQtDfDwAz0j4MmIWWDgoZOWF8EjoGgzAZPBOBXWMRdC4ezxMheDUJeX+FkxLn5I3CYyqgv85OgKQEjAJmAnRnA0Q82QWgDhlK5jEtaIIzNyUEOF1OV7AUavhKVKISlahEJSpRiX8m+tzxFY+CixolIG2UCts1ECxuGpkhoC8hNFBoD472xSAS7X61B2/txFVzRM4jYh2GDiLkJKDD6MlmzPEMpsRpm4pOIy7xzrKx1DkJq5B5osMh88YYXqKuBtVDUVfXIYWXwM34Nd5zlN73Pd0carJ7jf0XTf3t0OyI7QDd6BDl7+FDys9Eer9RvXP5xfDp/EIz754DkPx9DKu7Xwu1C4KIx4WEAAAAAElFTkSuQmCC",
        77: "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",
        88: "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",
        110: "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",
        132: "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",
    },
    "tile3": {
        44: "iVBORw0KGgoAAAANSUhEUgAAACwAAAAsCAMAAAApWqozAAADAFBMVEXwREQAAADuRETvQ0P/AAD/VVXwQ0PxRET/f3/vQ0PwQ0PxOzvTPDz1R0d/AAC/Pz+qVVUAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADRNY9hAAABAHRSTlP+AJrFAQNTsgItcQwJEgIEAwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAdXyNoAAAALpJREFUeNrt1UsOgzAMBFBPbOcLbe9/2joBtZuWmC1iFiDEk5VsxoSRnHEQ0e1N42kfJYY/qcWGyQcLSqKDcOxmYMmoNElYujb8RKBp0qpiWOdzhzZIIoVciVBSJB/mJgTn4D6aEL04GA5enM5gvvGNb3xR3KugnimZM/WVhZ3YilG9ZVetcq2jXVdkeVnlii6Og/AqMhYQlmmfp4a8rTbbQ5En5+1234MZLYbEP5NCbMDju2GhOMz++w3iKgRBB2vrlAAAAABJRU5ErkJggg==",
        55: "iVBORw0KGgoAAAANSUhEUgAAADcAAAA3CAMAAACfBSJ0AAADAFBMVEXvREQAAADtQ0PvQ0P/AAD/VVX7PT3zRUXxRETyRkbvRETxRET/f3+/Pz9/AACqAACqVVXUKioAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAeov57AAABAHRSTlP+AEh6AQMHK9MWk6wCBAIDAwYAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA2ygFCAAAAPJJREFUeNrtlkFuhDAMRf0d20kIM23vf9k6A2FUqRow6/wFRChPls3CjzCihrOoyjjS/n76F638Kbn6vfUvp0BeCp2k8bPffHOK3OhKSobIwSmYriZhlZ17INH1LKIbF6n2Ar2Qc6tUioW9FOFLWpArVYR8lBRNgpFhCXPF68FKmKPs/Vkc88lQ8CeMBic3uclNbnKT+5/LNzi+ucd8/ylaGCvW9zvfaM8I8hNe1N0n8B2eTHr5iz9SCGvdCTc/i6hIt56NkzUANsPwwQ5yudrbpry77z5g6Zwsqe7aevh1d8rM6VM4G4YmHxzE5NTn8b7zC8gmBchTkgVpAAAAAElFTkSuQmCC",
        66: "iVBORw0KGgoAAAANSUhEUgAAAEIAAABCCAMAAADUivDaAAADAFBMVEXvREQAAADwRET9R0f/VVX/AAD8Pj7xRES/Pz/vQ0PwRETuQ0P/f3+qVVXwRETaSEh/AACqKirfPz/UVVUAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADaZa8SAAABAHRSTlP+ADMJAwEGrQRxzlgCA5kHAgYIBgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA7gxKjwAAATlJREFUeNrt2MtygzAMBVDJluQn0Mf//2tlSFKSZgaQu7QWmcCEAzJZ6BpwVyxS8biiTPtD+P06Ufv0B7X49Wb0hmgnfQoBDiuH5NoFr4QC7sTl9wpF+3kmCGWGSxUc0p4gdBmuVrkZsAkFDJVQ7sSEDsBuKMFVso2AdT2gtTEbBcjfzEqQtY2tlahExGAngJChou8Q2psFwdRDBIzQ1wdkYphq7iG0C+hbivbXGMQgBjGIQQxiEIP4f0K6iQ/pG5Q89o5rOnh2D40cdXR1PYSGAdAokbuWgtsMXuzCrClAw0Ql+2N4rmuksa9GuUUaeyvzPVi1XGR6sWEhfoRMthizbIn5EXUvB9WCzE+BW/AzXUFmj5FfYv+E+FXCOSUkj0h/Nx/W/QTy7rD80n7I7/YvkKPgqSLh3dEP/iAH9EbLjlAAAAAASUVORK5CYII=",
        77: "iVBORw0KGgoAAAANSUhEUgAAAE0AAABNCAMAAADU1xmCAAADAFBMVEXvREQAAAD+Pj7vRETwRER/AAD/AAD/VVXvQ0PvQ0P/f3/wRETyRETvRka/Pz+qVVV/f3/MMzPaSEjUVVXvPz8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAC/KkhWAAABAHRSTlP+AASW2wIBAzBOAnSuFgQDAgUHBjAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAVOf2FQAAAW5JREFUeNrtmMtuxCAMRW1DbCAw09f/f2shjxHMtGoSLHXD3URBypExZOED2IYDHY/w09fQvAXBc5EWWNNCQCTnZ3Mw3lEB/kzLy85YOJXo3jHwKy2vuQjnY31VHjwKm2a4lpiQWppginA1uTypaQEd9MRv1S005gTQiZOdxiSxkwaOeaMJzr0wsCwr7d7ZtDVz2SuUrkUFGqS8V0BWKa0UR5lGaFRo9nOpjawKDRzeQWujy1bhhl6JZjKNFC7bfuUYtA4hZ0JFmh20QRu0QRu0QRu0QRu0QRu0f6fpzjKiNmcVGmNSonm8AYrO6Az5BLjMzl5ro2Xa5Q+VcfcLy+yMolJc3JxDfih0LvHb5mok2f4Dld0j9d85s2op2ISZ74Vx4998F4xbN0jXPOPyt+eePdFKE6/JB0MP0Vg5Vc5/7Hz6bI2rHW3te/OquNnYozHGJ6wFbeuimTZZO/0dDmu/w+9mO19kIj6qtV9F+TdrFgyAd4CR5QAAAABJRU5ErkJggg==",
        88: "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",
        110: "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",
        132: "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",
    },
    "tile4": {
        44: "iVBORw0KGgoAAAANSUhEUgAAACwAAAAsCAMAAAApWqozAAADAFBMVEUOpekAAAAPsfoNpOgNpegAqfsA//8NpeoNpusAf/8OpOcNpuoAAP8Om9kAf38Af78AjcYAqqoAv78Asv8AzP8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACIZ2LQAAABAHRSTlP+AP6axQUBU7ICLXEBDwIECQMECgUAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAlUZs+QAAAMpJREFUeNrt1c0SgyAMBOAEEoKA/X//Z+2CTnupgteOe9Bx+EbjJUvcEgLvJOhyp3bFQzK/kZzAywdPnCK5zZAY+IIxQqZO/K1q4Ct718MuPjUAK+eurRqQtCQaiTNWUo5DmOQViMdejBhmNjdmnQf2g5jiESwnPvGJ/xTXVZCPLJkj62sqMoi1YIva2BQZKxc7euQXnYR7IfTWLP2aEA1TKyCeo+uVxIxKa9WGHjKhTY+DXOtv7UF84WE+ys9EbzPz5duwrLyb9fgN/XwFreunll8AAAAASUVORK5CYII=",
        55: "iVBORw0KGgoAAAANSUhEUgAAADcAAAA3CAMAAACfBSJ0AAADAFBMVEUOpekAAAAPsfsOouYOpOoEqfoAf/8A//8NpusOpuoCvfwNpekNpusAAP8Mm+8Av78AVaoAf38Af78Af9QPn98AqqoRqt0PsfcAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB+OoBOAAABAHRSTlP+AP5IeggCAdMsBJOsAQ8EAwIEBhADDyEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAARBHR7QAAAQdJREFUeNrtlsmOwyAQRLuAZjM4yaz//6fT2CGZkSKbnjN1MbL8XAIO/Qg9nxlnYa59SffnzbfX9ijf7cfhL8dAWiKdpNhb+/LJMVIhQ+eJCb4+OIal0Tj4cOcucGaYM0vgnZO2cayBUiScr0y6WKkiXFGMjotfIZAcpbKOjEOmjMVowSh9WCOpk2R/rMfkZEhx5b83OLnJTW5yk5vcay7puW0e/WeOyfxjFDUWcyXWb3Cb7/Dv6kG9Bt88JKnrePclp/Kl0pxw9zOFipjITewaFzyW8bYP+O6R1cPGkUojGrlh3XffsDo5VnMUuTfHu30+/bo5ZbLuKDZldE1+cAg5nPo8su+rH2ENB7MrwouPAAAAAElFTkSuQmCC",
        66: "iVBORw0KGgoAAAANSUhEUgAAAEIAAABCCAMAAADUivDaAAADAFBMVEUOpekAAAANpeoAt/8EqPkPsfoA//8Af70NpusAf/8NpekNpeoNpOgNpeoAAP8AqqoElNsAVaoJnfUAqtQAf38OnuYAv78AttoOsPoA2v8kkdoAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADS5YkhAAABAHRSTlP+ADMGCf4BBK0Ccc5YmQEDCQMKBgI1BAc0BwcAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAtHHk7gAAAU1JREFUeNrtmNuSgyAQRHtguOMlyd7//0MXjNloNrUq7CNdpSWUHunRh2lACznpHG3LyGE5xP1y4HyW7/JPjZ/Ty/gJQqdJ9tZiU8F6kSmPiLQ0YaEU9kjBxuRnjWAaO+x7fqZYQbxEML2GI4BJcWbgSog4LuVJ3hADCaCMYa4I7c4BZZrqgWyjU4WIcHF9QnChjR8rSIdVxQxcqIcjRoUiaUjyFYtQlgzqfCBwj5MLNUbAqZyyipB+jYZoiIZoiIZoiIZoiP9HnKsRL7K6UUrtWhVCU2XTCOtMoogKQg4UoBOFqlLo3IPHYieqI57yCJcvg7XLCFdcDRXnSFNsJdmQ93hX8mGV/WK9CJn+OKKTpFdRVxwLqiqHGb0K3JKMD7vzskLH9NY/xP4U2sdo931e6/Mewe/Nhz7PMYstffCYb9TP9i9IG0O7xFIvRt8pFAluXK0KbwAAAABJRU5ErkJggg==",
        77: "iVBORw0KGgoAAAANSUhEUgAAAE0AAABNCAMAAADU1xmCAAADAFBMVEUOpekAAAAPsfkAvv4NpukNpekAf38BqfwA//8Af/8NpekNpOcNpuoOp+wAAP8Af78KnO4JmtYAqqoNsPgAzP8kkdoAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABEyntaAAABAHRSTlP+AP4EltsCBQECME50rgEEDxIDSwUHAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAn3Q5lAAAAYdJREFUeNrtmOtugzAMRu1csB1IoN32/q86B2gFbNUoibQ/+aQKFcGR7URIOYD7JB7t2YzEh7dh908I3wtRekUTQZzCx+BOpg9TBv5O09vBeTCnAwAx3JD5J00YQ4T8xHvx/aa8B+2O3QDXEie0exrhLZqLNKPl0ZamXcJVWE6/VgfLzAjKslaXaWwpmkJcYFlphEMpDHyihXbXoRXHDLlXpZFEqBBKojSpUdpcnFWaRWdq4Lx+UQD500OVBLwD12l0blVgxL4SzaEFW2GzrYNjhlqLoOmwIs03WqM1WqM1WqM1WqM1WqM12r/T6p5lqNo5K9NYf3XS4whIHOvQJmSl1RlcblRPu0m+qhx3g9aVLQH25XvExNU56CWW40i6hSZEvnxB6eGRivfcvARPx1W4rhmWdv6tBOdQ9m7QYri4FLPMSwdvqUMcLhg9A+72FI0bpyqMNGSlep6kj7qwdbRb36t3KQzOn41zvVa1EbR7F83jcuHu77A8OnpptjGRtXxWa1t7FOXfAaUP5iKbs90AAAAASUVORK5CYII=",
        88: "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",
        110: "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",
        132: "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",
    },
    "tile5": {
        44: "iVBORw0KGgoAAAANSUhEUgAAACwAAAAsCAMAAAApWqozAAADAFBMVEWoVfcAAACnVPanVPepVf7/AP+oVvepVfgAAP+nVfapVfd/f///f/9/AP+dTuyQVeV/P7+fX9+ZZv+/P/+yTP+qVaoAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABjfY0VAAABAHRSTlP+AJrFBQFTsgEtcQICAhUKBAgFBAoDAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAVknwAgAAAL9JREFUeNrtlUkOwyAQBKdhWL1l/f9XM+A4pwTG14g62EKUWvalm1CZZzRwvL+pPuWQvPlBThIWP/KCFKiB9ZK+y25Cpg5mK7bIFxjqEh7sROZ+brVFJI6JVHgwMYJOtjdHUAaXaILXykZko5XDGdkOechD/lO5VEE+UzJn6muKVilzlBZVll2WypWOVv2idc9IsltXxYdYdksdIGzdPg8rpn3aZId8OzxHzMcOyrjdvQn2K8H4FSX3WFgwmryvX17tBKNOhiIxAAAAAElFTkSuQmCC",
        55: "iVBORw0KGgoAAAANSUhEUgAAADcAAAA3CAMAAACfBSJ0AAADAFBMVEWoVfcAAACmVfSoVPaqVf3/AP+pVvmpVfh/f/+/P/8AAP+oVPepVfh/AP9VVf+dTvd/AH9/P79/VdSZZv+/P7+yTP//f/8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABs39DrAAABAHRSTlP+AEh6CAEr0wIEAZOsAgMOAgQGBQQKAgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAANdIphAAAAPhJREFUeNrtlt1uwyAMRv2BDYaQde1+3v9RZ9rSrdLUxLnmu0hQxJFl58KHMMIFW2Gu40j3t2r/HF4lfdm972eOgbRk2kgL7/3mL8dIjfYkJ6z1wTEC7U2Eyp0riLQ/i/CN81S7gvjsnFYmX4KVIrzV5uQyi5CNkryJKFSwuLls9fCR3Rwl64/9mE2GnD9hNDi5yU1ucpOb3P9cOsCFg3vM9h+jubFcKvGBBvt+h17ci5plNQ9xTyZe/cUe0YW17oQ3P/OoSOYudp0TdYDthHV4ZFWEvLc36B/fPeMUt8kcGas8+XV3yhTiq4RUMDT5wUGKbPo8io7TD8oFBiMFPUzgAAAAAElFTkSuQmCC",
        66: "iVBORw0KGgoAAAANSUhEUgAAAEIAAABCCAMAAADUivDaAAADAFBMVEWoVfcAAACmU/m1SP6qVP2/P/+pVfioVfapVfd/P///AP9/f/+nVPYAAP+pVfd/AP+qVapVVf+WS//abf//Vf//f/9/VdSfP/+fX/+qVdT///8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACwjKsJAAABAHRSTlP+ADMHCAStcc4EAQJYAZkCAwMKBwMCBggIBgEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAC+JOpwAAAT1JREFUeNrt2NuOgyAYBOAZ/EHkoD3u6f0fdKG6WdttYoW9ZJI21dSvgL1gBFfxWntuZ9CH9SF+Px4kv5+7jZxvPyZPCJdOSm8MNmNNr7LySCRAvXD5T0wg9T0h7EbsilGUNSFUFnsTFgOzEFCQfp4L5jEAZcYwE1/+ZMsI3NYDeRBjoQD7OcVESFQoTp4K0suUE5AY4SkVQrqzF2j2NYThgLp5wErE0dsaAl1azq5KSH+NRjSiEY1oRCMa0Yj/J96riUnXbZSEtds1XFi9aZwGXKlqiFQowCNt1VK4vAcP5cKYekQqE1cpH4Y4nwlfvhphqTTlUxlv5Wypd0U31nTiViWzwBg13V3V3V1UA527K9yab/0eZBTq+FD7D+RHMK8pphdS/j58iPmciNqMnPIX3bPnF3SD5ksR7VZH3xHuCNuFVe6qAAAAAElFTkSuQmCC",
        77: "iVBORw0KGgoAAAANSUhEUgAAAE0AAABNCAMAAADU1xmCAAADAFBMVEWoVfcAAAC/P/+nVfioVfh/f/+qVf7/AP+pVfeoVPapVfiqVfgAAP9/P/+rTPb/f/9/AP+/f/9/AH////+ZM/+fT++cUu6ZZv+qVaqqVdTMZv8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB/fHynAAABAHRSTlP+AASW2wIFATBOdK4BBBECAgQCAQUQHwUDBgUAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA7GaLcgAAAYBJREFUeNrtmNtugzAMhu0c7ISEQ9ud3/9BZwrdoFs1IJZ2k/8GEYlPjhMu/AGuk3tjTmZLTqYlvvsaVm+ecF+I8iOa94ivITZ2Y2LoRuDvNFkO1sGupPCEPf+kecaQYH9cXJQHX4WdGziW1KFZ0whfEhyNlEdLWo8BShLn6q40nzuAQhzdaGwoFdIg8DDTCJtSGLhME+2tsGlTmnGvQiOfFGhA2QvNq5Q2FmeEZtCq0Nw7C40/nAoNAhKw0kbHrbbQYlSiWTRgFC7b3LgLg9YhSOSG6NFcpVVapVVapVVapVVapVVapf07TXeWIbU5a6SxTG46iTK1IXHSoT0jC02pcfY6O2ffqYy7MuxeLYFKcWl2DvJQ6BwN54k2ELnyA6WbRyq/c3bSUjALs1gKyyv/Fotgw9oNmmOe8fq3S8/ynbeUJh6TD/ZbNC6cqpc/ttl9tjYsHe3S98oqhca6rbE2drgUtGsXze1kMdn/nYuf+t0/NtuYyZx4q9Y25l6UfwLYGw1pL6nIawAAAABJRU5ErkJggg==",
        88: "iVBORw0KGgoAAAANSUhEUgAAAFgAAABYCAMAAABGS8AGAAADAFBMVEWoVfcAAACqVf6nVfioVfeZZsz/AP9/f/+mVPWpVfe/P/+qVvipVfgAAP//f/9/P/9/AP9/P7+WSuibW/eqVaqTNfCmTfR/AH9/f3+fX9+fUfGZZv+/f7//Vf8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAC/L5PvAAABAHRSTlP+AAWXQgUBAjWyBNVsAQIEAgQQCQMHGAICCDgFBAMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA61+vggAAAbdJREFUeNrt2dlywjAMBVBd2XiJ44S9+///ZuUAbdjKhKhv1gw8MMNBo9g86BKuKzueVD7fQOjyA7/qMbl80z2Atyxv3VuwU4q/it27+7DzALcm0cRKsQ2loXuwzMAaerJiy2B3C3YN9pFmVLIyymtYfszSzDI78CXssTY0u1JAcw47cCSNCqeeD7DcCB23yH4ENzBKLiU+nI0B5vnPbXTuDsMosH9hUiw7yAVeImrCxO51gLc5qLpksBrgT70nd2p5GIXDXtmlVo4ZyavVhqP80QmcozYss8iU4ZM6LBdb4I26W46ydB30YYMFqV7nCle4whWucIUrXOEKV7jCFa5whSv8PLwE68MtVpSB/9lXoOv0VzcBy39ZNiW4LB3rr1iMK+sxeGjPgsUUeKF94GJZIZelad7oLsiCWx3XvLotm581r+bCu6y8l+4EO+akeIb9745ecavXoh/HFYx3LXd3HrA0CEnFbS4jIcZ69j0ZBTejEKubHTaZDfpbsVvmWXlTtDiGNtdBYV9ywqcGkoxcinGcdxFtunJn2FozqVpbvub5zzDWsccz1fhHKe9wDxdT6qO/1cw33NcQBUc/voAAAAAASUVORK5CYII=",
        110: "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",
        132: "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",
    },
    "tile6": {
        44: "iVBORw0KGgoAAAANSUhEUgAAACwAAAAsCAMAAAApWqozAAADAFBMVEXrR5gAAAD6TKLrR5jsR5n/Var/AP/tSJrvSJr/f3/qSZjtSJn/AADTPJXsSZf/AH/0OZZ/AH+/P3+qVVXXTpz/P3//f/8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADYxC71AAABAHRSTlP+AP6axQMBU7ICLXEBCRYCCAIEAw0EAgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAjd+WbAAAAMtJREFUeNrtlUkOgzAQBKfH+8KS9f9PzdiOkksw5hpRB5DlUgsu3YRKeKKDNe1N9SmH6NQGKUpY/sgLoifehLST9CbbgERMHZjVVGyRr1Bdtep+NlZkg7TrFltEMjnSCOxgyMDzkK1XSxgLrtEEx4OyElmNyv6IrE/5lE/5T+VSBelIyRypr5D1YPSapUXHyo6TVK509Mgvsra3TLJb0/6HsJ7tUgcI016fy6QgtGmTHXKaO1BCcdsOyrg9nPL6J165O3D5LiwMuryvX+KXBmGYS9FlAAAAAElFTkSuQmCC",
        55: "iVBORw0KGgoAAAANSUhEUgAAADcAAAA3CAMAAACfBSJ0AAADAFBMVEXrR5gAAAD7TKPpRpjsR5n/VaruSJrtSJj/AP/rR5n/AAD/f3//AH/tR5nuSJq/P3//P3//P79/AH+qVVWqVarUKn/fP4//TaL/VVX/VY3/ZpkAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACiXH/bAAABAHRSTlP+AP5IegPTLAEVAQICk6wEBAQCAwMGECEDCQUAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAf/aJCwAAAQ9JREFUeNrtltmOgzAMRX2zb1DotLP9/4eOw1ZVGkH8nvtCBDmxHB58CHseE65ibdqXtD3vhV9HfZavyPvyO2cBPzhSZyEa9b3ufHEWfiT+cBGlnEdJB2ehG6iFJIMcNu4G00Yt5BDsynG1dqyCXIi5nCKJojS+mfvAqGSgiyEQXyUJowxmmjAoKehSIPw6Esdzf1GO8c2Q7CccDXauc53rXOc69z8nHpvbPPqRc3X+WfF453k7J7LyBpf5jvx0UjCmUj3Ei8vZ1ZeMyJfG6oSrnwlURLlYxa5yIbeDavxE2T0yZWinGtCqkavybr57QzQXusvnOhNRwptfV6f02pxF+wm7Jh8cwpQufR5z2Vd/WY4I6q+RhZ4AAAAASUVORK5CYII=",
        66: "iVBORw0KGgoAAAANSUhEUgAAAEIAAABCCAMAAADUivDaAAADAFBMVEXsSJkAAADrSJj8SJL7TKP/VaruSJq/P3/sSJntR5n/f3/rR5j/AP//P7//AAD/P3/tR5naSJGqVVWqVar/AH//SLb3S6H/VVV/AH+qKn/fP5/UVX/UVar///8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADw4OoXAAABAHRSTlP+ADMJ/gOtBHHOAlgBBAEEmQgDAwIHNAMCBggGBgEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA5OJkeQAAAVxJREFUeNrt2NmSgyAUBNBuNkFxyzrr///mgEkqJpMaFeaRW5VUtPAImIfbgrOq277mclXtfn6I+8+9jt/HUf5Z43G6mX5B+HBSWmOwWM5YEZVnIgDCQCm1TMRBpgnreSQ0xwErLr8zRlDPCU3hNgATguZq4CI02F7Ksr0RewogzaguxHctHdJK8DwRmoNKE5T7PHSBOFMkCtelIHxMMgF8sUNNmQ5AhSeLljZjEsqwQt464HSHXe1yCMiwnTlbMf01ClGIQhSiEIUoRCH+n+izibc+u1HKbNegmd00HqrQuoqMOcRAAe6YsxkyNNChB2/S2/gh5AjEwJg+DenriUjeDTXFsxisUpcSltHe413Kg1Vm1H4WMi3U5jn09A9Rd2NQjTG18w+Bu+e7dWqlEsYNklX3FPt35Edj3Jr7w1hJ6t8vH7p4TsuTWKiTHONA/+r9BX3Vc1Xp1s+OfgBkvwqklCBiSgAAAABJRU5ErkJggg==",
        77: "iVBORw0KGgoAAAANSUhEUgAAAE0AAABNCAMAAADU1xmCAAADAFBMVEXsSJkAAAD8TKPsSJjsSJn/P79/AH//Var/AP/sR5j/P3/qR5f/f3/tR5nvSJv/AAD/AH/tR5m/P3/0OZOqVarcRJL4SqHMM5nUVar/AFX/AKr/RaL2TKH2SqD2S6D/f/8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABeySFjAAABAHRSTlP+AP6W2wQCAwEvBE4CdK4BAhUECwMTTAUGAwMLP5ffAgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAoO6lTQAAAZpJREFUeNrtmN1ugzAMRu0EaicEEkrb/W/v/5ZzoK2gWzVKIu0m3w0igiPbyU0O4DLeWVuvjCW++RsWb47wsRD5ezTnEAdzavXKnEwfgb/TZNnoCtT6AHTvT+j4J03WTAfywUNRUDWz8i60A+7ah1kTsOvRLmmEx24TK+KkPJrTHBrYCou85lzdSAu+T4GNOLrQ2FKXBJMYDmcaYZsKU5WniXaQoSVHtbFXoZFL7jOm90FoIUdpY3FWaBZ1jtKgemah8VBBlhg8AOdpdGoV9tioPDSNNdTph+1y5JiFlmcTJDvMSKsKrdAKrdAKrdAKrdAKrdAK7d9pee8ylO2eFWmMb5loDe4Bibs8rQ7IgK/YZGo03p29O2a57hp8GS1Bjl1V3dk5yCPD5Pqwm2iB+uReJ/UDk+EyKn0Lro4rcXTx4PqFf2sSLJfGsHSDFs3GrVCfMjN/4y1liFtUowI9XEXjzKkGxr4dlepqknyqzdzRzn2vrJJpP6q10brpcS5oly6a9+Pji3d/h900b3ffbKMnW/NarV3Xt6L8G26BEdrylkRkAAAAAElFTkSuQmCC",
        88: "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",
        110: "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",
        132: "iVBORw0KGgoAAAANSUhEUgAAAIQAAACECAMAAABmmnOVAAADAFBMVEXsSJkAAAD9TaTsSJjqR5f/P7//Var/AP/qR5ftR5n/Pn3qR5f/f3/sSJmqVaq/P3/RNpj/AAD/AH/4S6F/AH/8TKP/VVX////XSY1/f3/4S6GqVVXlP5n/VY3/Vf//f/8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABEaXtUAAABAHRSTlP+AP7aZQQDARaxBDcCUAMECQECcgJWAwERAtUDFAkDAgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAuaTO/wAAAsBJREFUeNrt3Nli6iAQBmBgwhqyGbf2LH3/tzxAPGmsibVtwngxc63xc1j0gh/Gn6DY0yNq6fwqn9I4130HoZ3Wq35drdQXEXV6Q3moLKxRRVX9Cc/zSj+OiGZzsMCEEGyNCs8B6Mv4aP0YQgdCWcBKnz+RMHswgfEIog4Ey9YWXBoC/W8u9aeIhptebEIYHBAGZXcfsXe8hO0IidGfeXsPEYjFtoTUjDb0exGhuLFs+xJQcLmECAYQLEeJ4mpEJgifzZAU9RzCy3yGqFByBvGX23yGoCj58QbhVMGyFpjxJ5qNk7IUeRHCjpPzgtD+DCxzhcmprhAqwyZ1W8brCUJ1JYIhDEg9QdR5V8b7CtmrEVHvMRqRWvFrRDQoMyJWe9IXRHcyOISwQOLfuIRoeIXUCAb8fyd2O4uFEFXcvCNCc2B4CJcQR14KNISN4xEQDm1txPL8gqjwEHDZJ3bc4iHC34q3YbPCG4z4UyoHBBCCEIQgBCEIQQhCEIIQhCAEIQhBCEIQghCEIAQhCEEIQhCCEIQgBCEIQQhCEIIQhJggUM9ZgXqic1a4iG5AdNoA4pRQz3MKkTcd3sw0uhsQNdpZSGFT9IYNoQqkI4Ci1PV4WrlDWh/A/XhkWr8YrLXRTI7RI22a5mX/jtAKY6sYMzcMK96Rloa8Dpm0Pv+AmEZfI067nLGj6aycZn48N7kN7jb9JLOuEAFGnWbCaG1GRTBoPxvLy6cIhml49SqgmEsRDc1iVLPePC2aDP15mgy8Ca063m4d1hRhXXzIzn6M7x45L143bYY13OlPMsS6jclVsWUb1AORbrVVnFqw197MhKlnw+0xkWT6GG1ftQfMVnw2Vr4Q81dxI6kOFlLI/ufFAIoYbF/I+bPF6wmGl5d9sUINX9+rr1/9oOR+xUsXlNTfvgTDSyd/XHcvn3iWmzj+AZV8HyFEOkKyAAAAAElFTkSuQmCC",
    },
    "sw_on": {
        48: "iVBORw0KGgoAAAANSUhEUgAAADAAAAAaCAMAAADG6y/lAAADAFBMVEUixV7+/v4AAAAYwlcpyGMl0mQhxl4Af38AqlUA/wAhyF4ixV4iyWC168lW0oNr2JPY9OKS4q/L8dkAmWYfw18ixVwhxF0zzGYiyGAiymBL0HxIz3mi5rsAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADda3VhAAABAHRSTlP+/wD+/f6SAgMBPMfF//7/////BTgWvAVCt////wAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAFcaebgAAAQpJREFUeNqtVO0OgjAM7LpubsD4kg8F3/857dAYkRUx8RL4sdzR62gPkOEdYmYLEEBU2AzR+cgFfk6IlysfJ8mgGWToelmIUXDCszUm/e1InqaoMsaeowL41RdGMKOpaRWjbmaWFD2TwbsxF/mNeuFG2uSj8+DQivyg3tCywqIDzI7x2RcrMrY0SP026gNBm8FDJ/arNig1dWAPF1hKWMhJqFBvBYp0Dj84WjyJgiklqP4pEC1JTYPUtHitty2/jdcq/rg52UK3MxohUYBHQx4+qjcdxOETx5v0h2J+jLe8QKwI63VYFmhnRXmjy6ckVK8V/RYCVFZVSe8hsBszFHODsYqZL0FGtAqyO3uZC/OFJOx8AAAAAElFTkSuQmCC",
        60: "iVBORw0KGgoAAAANSUhEUgAAADwAAAAgCAMAAAB5NuugAAADAFBMVEUixV7+/v4AAAAlyWEWwVUk02QAqlUixV5X04Wz68hw2ZcA/wAA/38hu1cixVwix14hxFwiymF93aCU47FEznfb9eQAvz8fxV8dxGIA/1Uktkgktm0gw10hx2MjymAiy2HL8dlN0H2j57yA3aLA7tEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAmSrkiAAABAHRSTlP+/wD8/v4D0v///wECKihjkpH/////BCgaAwcHqxemx///////AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAxJsUgAAAAUxJREFUeNq1lclyhDAMRIUlwDYZGLZZmQWS///GCENSQwXbxKn0gQNVj5aM1QJhlJ2FOBX3FDxK78VJiHM2UWCeb0LkJSCig8NZUOYG+IITcSkxBidJswBjLC+MzHAidg8nCoyi1HVdSTT4Y2doMKzHlqhX0Swl+UMxGhq4/NzZK7M4RC9S/AIxZxCy5Jp6fJs2Wkqyd3pNMjiIvY+NfkgTxHtxAHF01gyE0YrGyo/cs89YrcFPYw0395WiPlqVJmS0wABjY40FdPj7jkc1hB2kbljaYK7bM0ZI2gbXhOCDaxus/ht+t8HDCIceWDUeWOivkhR3oZekhfGS3DxNS2vVjHoGA+jDYjwORthIymkk/WEg14qewmBDDOk11sTQlgBsnot+JY/jFIDbold/422FL9G7IfQRiBpdKzXoHpahv2ndgG3d/G3Rha7YT50SEczUHcTOAAAAAElFTkSuQmCC",
        72: "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",
        84: "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",
        96: "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",
        120: "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",
        144: "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",
    },
    "sw_off": {
        48: "iVBORw0KGgoAAAANSUhEUgAAADAAAAAaCAMAAADG6y/lAAADAFBMVEVLVWP+/v4AAABCTFtRVFZKVWJLVWMAAH9KVmRGUF5bZHBLVWRweIM+SFiGjZbFyM0zM2ZNVWRmZmakqbBqcn2ytrzR09fb3eDe4ONgaXV4gIrj5Oe4vMK+wcYAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAC17OrXAAABAHRSTlP+/wD+B5LGAjv+/rn+/P//BUIF//////////////8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAK6xTHQAAAQ1JREFUeNqtVO2OgzAMc5OWtaXANsaxz/d/zaVsumODsEOapfID2aqdJgFlWCLvCqgonB9IAsjZEO3K/J85qJpyNxCzYENbB4QKMaWIijWJ22YF5GPFTHVqeyO4dAdVUVghg+xe+KE1v+igGSv2lmBJ/KAxI/SqwpEFeQn7wjfmqEb3YqlE1Zo3NFqOkuARTmaCs6bwcDMXLFzhICU6TgUmaoWSE2f4JqmvAU5zgvqLgrWWJMVlLnTQ6FLWbsr/0cvqwYcVEfzQGtfJBUFvjdx86P+ZIDdfbm+Or4+tztDQ3nmAGLeRn8hLA/QY0cDn50w0NXh5RJ9LgDmmuk6RPy6BvzXDvLBnRmtm3SK7A3oACaWA77SoAAAAAElFTkSuQmCC",
        60: "iVBORw0KGgoAAAANSUhEUgAAADwAAAAgCAMAAAB5NuugAAADAFBMVEVLVWP+/v4AAABBTFo+SFdVVVVLVWRMVWRJUV6UmqNyeYRNWGJIU2JLVWVLVmN/f3/FyM1GUF57gozl5uiyt7w/Pz9ISEhISG1cZXGlqrHU19tkbHhocXyHjpeQlp65vcO9wcbe4OMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADPTgN+AAABAHRSTlP+/wD+/gORzyn//xgoY6gC//7///8EBwf/////////////AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB28H+gAAAUdJREFUeNq1lW13gjAMhdOmiC0vKwUVBHX7/39ybTqnHqlBdnY/8Inn3DRNb0BGZVIWVZ0Do7yuCvqZBPT9kLLcwEJtSgKucCb3EUXlhTy+j+ZA7DbUiwhGD4M2PXJ8viUaiCVXc2lFlHXYMDjR4MsvAwqduJPtOfPSgyCzg68Zp1Y8yimm8kMmYSd9r3AUT3KM90buIB64fYbFiNyxgYwvM6w4Npw1FKHPYlYnxrqACkB9zsOcdQW1/4qEptfWNeTJqoU4v76uMJZKp+BOsWOuhhRs8Z/h9WX7hrkUrLmG1dD0KdhwV+WHBO082wI3JGE83aqqPUoPw64x9g8jPMn5Uxv+ScYwcO8XTWFAMaTcuyzF0DUAx+PDeY1aFIA/0YvN6Rf/0oDLovcW+s107qzttAG1OPRv68ZvG8QF++Z+3fxt0a1dsd8gHQ4XP7U+egAAAABJRU5ErkJggg==",
        72: "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",
        84: "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",
        96: "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",
        120: "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",
        144: "iVBORw0KGgoAAAANSUhEUgAAAJAAAABOCAMAAAD4rG/VAAADAFBMVEVLVWP+/v4AAABCTFpVVVU+SFdKVWNKVmNLVWM8PDxLVWJLVGJ/f3+Ei5VMVWSxtbtweIOVm6OkqbDU1to/P397g41ZYm9pcX1ja3dGUF4AAH8AAP8/X19LVWbDxsvo6euNlJzb3eAAf38/f39GUl++wcfe4OO5vMKepKsAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACuNMc1AAABAHRSTlP+/wD/A//NUI4EMnoC/7L//////wT//////wIBCBz/////AgQo/////wAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAARHHcoQAAA01JREFUeNrtmtt2qjAQhicJSUBFQE7i2ba2+/2fcKMVSFAUwqBc9L/olat8azKTzAlog6zo/Hcrp3MhAElCzKdye/6/kdX0XWjAWVO6kTYaioZlyw2la6sD0Pm3k2FoCqbJ9TNtgGZrupJzGFhzuaLrWRsgh1Ip4AUS8vKxJ0A582QOL9J8cvngIyCHOja8ULZTNxLUeL7unBa7apBz+6oRgR5dsvb7jHOA1Hdz+WkCjONTST3aQOOZ6r/lbHn4CUmp71OQAs+QiaYaEag8tm6cJFBgCh0XS+zDs1UiaOLhS480aJciI6lEUPmzysOTRpwL0pIjEzl1oEjzH3YgT7QAhutHkQ6kxReDHXmqENdIZazB9X6eKDz+nrSRi0o0ud7Zv0DWZ3Uf8pi0VIBJJD6tCki9gNrzIBNdryOoHRjrwINM9HtocDGQqPyHdNIBkUhcTAS1CNt3AyIpQ460HGhlVQbadeQhe0y/tlZnIMVAPCCd5XFcE0HuSaWBEmIgnyF60YyCVYUY90yAQoYZaBZE5aOaLYmRYjwim0ZAN/0MhGuiTe5DhUtnCTEUohfJHMjuEWLogWZT+ChijIWmQIh3kfiAbU+XRnbrLRQuxA/mQIhnJqFIPPiPORBinE2hLOSP5kAEsdyH8t3owYP45osCiKV9gFxEIMPMbLg8rQBy+wAt/oD+gHo6NWY5NLqwF33yafyMSFRPx74HUIL4dEyNSzKlzZchPq6yd8KInH4UCVofr3YxE7Qyhc2OI3ChPIWtkvyFKc8JszCryiBmnFTHuGXQBnrG2Tfig5EXilUpbfp6uLiltNJsMDMRpoHOzQalHWPmRS5yO0ZrWBkE2g65p6e39LrXQnu8O6ho6akm6n5o8QBNT7UtzN33pYplW1hrnHd7Yz2GGmKzu6OF4E08ymihNnwJ3sKjDV/08VRbP/qHOsLTx1P6eIGnLaJ/Hw85wKuNOBk8bciekmFHnDdDYP9hxzGMscfSzrMxOWNxI1LowgvG5DeDe8Z8715pdIrhNYsEt6sWjEPsaXYKvdw42OsfjasW95ZRztsnqXtY5ApcP8leu4zSsK7DGL9okH2dx+s641toGt/K1/iW4ka4Nji+xcoRrp6+czn3PxZ6Mu96oY+8AAAAAElFTkSuQmCC",
    },
}

if __name__ == "__main__":
    if "--engine" in sys.argv:
        engine_main()
        sys.exit(0)
    if "--agent" in sys.argv:
        agent_main()
        sys.exit(0)
    if not is_admin():
        relaunch_as_admin()
    enable_dpi()
    App().mainloop()
