# Install the SEKAI theme without AI

Every step by hand. You need the TV, a computer on the same network, and about 30 minutes.

## What you need

- An Android TV with Android 9 (API 28) or later. The launcher's `minSdk` is 26.
- A computer with **JDK 17** and **Android SDK Platform 34** (Android Studio installs both), or the Gradle
  wrapper in `launcher/` with those two set.
- **ADB** on the computer: `adb` from Android Studio's platform-tools, or `apt install adb` on Linux.
- The files of this repository: `git clone https://github.com/lucasperrin3010-lang/SEKAI-TV.git`.

## 1. Switch on debugging on the TV

1. Settings → Device Preferences → About → press **Build** seven times.
2. Back to Settings → Device Preferences → **Developer options** → **USB debugging** on.
3. On Android 11 and later there is also **Network debugging**; switch it on. On Android 9, connect a USB
   cable once and run `adb tcpip 5555`, then unplug it.

From the computer:

```bash
adb connect <TV_IP>:5555
adb devices
```

`adb devices` must list the TV as `device`, not `unauthorized`. If it says unauthorized, accept the prompt on
the TV screen with the remote.

## 2. Back up the TV's state

```bash
mkdir -p backup
adb shell pm list packages      > backup/packages.txt
adb shell pm list packages -d   > backup/disabled.txt
adb shell getprop               > backup/getprop.txt
adb shell dumpsys activity activities > backup/activities.txt
```

Keep these files: they are how you undo everything later.

## 3. Build the launcher

```bash
cd launcher
./gradlew assembleRelease
```

On Windows: `gradlew.bat assembleRelease`. If Gradle cannot find Java or the SDK, set `JAVA_HOME` to the
JDK 17 folder and `ANDROID_HOME` to the Android SDK folder, then run it again.

The APK is `launcher/app/build/outputs/apk/release/app-release.apk` (about 600 KB).

## 4. Install it and make it Home

```bash
adb install -r launcher/app/build/outputs/apk/release/app-release.apk
adb shell cmd package set-home-activity com.gathin.tvhome/.MainActivity
```

Press **Home** on the remote: the teal band and the dock should appear. If Android asks which launcher to
use, choose **Home** (`com.gathin.tvhome`) and "Always".

## 5. Push the theme configuration

The launcher reads `config.json` and the wallpaper from its own files folder:

```bash
adb shell mkdir -p /sdcard/Android/data/com.gathin.tvhome/files
adb push theme/sekai/config.json /sdcard/Android/data/com.gathin.tvhome/files/config.json
adb shell am force-stop com.gathin.tvhome
```

Press **Home** again. The dock now follows the theme's five apps (Play Store, YouTube, Netflix, Prime
Video, Spotify); an app that is not installed on your TV is skipped, so you may see fewer tiles.

Android 11 and later can refuse `adb push` into `Android/data`. In that case push to `/sdcard/Download/`
and copy it from the TV's own file manager, or run `adb shell` and use `cp` from `/data/local/tmp/`.

## 6. Add your own wallpaper and music (optional)

Neither is in the repository, because they belong to their rights holders.

```bash
adb push my-wallpaper.jpg /sdcard/Android/data/com.gathin.tvhome/files/wallpaper.jpg
adb push my-music.mp3     /sdcard/Android/data/com.gathin.tvhome/files/bgm.mp3
adb shell am force-stop com.gathin.tvhome
```

Use a 1920×1080 JPEG for the wallpaper. The music loops on Home; its volume is in the **THEME** window,
**MUSIC** row (Off, 20 %, 40 %, 60 %).

## 7. Optional: turn off the stock launcher

Do this only once the new Home works and its theme is applied. Without it, the TV may still start on its
old home screen.

```bash
# Find the old launcher first; it is not always the same package.
adb shell cmd package resolve-activity -c android.intent.category.HOME
# Then disable it (undo: adb shell pm enable <package>)
adb shell pm disable-user --user 0 com.google.android.tvlauncher
```

Never uninstall anything, never disable Android core packages (`com.android.*`, `com.google.android.*`
system services), the TV input service, the keyboard, Play Store or Play Services. TVs from CVTE
(`com.cvte.*`) must keep their stock launcher: skipping that can leave the set without Home.

## 8. Optional: the remote's Home button

Some remotes send `KEYCODE_ALL_APPS` for Home, which Android gives to the stock launcher; once the stock
launcher is disabled, the button does nothing. The launcher ships a small accessibility helper for this
(`HomeKeyService`). The upstream script enables it:

```bash
TV_HOST=<TV_IP> bash launcher/build-and-install.sh
```

That script rebuilds, installs, sets Home, grants the permissions the helper needs and enables it. Read its
first lines before running it.

## 9. Close the debugging access

When you are done:

```bash
adb shell settings put global adb_enabled 0
adb shell settings put global development_settings_enabled 0
adb disconnect
```

Then check from the computer that the port no longer answers: `adb connect <TV_IP>:5555` must fail.

## If something goes wrong

- **The theme colours are not there.** `config.json` is not in the right folder, or Home was not restarted.
  Check with `adb shell ls -l /sdcard/Android/data/com.gathin.tvhome/files/` and force-stop Home again.
- **The TV boots to its old home screen.** The stock launcher is still enabled, or it was re-enabled by
  firmware. Do step 7, then reboot once.
- **No tiles.** The dock package names differ on your TV. Run `adb shell pm list packages` and edit
  `"dock_apps"` in `theme/sekai/config.json`, then push it again (step 5).
- **Put everything back.** Install and enable the old launcher again, `adb shell pm enable` every package
  you disabled, then `adb uninstall com.gathin.tvhome` if you no longer want the theme.
