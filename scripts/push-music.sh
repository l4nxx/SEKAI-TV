#!/usr/bin/env bash
# Copy one or more audio files into the music app's library on the TV, then restart the app so the
# library is read again.
#
# Usage:  TV_HOST=192.168.1.21 bash work/push-music.sh ~/Downloads/titre.mp3 [autre.flac …]
#
# The TV side of the transfer only accepts plain names (letters, digits, '.', '_', '-'), so spaces,
# accents and parentheses in a file name become underscores on the TV; the title shown in the app
# still comes from the file's own tags.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${TV_HOST:?set TV_HOST to the TV address, e.g. TV_HOST=192.168.1.21 bash work/push-music.sh <fichier>}"
[ "$#" -gt 0 ] || { echo "usage: TV_HOST=… bash work/push-music.sh <fichier audio> [ …]" >&2; exit 2; }
REMOTE=/sdcard/Android/data/com.gathin.tvmusic/files/music
cd "$ROOT"
PY=tools/venv/Scripts/python.exe
tv() { MSYS_NO_PATHCONV=1 "$PY" tools/tv.py "$@"; }

# The transfer's local argument must be a Windows path (the TV one is passed through untouched).
winpath() {
    local dir
    dir="$(cd "$(dirname "$1")" && { pwd -W 2>/dev/null || pwd; })"
    printf '%s/%s' "$dir" "$(basename "$1")"
}

tv "mkdir -p $REMOTE"
for file in "$@"; do
    [ -f "$file" ] || { echo "not a file: $file" >&2; exit 2; }
    base="$(basename "$file")"
    safe="$(printf '%s' "$base" | sed -e 's/[^A-Za-z0-9._-]/_/g')"
    case "$safe" in
        ""|.*) safe="titre-$(date +%s).mp3" ;;
    esac
    MSYS_NO_PATHCONV=1 "$PY" tools/b64push.py "$(winpath "$file")" "$REMOTE/$safe"
done

# The scanner runs on start and on ACTUALISER; restarting is the quickest way to see the new files.
tv 'am force-stop com.gathin.tvmusic; am start -n com.gathin.tvmusic/.MusicActivity'
echo "Pushed to $REMOTE and restarted the app."
