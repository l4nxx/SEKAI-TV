#!/usr/bin/env bash
# Build the music app from work/music/ and install it on the TV.
# Uses the portable JDK 17 and Android SDK unpacked under work/toolchain/, so nothing has to be
# installed system-wide. The launcher's wallpaper and its bgm.mp3 are copied into the app's own
# folder on the first install, so the app opens on the same picture and already has something to
# play; a file already there is left alone.
#
# Usage:  TV_HOST=192.168.1.21 bash work/build-music.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${TV_HOST:?set TV_HOST to the TV address, e.g. TV_HOST=192.168.1.21 bash work/build-music.sh}"
JDK="$(cd "$ROOT/work/toolchain/jdk-17.0.20.1+1" && { pwd -W 2>/dev/null || pwd; })"
SDK="$(cd "$ROOT/work/toolchain/sdk" && { pwd -W 2>/dev/null || pwd; })"
[ -x "$ROOT/work/toolchain/jdk-17.0.20.1+1/bin/java" ] || { echo "JDK 17 not found at $JDK" >&2; exit 1; }
[ -d "$ROOT/work/toolchain/sdk/platforms/android-34" ] || { echo "Android SDK not found at $SDK" >&2; exit 1; }

export JAVA_HOME="$JDK" ANDROID_HOME="$SDK" TV_HOST
export PATH="$JDK/bin:$PATH"

# Relative paths on purpose: MSYS_NO_PATHCONV keeps the TV's /sdcard paths intact, which also stops
# it converting the local ones, and a relative path needs no conversion.
cd "$ROOT"
PY=tools/venv/Scripts/python.exe
tv() { MSYS_NO_PATHCONV=1 "$PY" tools/tv.py "$@"; }

echo "Building…"
( cd work/music && ./gradlew assembleRelease --offline -q )
APK=work/music/app/build/outputs/apk/release/app-release.apk
[ -f "$APK" ] || { echo "no APK at $APK" >&2; exit 1; }

echo "Installing $(du -h "$APK" | cut -f1)…"
MSYS_NO_PATHCONV=1 "$PY" tools/b64push.py "$APK" /data/local/tmp/tvmusic.apk
tv 'pm install -r /data/local/tmp/tvmusic.apk'

echo "Seeding the music folder…"
tv 'mkdir -p /sdcard/Android/data/com.gathin.tvmusic/files/music; \
    [ -f /sdcard/Android/data/com.gathin.tvmusic/files/wallpaper.jpg ] || \
      cp /sdcard/Android/data/com.gathin.tvhome/files/wallpaper.jpg /sdcard/Android/data/com.gathin.tvmusic/files/wallpaper.jpg; \
    [ -f /sdcard/Android/data/com.gathin.tvmusic/files/music/Project_Sekai_BGM.mp3 ] || \
      cp /sdcard/Android/data/com.gathin.tvhome/files/bgm.mp3 /sdcard/Android/data/com.gathin.tvmusic/files/music/Project_Sekai_BGM.mp3; \
    ls -l /sdcard/Android/data/com.gathin.tvmusic/files/music'

tv 'am force-stop com.gathin.tvmusic; am start -n com.gathin.tvmusic/.MusicActivity'
echo "Installed. The app is in the launcher's grid as « Musique »."
