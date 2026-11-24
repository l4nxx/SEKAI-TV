#!/usr/bin/env python3
"""SEKAI TV — finds the Android TVs on this network and lets you pick one.

A small window of its own (Chromium in app mode, no address bar) drawn like the TV theme: teal band,
white panels with a navy edge, Outfit type. The page is served on 127.0.0.1 only.

The scan runs by itself at start and looks for two things, in parallel:

  * the ADB port (5555) on every address of this computer's /24, which is what an Android TV with
    network debugging on answers; the TV is then identified over ADB (model, manufacturer, Android
    version, leanback feature) with the same key the tools already use;
  * DIAL/SSDP (the protocol behind Chromecast and most smart TVs), which also finds TVs whose ADB is
    off: their UPnP description gives the name and the model.

Only TVs are listed: an open ADB port on a phone or a PC stays out unless the TV itself announced it.
The chosen TV is remembered in %APPDATA%\\SekaiTV\\config.json.
"""
from __future__ import annotations

import json
import os
import re
import socket
import shutil
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor, as_completed
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse
from urllib.request import urlopen

HERE = Path(__file__).resolve().parent
FROZEN = getattr(sys, "frozen", False)
RES = Path(getattr(sys, "_MEIPASS", HERE))       # web/ and theme/ bundled inside the exe
APP_DIR = Path(sys.executable).resolve().parent if FROZEN else HERE
WEB = RES / "web"


def config_dir() -> Path:
    """Where the chosen TV and the window profile live, per platform."""
    if os.name == "nt":
        base = Path(os.environ.get("APPDATA", str(HERE)))
    elif sys.platform == "darwin":
        base = Path.home() / "Library" / "Application Support"
    else:
        base = Path(os.environ.get("XDG_CONFIG_HOME", str(Path.home() / ".config")))
    return base / "SekaiTV"


SETTINGS = config_dir() / "config.json"
ANDROID_KEYS = Path.home() / ".android"
ADB_PORT = 5555
IP_OK = re.compile(r"^\d{1,3}(\.\d{1,3}){3}$")


# --- settings ---------------------------------------------------------------------------------

SETTINGS_LOCK = threading.Lock()


def load_settings() -> dict:
    try:
        return json.loads(SETTINGS.read_text(encoding="utf-8"))
    except Exception:
        return {}


def save_settings(data: dict) -> None:
    try:
        SETTINGS.parent.mkdir(parents=True, exist_ok=True)
        SETTINGS.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    except OSError:
        pass


def selected() -> dict | None:
    with SETTINGS_LOCK:
        data = load_settings()
    ip = data.get("ip")
    if not ip:
        return None
    return {"ip": ip, "name": data.get("name") or ip}


def select(ip: str, name: str) -> dict:
    with SETTINGS_LOCK:
        data = load_settings()
        data["ip"] = ip
        data["name"] = name
        save_settings(data)
    return {"ip": ip, "name": name}


# --- the network ------------------------------------------------------------------------------

def local_ip() -> str:
    """This computer's address on the network the TV is on (no packet is sent)."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("8.8.8.8", 80))
        return sock.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        sock.close()


def port_open(ip: str, port: int = ADB_PORT, timeout: float = 0.3) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(timeout)
        return sock.connect_ex((ip, port)) == 0


class AdbError(RuntimeError):
    """A connection or a command whose reason the window can show as is."""


def adb_signer():
    """The key the TV already trusts: ~/.android/adbkey, the one the tools use."""
    try:
        from adb_shell.auth.sign_pythonrsa import PythonRSASigner
    except ImportError as error:
        raise AdbError("adb_shell absent de ce Python") from error
    key = ANDROID_KEYS / "adbkey"
    pub = ANDROID_KEYS / "adbkey.pub"
    if not (key.is_file() and pub.is_file()):
        raise AdbError("clé ADB absente (~/.android/adbkey)")
    try:
        return PythonRSASigner(pub.read_text(encoding="utf-8"), key.read_text(encoding="utf-8"))
    except Exception as error:                       # noqa: BLE001 - shown in the window
        raise AdbError(f"clé ADB illisible ({error.__class__.__name__})") from error


def adb_connect(ip: str, timeout: float = 8):
    try:
        from adb_shell.adb_device import AdbDeviceTcp
    except ImportError as error:
        raise AdbError("adb_shell absent de ce Python") from error
    device = AdbDeviceTcp(ip, ADB_PORT, default_transport_timeout_s=timeout)
    try:
        device.connect(rsa_keys=[adb_signer()], transport_timeout_s=timeout, auth_timeout_s=timeout)
    except Exception as error:                       # noqa: BLE001 - the TV may refuse the key
        raise AdbError(str(error)[:160] or error.__class__.__name__) from error
    return device


def adb_info(ip: str) -> dict:
    """The TV's identity over ADB, or the reason it could not be read."""
    try:
        device = adb_connect(ip, timeout=5)
    except AdbError as error:
        return {"error": str(error)}
    try:
        answer = device.shell(
            "getprop ro.product.model; getprop ro.product.manufacturer; "
            "getprop ro.build.version.release; pm list features | grep -c leanback")
        device.close()
    except Exception as error:                       # noqa: BLE001 - the TV may refuse the command
        return {"error": str(error)[:160] or error.__class__.__name__}
    lines = [line.strip() for line in (answer or "").splitlines()] + ["", "", "", ""]
    return {
        "model": lines[0] or "",
        "manufacturer": lines[1] or "",
        "android": lines[2] or "",
        "leanback": lines[3] not in ("", "0"),
    }


SSDP_ADDRESS = ("239.255.255.250", 1900)
SSDP_SEARCH = ("M-SEARCH * HTTP/1.1\r\n"
               "HOST: 239.255.255.250:1900\r\n"
               "MAN: \"ssdp:discover\"\r\n"
               "MX: 1\r\n"
               "ST: urn:dial-multiscreen-org:service:dial:1\r\n\r\n")


def ssdp_locations(seconds: float = 2.4) -> dict[str, str]:
    """ip -> description URL, for the devices answering the DIAL search."""
    found: dict[str, str] = {}
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
        sock.settimeout(0.3)
        sock.sendto(SSDP_SEARCH.encode("ascii"), SSDP_ADDRESS)
        end = time.time() + seconds
        while time.time() < end:
            try:
                data, address = sock.recvfrom(4096)
            except socket.timeout:
                continue
            except OSError:
                break
            match = re.search(r"(?im)^LOCATION:\s*(\S+)", data.decode("utf-8", "replace"))
            if match:
                found[address[0]] = match.group(1).strip()
    finally:
        sock.close()
    return found


def ssdp_details(location: str) -> dict:
    """friendlyName, modelName, manufacturer and deviceType of a UPnP description."""
    try:
        with urlopen(location, timeout=1.2) as answer:
            body = answer.read(80000)
        root = ET.fromstring(body)
    except Exception:                                # noqa: BLE001 - a silent device is not an error
        return {}
    details: dict[str, str] = {}
    for element in root.iter():
        tag = element.tag.split("}")[-1]
        if tag in ("friendlyName", "modelName", "manufacturer", "deviceType") and element.text:
            details.setdefault(tag, element.text.strip())
    return details


def looks_like_tv(*parts: str) -> bool:
    text = " ".join(part or "" for part in parts).lower()
    return any(word in text for word in ("tv", "dia", "screen", "renderer", "xiaomi", "sony", "philips",
                                         "hisense", "tcl", "samsung", "lg electronics"))


# --- the scan -----------------------------------------------------------------------------------

class Scanner:
    """One scan at a time; the page polls this while it runs."""

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.scanning = False
        self.done = 0
        self.total = 0
        self.devices: list[dict] = []
        self.finished = 0.0
        self.error = ""
        self.network = local_ip()

    def snapshot(self) -> dict:
        with self.lock:
            return {
                "scanning": self.scanning, "done": self.done, "total": self.total,
                "devices": list(self.devices), "finished": self.finished,
                "error": self.error, "network": self.network,
            }

    def start(self) -> bool:
        with self.lock:
            if self.scanning:
                return False
            self.scanning = True
            self.error = ""
            self.done = 0
            self.total = 0
            self.devices = []
        threading.Thread(target=self._run, daemon=True).start()
        return True

    def bump(self, count: int = 1) -> None:
        with self.lock:
            self.done += count

    def _run(self) -> None:
        try:
            devices = self._scan()
            with self.lock:
                self.devices = devices
        except Exception as error:                   # noqa: BLE001 - reported in the window
            with self.lock:
                self.error = f"{error.__class__.__name__}: {error}"
        finally:
            with self.lock:
                self.scanning = False
                self.finished = time.time()

    def _scan(self) -> list[dict]:
        network = local_ip()
        with self.lock:
            self.network = network
        prefix = network.rsplit(".", 1)[0]
        hosts = [f"{prefix}.{index}" for index in range(1, 255) if f"{prefix}.{index}" != network]
        with self.lock:
            self.total = len(hosts)

        ssdp: dict[str, str] = {}
        threading.Thread(target=lambda: ssdp.update(ssdp_locations()), daemon=True).start()

        open_hosts: list[str] = []
        with ThreadPoolExecutor(max_workers=64) as pool:
            futures = {pool.submit(port_open, host): host for host in hosts}
            for future in as_completed(futures):
                self.bump()
                try:
                    if future.result():
                        open_hosts.append(futures[future])
                except OSError:
                    pass

        devices: list[dict] = []
        seen: set[str] = set()
        for host in sorted(open_hosts, key=lambda value: tuple(int(p) for p in value.split("."))):
            info = adb_info(host)
            if "error" in info:
                # The port answers but the TV refused the key: say so, it is still a lead.
                devices.append({
                    "ip": host, "name": host, "model": "", "manufacturer": "", "android": "",
                    "adb": False, "note": info["error"], "source": "adb",
                })
                seen.add(host)
                continue
            identity = " ".join((info.get("model"), info.get("manufacturer")))
            if info.get("leanback") or looks_like_tv(identity):
                devices.append({
                    "ip": host, "name": info.get("model") or host, "model": info.get("model", ""),
                    "manufacturer": info.get("manufacturer", ""), "android": info.get("android", ""),
                    "adb": True, "note": "", "source": "adb",
                })
                seen.add(host)

        for host, location in ssdp.items():
            if host in seen:
                continue
            details = ssdp_details(location)
            name = details.get("friendlyName") or details.get("modelName") or host
            model = details.get("modelName", "")
            manufacturer = details.get("manufacturer", "")
            if not looks_like_tv(name, model, manufacturer, details.get("deviceType", "")):
                continue
            devices.append({
                "ip": host, "name": name, "model": model, "manufacturer": manufacturer, "android": "",
                "adb": port_open(host), "note": "", "source": "ssdp",
            })

        devices.sort(key=lambda device: (not device["adb"], (device["name"] or device["ip"]).lower()))
        return devices


SCANNER = Scanner()


# --- installing the theme ----------------------------------------------------------------------

PACKAGE = "com.gathin.tvhome"
REMOTE_FILES = f"/sdcard/Android/data/{PACKAGE}/files"
REMOTE_APK = "/data/local/tmp/sekai-tv-home.apk"


def theme_file(name: str) -> Path | None:
    """A theme file: shipped inside the exe, dropped next to it, or left in this project."""
    for folder in (RES / "theme", APP_DIR / "theme", HERE / "theme"):
        candidate = folder / name
        if candidate.is_file():
            return candidate
    return None


APK_URL = ("https://github.com/lucasperrin3010-lang/SEKAI-TV/releases/latest/download/"
           "sekai-home.apk")


def download_apk() -> Path | None:
    """Fetch the launcher APK from the project's latest release, once, and keep it.

    The macOS and Linux builds ship without an APK; the Windows one has it inside. Downloading it
    on the first install keeps every build able to install the theme, and a failed download only
    means the install says the APK is missing.
    """
    target = config_dir() / "sekai-home.apk"
    if target.is_file() and target.stat().st_size > 100_000:
        return target
    try:
        with urlopen(APK_URL, timeout=30) as answer:
            body = answer.read()
    except Exception:                                # noqa: BLE001 - the install reports the gap
        return None
    if len(body) < 100_000:
        return None
    try:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(body)
    except OSError:
        return None
    return target


def theme_apk() -> Path | None:
    """The launcher APK: bundled, dropped next to the app, left in this project, or downloaded."""
    bundled = theme_file("sekai-home.apk") or theme_file("app-release.apk")
    if bundled is not None:
        return bundled
    for path in (APP_DIR.parent / "cleanyourtv/launcher/app/build/outputs/apk/release/app-release.apk",
                 APP_DIR.parent / "cleanyourtv/site/downloads/sekai-home.apk",
                 APP_DIR.parent / "mon-tv/launcher/app/build/outputs/apk/release/app-release.apk"):
        if path.is_file():
            return path
    return download_apk()


class Installer:
    """Installs or updates the launcher and pushes the theme, one step at a time.

    Nothing is uninstalled and the stock launcher is left alone: the new Home is chosen with
    set-home-activity, which the TV can undo by picking its old launcher again.
    """

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.running = False
        self.lines: list[dict] = []
        self.ok: bool | None = None
        self.error = ""
        self.ip = ""

    def snapshot(self) -> dict:
        with self.lock:
            return {"running": self.running, "lines": list(self.lines), "ok": self.ok,
                    "error": self.error, "ip": self.ip}

    def add(self, text: str, state: str = "ok") -> None:
        with self.lock:
            self.lines.append({"text": text, "state": state})

    def start(self, ip: str) -> bool:
        with self.lock:
            if self.running:
                return False
            self.running = True
            self.lines = []
            self.ok = None
            self.error = ""
            self.ip = ip
        threading.Thread(target=self._run, args=(ip,), daemon=True).start()
        return True

    def _run(self, ip: str) -> None:
        try:
            self.add(f"Connexion à {ip}…", "run")
            device = adb_connect(ip, timeout=10)
            self.add(f"Connecté à {ip}", "ok")

            apk = theme_apk()
            if apk is None:
                raise AdbError("APK du lanceur introuvable à côté de l'application")
            self.add(f"Copie du lanceur ({max(1, apk.stat().st_size // 1024)} Ko)…", "run")
            device.push(str(apk), REMOTE_APK)
            self.add("Lanceur copié sur la télé", "ok")

            self.add("Installation du lanceur…", "run")
            answer = device.shell(f"pm install -r {REMOTE_APK}; rm -f {REMOTE_APK}", read_timeout_s=240)
            if "Success" not in (answer or ""):
                detail = " ".join((answer or "").split())[:180]
                if "INSTALL_FAILED_OLDER_SDK" in (answer or ""):
                    detail = "APK trop récent pour cette télé (INSTALL_FAILED_OLDER_SDK)"
                raise AdbError(detail or "installation sans réponse")
            self.add("Lanceur installé ou mis à jour", "ok")

            config = theme_file("config.json")
            if config is None:
                raise AdbError("config.json du thème introuvable")
            device.shell(f"mkdir -p {REMOTE_FILES}")
            device.push(str(config), f"{REMOTE_FILES}/config.json")
            self.add("Thème envoyé (config.json)", "ok")

            wallpaper = theme_file("wallpaper.jpg")
            if wallpaper is not None:
                device.push(str(wallpaper), f"{REMOTE_FILES}/wallpaper.jpg")
                self.add("Fond d'écran envoyé", "ok")
            else:
                self.add("Fond d'écran laissé tel quel", "skip")

            device.shell(f"cmd package set-home-activity --user 0 {PACKAGE}/.MainActivity")
            self.add("Écran d'accueil réglé", "ok")

            # A force stop also takes the launcher's Home-key helper off the enabled accessibility
            # services; Android binds it again only when the list changes, so it is cleared first.
            device.shell(
                's=$(settings get secure enabled_accessibility_services); '
                'a=$(settings get secure accessibility_enabled); '
                f'am force-stop {PACKAGE}; '
                f'case "$s" in *{PACKAGE}*) settings put secure enabled_accessibility_services ""; '
                'settings put secure enabled_accessibility_services "$s"; '
                'settings put secure accessibility_enabled "$a" ;; esac')
            start = device.shell(
                f"am start -W -a android.intent.action.MAIN -c android.intent.category.HOME "
                f"-n {PACKAGE}/.MainActivity", read_timeout_s=60)
            device.close()
            if "Status: ok" not in (start or ""):
                detail = " ".join((start or "").split())[:160]
                raise AdbError(f"le lanceur n'a pas démarré ({detail or 'aucune réponse'})")
            self.add("Lanceur relancé sur la télé", "ok")
            self.add("Thème installé — appuie sur HOME sur la télé", "ok")
            with self.lock:
                self.ok = True
        except AdbError as error:
            self._fail(str(error))
        except Exception as error:                   # noqa: BLE001 - shown in the window
            self._fail(f"{error.__class__.__name__}: {error}")
        finally:
            with self.lock:
                self.running = False

    def _fail(self, message: str) -> None:
        with self.lock:
            self.error = message
            self.ok = False
            self.lines.append({"text": message, "state": "fail"})


INSTALLER = Installer()


# --- the local window ---------------------------------------------------------------------------

CONTENT_TYPES = {".html": "text/html; charset=utf-8", ".css": "text/css; charset=utf-8",
                 ".js": "application/javascript; charset=utf-8", ".ttf": "font/ttf",
                 ".png": "image/png", ".svg": "image/svg+xml"}


class LocalServer(ThreadingHTTPServer):
    """Loopback only. allow_reuse_address off: on Windows it would let a second window bind a port
    that is already served, and the two windows would answer each other's requests."""
    allow_reuse_address = False
    daemon_threads = True


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_args) -> None:
        pass

    def send_bytes(self, body: bytes, content_type: str, status: int = 200) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def send_json(self, data: dict, status: int = 200) -> None:
        self.send_bytes(json.dumps(data, ensure_ascii=False).encode("utf-8"),
                        "application/json; charset=utf-8", status)

    def body_json(self) -> dict:
        length = int(self.headers.get("Content-Length") or 0)
        if not length:
            return {}
        try:
            return json.loads(self.rfile.read(length).decode("utf-8"))
        except Exception:
            return {}

    def do_GET(self) -> None:                        # noqa: N802 - BaseHTTPRequestHandler name
        path = urlparse(self.path).path
        if path == "/":
            return self.serve_file(WEB / "index.html")
        if path == "/app.css":
            return self.serve_file(WEB / "app.css")
        if path == "/app.js":
            return self.serve_file(WEB / "app.js")
        if path == "/font/outfit.ttf":
            return self.serve_file(WEB / "fonts" / "outfit.ttf")
        if path == "/api/state":
            return self.send_json({"scan": SCANNER.snapshot(), "selected": selected(),
                                   "install": INSTALLER.snapshot()})
        return self.send_bytes(b"not found", "text/plain; charset=utf-8", 404)

    def do_POST(self) -> None:                       # noqa: N802 - BaseHTTPRequestHandler name
        path = urlparse(self.path).path
        if path == "/api/scan":
            SCANNER.start()
            return self.send_json({"ok": True})
        if path == "/api/install":
            data = self.body_json()
            ip = str(data.get("ip", "")).strip()
            if not IP_OK.match(ip):
                return self.send_json({"ok": False, "error": "adresse refusée"}, 400)
            started = INSTALLER.start(ip)
            return self.send_json({"ok": started, "error": "" if started else "installation déjà en cours"})
        if path == "/api/select":
            data = self.body_json()
            ip = str(data.get("ip", "")).strip()
            if not IP_OK.match(ip):
                return self.send_json({"ok": False, "error": "adresse refusée"}, 400)
            name = str(data.get("name", "")).strip() or ip
            return self.send_json({"ok": True, "selected": select(ip, name)})
        if path == "/api/quit":
            self.send_json({"ok": True})
            threading.Timer(0.4, lambda: os._exit(0)).start()
            return None
        return self.send_json({"ok": False, "error": "inconnu"}, 404)

    def serve_file(self, path: Path) -> None:
        try:
            body = path.read_bytes()
        except OSError:
            return self.send_bytes(b"not found", "text/plain; charset=utf-8", 404)
        self.send_bytes(body, CONTENT_TYPES.get(path.suffix.lower(), "application/octet-stream"))


# --- window -------------------------------------------------------------------------------------

def find_chromium() -> Path | None:
    """Chrome, Chromium, Edge or Brave, wherever this platform keeps it."""
    if sys.platform == "darwin":
        names = ["Google Chrome.app/Contents/MacOS/Google Chrome",
                 "Chromium.app/Contents/MacOS/Chromium",
                 "Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
                 "Brave Browser.app/Contents/MacOS/Brave Browser"]
        for name in names:
            candidate = Path("/Applications") / name
            if candidate.is_file():
                return candidate
        return None
    if os.name != "nt":
        for command in ("google-chrome", "google-chrome-stable", "chromium", "chromium-browser",
                        "microsoft-edge", "brave-browser"):
            found = shutil.which(command)
            if found:
                return Path(found)
        return None
    roots = [os.environ.get("PROGRAMFILES", ""), os.environ.get("PROGRAMFILES(X86)", ""),
             os.environ.get("LOCALAPPDATA", "")]
    names = ["Google/Chrome/Application/chrome.exe", "Chromium/Application/chrome.exe",
             "Microsoft/Edge/Application/msedge.exe",
             "BraveSoftware/Brave-Browser/Application/brave.exe"]
    for root in roots:
        for name in names:
            candidate = Path(root) / name
            if candidate.is_file():
                return candidate
    return None


def open_window(url: str) -> None:
    browser = find_chromium()
    profile = config_dir() / "window"
    profile.mkdir(parents=True, exist_ok=True)
    if browser is None:
        if os.name == "nt":
            os.startfile(url)                        # type: ignore[attr-defined]
        else:
            import webbrowser
            webbrowser.open(url)
        return
    subprocess.Popen([str(browser), f"--app={url}", "--window-size=1180,760",
                      f"--user-data-dir={profile}", "--no-first-run", "--no-default-browser-check",
                      "--disable-features=Translate"], close_fds=True)


def main() -> int:
    for port in range(8785, 8796):
        try:
            server = LocalServer(("127.0.0.1", port), Handler)
            break
        except OSError:
            continue
    else:
        print("aucun port libre entre 8785 et 8795", file=sys.stderr)
        return 1
    SCANNER.start()                                  # the window opens on a scan already running
    url = f"http://127.0.0.1:{server.server_address[1]}/"
    print(url, flush=True)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    time.sleep(0.2)
    if os.environ.get("SEKAI_TV_NO_WINDOW") != "1":
        open_window(url)
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
