#!/usr/bin/env bash
# Put the MiTV-AESP0 back the way it was before this cleanup:
#   1. re-enable every package the four batch lists disabled (tools/restore.sh replays the state
#      each package had before it was touched, so apps that were already off stay off),
#   2. hand Home back to the stock Google TV launcher,
#   3. remove the Sekai theme overlay.
#
# Usage:  TV_HOST=192.168.1.21 bash work/restore-everything.sh
# Needs USB/Network debugging back on (Settings > Device Preferences > About > Build x7 >
# Developer options > USB debugging), and the adb client in tools/venv.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${TV_HOST:?set TV_HOST to the TV address, e.g. TV_HOST=192.168.1.21 bash work/restore-everything.sh}"
export TV_HOST
export MSYS_NO_PATHCONV=1
cd "$ROOT"

PY="$ROOT/tools/venv/Scripts/python.exe"
[ -x "$PY" ] || PY="$ROOT/tools/venv/bin/python"
[ -x "$PY" ] || { echo "no adb client: run python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }

# Reverse order of application.
for list in work/batch4-voice-cast-xiaomi.txt work/batch3-plumbing.txt \
            work/batch2-preloaded-apps.txt work/batch1-telemetry-patchwall.txt; do
  echo "== restoring $(basename "$list")"
  bash tools/restore.sh "$list" com.google.android.tvlauncher
done

# Belt and braces: the stock launcher enabled, and Home pointed at it again.
"$PY" -I tools/tv.py 'pm enable --user 0 com.google.android.tvlauncher'
"$PY" -I tools/tv.py 'cmd package set-home-activity --user 0 com.google.android.tvlauncher/com.google.android.tvlauncher.MainActivity'

# Take the Sekai overlay off (the launcher app itself stays installed; see work/README.md).
bash tools/theme.sh --reset

echo
echo "Restored. Reboot the TV (Settings > About > Restart) so the stock Home comes back cleanly."
echo "The new launcher is still installed: press Home and, if Android asks which home to use,"
echo "pick the stock 'Google TV' one, or remove it with:"
echo "  TV_HOST=$TV_HOST $PY -I tools/tv.py 'pm uninstall com.gathin.tvhome'"
