#!/usr/bin/env python3
"""Musique TV — l'app de bureau qui ajoute des morceaux à l'app « Musique » de la TV.

Elle ouvre sa propre fenêtre (Chromium en mode application, sans barre d'adresse) sur une petite
interface servie en local sur 127.0.0.1, dessinée comme l'écran de la TV : bandeau turquoise,
panneaux blancs à liseré bleu nuit, police Outfit.

  * on glisse des fichiers (ou on clique pour parcourir) : ils sont copiés dans un dossier de
    travail local ;
  * « Envoyer sur la TV » les pousse dans /sdcard/Android/data/com.gathin.tvmusic/files/music avec
    tools/b64push.py, qui vérifie le md5 de chaque copie, puis relance l'app sur la TV ;
  * la liste des titres déjà sur la TV s'affiche, et chacun peut être retiré.

Rien à installer : Python de tools/venv et le Chromium déjà présent sur le PC. Aucune connexion
internet n'est utilisée (le serveur n'écoute que sur 127.0.0.1).
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import threading
import time
import unicodedata
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlparse

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
WEB = HERE / "web"
PYTHON = ROOT / "tools" / "venv" / "Scripts" / "python.exe"
TV = ROOT / "tools" / "tv.py"
PUSH = ROOT / "tools" / "b64push.py"
REMOTE = "/sdcard/Android/data/com.gathin.tvmusic/files/music"
APP_ID = "com.gathin.tvmusic"
DEFAULT_HOST = "192.168.1.21"
AUDIO = {".mp3", ".m4a", ".aac", ".ogg", ".opus", ".flac", ".wav", ".mp4"}
NAME_OK = re.compile(r"[A-Za-z0-9._-]{1,120}")

INBOX = Path(os.environ.get("TEMP", str(HERE))) / "musique-tv" / "a-envoyer"
SETTINGS = Path(os.environ.get("APPDATA", str(HERE))) / "MusiqueTV" / "config.json"


# --- files and names ------------------------------------------------------------------------

def safe_name(name: str) -> str:
    """The name used on the TV: the transfer only accepts letters, digits, '.', '_' and '-'."""
    stem, dot, ext = name.rpartition(".")
    if not dot:
        stem, ext = name, ""
    stem = "".join(char for char in unicodedata.normalize("NFKD", stem)
                   if not unicodedata.combining(char))
    base = "".join(char if (char.isascii() and (char.isalnum() or char in "._-")) else "_"
                   for char in stem).strip("_.") or "titre"
    clean_ext = "".join(char for char in ext if char.isalnum()).lower()
    return f"{base}.{clean_ext}" if clean_ext else base


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


SETTINGS_LOCK = threading.Lock()


def host() -> str:
    with SETTINGS_LOCK:
        return load_settings().get("host") or DEFAULT_HOST


def set_host(value: str) -> str:
    value = value.strip() or DEFAULT_HOST
    with SETTINGS_LOCK:
        data = load_settings()
        data["host"] = value
        save_settings(data)
    return value


# --- talking to the TV ------------------------------------------------------------------------

def tv_shell(command: str, timeout: float = 90.0) -> subprocess.CompletedProcess:
    env = os.environ.copy()
    env["TV_HOST"] = host()
    env["MSYS_NO_PATHCONV"] = "1"
    return subprocess.run([str(PYTHON), str(TV), command], cwd=str(ROOT), env=env,
                          capture_output=True, text=True, timeout=timeout)


def probe() -> dict:
    """The TV's model and the titles it already holds; ok=false with a reason when it is away."""
    try:
        answer = tv_shell("getprop ro.product.model", timeout=25)
    except (OSError, subprocess.TimeoutExpired) as error:
        return {"ok": False, "error": f"TV injoignable ({error.__class__.__name__})"}
    if answer.returncode != 0:
        lines = [line.strip() for line in (answer.stderr or answer.stdout).splitlines() if line.strip()]
        return {"ok": False, "error": lines[-1] if lines else "pas de réponse de la TV"}
    model = answer.stdout.strip().splitlines()
    # "./$f" and not "$f": a name that starts with a dash would otherwise be read as an option.
    listing = tv_shell(
        f'cd {REMOTE} 2>/dev/null && for f in *; do [ -f "$f" ] && echo "$(stat -c %s "./$f")|$f"; done',
        timeout=40)
    tracks = []
    for line in (listing.stdout or "").splitlines():
        size, _, name = line.partition("|")
        if not name or not size.strip().isdigit():
            continue
        # Only what the app can play: an interrupted transfer leaves a .b64.part behind, and
        # listing that as a title would invite the user to delete the wrong thing.
        if Path(name).suffix.lower() not in AUDIO:
            continue
        tracks.append({"name": name, "size": int(size)})
    tracks.sort(key=lambda track: track["name"].lower())
    return {"ok": True, "model": model[-1] if model else "TV", "tracks": tracks}


def restart_app() -> None:
    try:
        tv_shell(f"am force-stop {APP_ID}; am start -n {APP_ID}/.MusicActivity", timeout=40)
    except Exception:
        pass


# --- the sending job ---------------------------------------------------------------------------

class Job:
    """One run at a time: the files being pushed, and what happened to each."""

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.running = False
        self.total = 0
        self.done = 0
        self.current = ""
        self.log: list[dict] = []
        self.finished = False
        self.sent = 0

    def snapshot(self) -> dict:
        with self.lock:
            return {"running": self.running, "finished": self.finished, "total": self.total,
                    "done": self.done, "current": self.current, "sent": self.sent,
                    "log": list(self.log[-80:])}

    def write(self, kind: str, text: str) -> None:
        with self.lock:
            self.log.append({"kind": kind, "text": text})

    def start(self, names: list[str]) -> None:
        with self.lock:
            self.running = True
            self.finished = False
            self.total = len(names)
            self.done = 0
            self.current = ""
            self.log = []
            self.sent = 0
        threading.Thread(target=self._run, args=(names,), daemon=True).start()

    def _run(self, names: list[str]) -> None:
        errors = 0
        for index, name in enumerate(names, start=1):
            local = INBOX / name
            with self.lock:
                self.current = name
            self.write("info", f"[{index}/{len(names)}] {name}")
            if not local.is_file():
                self.write("error", "    fichier introuvable dans le dossier de travail")
                errors += 1
                continue
            try:
                result = subprocess.run([str(PYTHON), str(PUSH), str(local), f"{REMOTE}/{name}"],
                                        cwd=str(ROOT),
                                        env={**os.environ, "TV_HOST": host(), "MSYS_NO_PATHCONV": "1"},
                                        capture_output=True, text=True, timeout=900)
            except Exception as error:                      # noqa: BLE001 - reported in the window
                self.write("error", f"    échec : {error}")
                errors += 1
                continue
            output = (result.stdout or "") + (result.stderr or "")
            if result.returncode == 0 and "MATCH" in output:
                with self.lock:
                    self.sent += 1
                self.write("ok", "    copié, md5 vérifié")
            else:
                errors += 1
                self.write("error", "    échec : " + last_reason(output))
            with self.lock:
                self.done = index
        self.write("info", "— relance de l'app sur la TV —")
        restart_app()
        self.write("done", f"— {self.sent}/{len(names)} titre(s) envoyé(s)"
                           + (f", {errors} en échec —" if errors else " —"))
        with self.lock:
            self.running = False
            self.finished = True
            self.current = ""


def last_reason(output: str) -> str:
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    for line in reversed(lines):
        if not line.startswith(("md5 ", "chunk ")):
            return line
    return lines[-1] if lines else "raison inconnue"


JOB = Job()


# --- the local window handler ------------------------------------------------------------------

CONTENT_TYPES = {".html": "text/html; charset=utf-8", ".css": "text/css; charset=utf-8",
                 ".js": "application/javascript; charset=utf-8", ".png": "image/png",
                 ".jpg": "image/jpeg", ".ttf": "font/ttf", ".svg": "image/svg+xml"}
WALLPAPER = ROOT / "work" / "sekai" / "wallpaper.jpg"
FONT = ROOT / "work" / "music" / "app" / "src" / "main" / "res" / "font" / "outfit.ttf"


class LocalServer(ThreadingHTTPServer):
    """Loopback only, and one instance per port: on Windows the default SO_REUSEADDR lets a second
    instance bind a port that is already served, so two windows would answer each other's requests
    (an old one could serve the new window). Turning it off makes the next port be chosen instead."""
    allow_reuse_address = False
    daemon_threads = True


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_args) -> None:
        pass                                        # a local window does not need a request log

    # --- helpers ---------------------------------------------------------------------------

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

    def body_bytes(self) -> bytes:
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length) if length else b""

    # --- routes ----------------------------------------------------------------------------

    def do_GET(self) -> None:                       # noqa: N802 - BaseHTTPRequestHandler name
        path = urlparse(self.path).path
        if path == "/":
            return self.serve_file(WEB / "index.html")
        if path == "/app.css":
            return self.serve_file(WEB / "app.css")
        if path == "/app.js":
            return self.serve_file(WEB / "app.js")
        if path == "/font/outfit.ttf":
            return self.serve_file(FONT)
        if path == "/wallpaper.jpg":
            return self.serve_file(WALLPAPER)
        if path == "/api/state":
            return self.send_json({"host": host(), "tv": probe()})
        if path == "/api/job":
            return self.send_json(JOB.snapshot())
        return self.send_bytes(b"not found", "text/plain; charset=utf-8", 404)

    def do_POST(self) -> None:                      # noqa: N802 - BaseHTTPRequestHandler name
        parsed = urlparse(self.path)
        path = parsed.path
        if path == "/api/upload":
            name = safe_name(unquote(parse_qs(parsed.query).get("name", ["titre.mp3"])[0]))
            if Path(name).suffix.lower() not in AUDIO:
                return self.send_json({"ok": False, "error": "format non pris en charge"}, 400)
            data = self.body_bytes()
            if not data:
                return self.send_json({"ok": False, "error": "fichier vide"}, 400)
            if len(data) > 400 * 1024 * 1024:
                return self.send_json({"ok": False, "error": "fichier trop gros (400 Mo)"}, 400)
            INBOX.mkdir(parents=True, exist_ok=True)
            (INBOX / name).write_bytes(data)
            return self.send_json({"ok": True, "file": {"name": name, "size": len(data)}})
        if path == "/api/send":
            names = [name for name in self.body_json().get("names", [])
                     if NAME_OK.fullmatch(name or "")]
            if not names:
                return self.send_json({"ok": False, "error": "rien à envoyer"}, 400)
            if JOB.snapshot()["running"]:
                return self.send_json({"ok": False, "error": "un envoi est déjà en cours"}, 409)
            JOB.start(names)
            return self.send_json({"ok": True})
        if path == "/api/delete":
            name = self.body_json().get("name", "")
            if not NAME_OK.fullmatch(name or ""):
                return self.send_json({"ok": False, "error": "nom refusé"}, 400)
            answer = tv_shell(f'rm -f "{REMOTE}/{name}" && echo removed', timeout=40)
            ok = "removed" in (answer.stdout or "")
            restart_app()
            return self.send_json({"ok": ok, "error": None if ok else "la TV a refusé"}
                                  | {"tv": probe()})
        if path == "/api/host":
            return self.send_json({"ok": True, "host": set_host(self.body_json().get("host", ""))})
        if path == "/api/test":
            return self.send_json({"ok": True, "tv": probe()})
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


# --- window ----------------------------------------------------------------------------------

def find_chromium() -> Path | None:
    roots = [os.environ.get("PROGRAMFILES", ""), os.environ.get("PROGRAMFILES(X86)", ""),
             os.environ.get("LOCALAPPDATA", "")]
    names = ["Google/Chrome/Application/chrome.exe", "Chromium/Application/chrome.exe",
             "Microsoft/Edge/Application/msedge.exe", "BraveSoftware/Brave-Browser/Application/brave.exe"]
    for root in roots:
        for name in names:
            candidate = Path(root) / name
            if candidate.is_file():
                return candidate
    return None


def open_window(url: str) -> None:
    browser = find_chromium()
    profile = Path(os.environ.get("LOCALAPPDATA", str(HERE))) / "MusiqueTV" / "window"
    profile.mkdir(parents=True, exist_ok=True)
    if browser is None:
        os.startfile(url)                                   # type: ignore[attr-defined]
        return
    subprocess.Popen([str(browser), f"--app={url}", "--window-size=1180,780",
                      f"--user-data-dir={profile}", "--no-first-run", "--no-default-browser-check",
                      "--disable-features=Translate"], close_fds=True)


def main() -> int:
    if not PYTHON.exists():
        print(f"Python de tools/venv introuvable : {PYTHON}", file=sys.stderr)
        return 1
    INBOX.mkdir(parents=True, exist_ok=True)
    server = None
    for port in range(8765, 8776):
        try:
            server = LocalServer(("127.0.0.1", port), Handler)
            break
        except OSError:
            continue
    if server is None:
        print("aucun port libre entre 8765 et 8775", file=sys.stderr)
        return 1
    url = f"http://127.0.0.1:{server.server_address[1]}/"
    threading.Thread(target=server.serve_forever, daemon=True).start()
    time.sleep(0.2)
    # MUSIQUE_TV_NO_WINDOW=1 serves the window without opening it, for a check in a browser.
    if os.environ.get("MUSIQUE_TV_NO_WINDOW") != "1":
        open_window(url)
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
