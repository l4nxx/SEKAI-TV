# SEKAI TV — desktop app

A small companion window for the SEKAI theme: it lists the Android TVs on your network, lets you pick
one, and installs or updates the theme on it over ADB.

It is written in plain Python (standard library + `adb_shell`) and opens its own Chromium window; the
page is served on `127.0.0.1` only.

## Run it

```bash
python -m pip install "adb_shell==0.4.4"
python app.py
```

The TV must accept this computer's ADB key once (`~/.android/adbkey`); the app reads the model and
installs the launcher with it.

## Build a single-file binary

```bash
python -m pip install pyinstaller "adb_shell==0.4.4"
pyinstaller --noconfirm --clean --onefile --windowed --name "SEKAI TV" \
  --add-data "web:web" --add-data "theme:theme" --collect-submodules adb_shell app.py
```

On Windows use `;` instead of `:` in `--add-data`, and add `--icon icon.ico`. The GitHub workflow in
`.github/workflows/build.yml` builds all three platforms and attaches them to a release when a `v*` tag
is pushed.

The release builds are unsigned: macOS refuses the first launch of an unsigned app (right-click it and
choose **Open**, or `xattr -dr com.apple.quarantine "SEKAI TV.app"`), and the macOS binary is built for
Apple Silicon. On Linux, make the downloaded file executable (`chmod +x SEKAI-TV-linux`). The app opens
its window in Chrome, Chromium, Edge or Brave, so one of them has to be installed.

## What the install button does

1. connects to the selected TV over ADB, on port 5555;
2. pushes `sekai-home.apk` and runs `pm install -r` (the APK is the launcher in `launcher/`, built by
   `launcher/gradlew assembleRelease`, or downloaded from this repository's latest release);
3. pushes `theme/config.json` to `/sdcard/Android/data/com.gathin.tvhome/files/config.json`;
4. pushes `theme/wallpaper.jpg` when that file is next to the app, otherwise leaves the TV's wallpaper;
5. sets `com.gathin.tvhome/.MainActivity` as Home and restarts the launcher.

Nothing is uninstalled and the stock launcher is never disabled.
