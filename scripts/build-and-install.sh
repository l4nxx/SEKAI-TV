#!/usr/bin/env bash
# Rebuild the Sekai launcher from launcher/ and install it on the TV as Home.
# Uses the portable JDK 17 and Android SDK unpacked under work/toolchain/, so nothing has to be
# installed system-wide. HOME_KEY_SERVICE=1 is always passed: this stick's remote sends
# KEYCODE_ALL_APPS (284) for Home, and Android switches the helper off on every reinstall.
#
# Usage:  TV_HOST=192.168.1.21 bash work/build-and-install.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${TV_HOST:?set TV_HOST to the TV address, e.g. TV_HOST=192.168.1.21 bash work/build-and-install.sh}"
JDK="$(cd "$ROOT/work/toolchain/jdk-17.0.20.1+1" && { pwd -W 2>/dev/null || pwd; })"
SDK="$(cd "$ROOT/work/toolchain/sdk" && { pwd -W 2>/dev/null || pwd; })"
[ -x "$JDK/bin/java" ] || { echo "JDK 17 not found at $JDK" >&2; exit 1; }
[ -d "$SDK/platforms/android-34" ] || { echo "Android SDK not found at $SDK" >&2; exit 1; }
export JAVA_HOME="$JDK" ANDROID_HOME="$SDK" TV_HOST
export MSYS_NO_PATHCONV=1
HOME_KEY_SERVICE=1 bash "$ROOT/launcher/build-and-install.sh"
