# AI prompt: put the SEKAI theme on an Android TV

Copy the block below into an AI coding agent that can run commands on the computer connected to the TV
(Claude Code, Codex, Cursor, Gemini CLI, or similar). Replace the two placeholders first.

- `<TV_IP>`: the TV's address on your network, for example `192.168.1.50`.
- Keep the repository URL as it is if you use this repository.

```text
You are installing the SEKAI theme on my Android TV.

Repository: https://github.com/lucasperrin3010-lang/SEKAI-TV
TV address: <TV_IP>
The computer you are running on is on the same network as the TV.

The theme is a fork of the CleanYourTV launcher (package com.gathin.tvhome) with a Project Sekai look:
teal header band, white panels with a navy edge, Outfit type, a theme window, a credits card. The theme
configuration is theme/sekai/config.json.

Do this, in order, and stop to ask me when a step needs a decision:

1. Check the tools: git, adb, JDK 17, Android SDK platform 34. If one is missing, tell me exactly what to
   install and stop.
2. Connect to the TV with ADB (adb connect <TV_IP>:5555, then adb devices). If the connection is refused,
   tell me how to switch on Developer options and USB/network debugging on this TV and stop.
3. Back up before changing anything: `adb shell pm list packages`, `adb shell pm list packages -d`,
   `adb shell getprop` and `adb shell dumpsys activity activities` into a backup folder on this computer.
4. Build the launcher from launcher/ with `./gradlew assembleRelease` (JAVA_HOME to JDK 17, ANDROID_HOME to
   the SDK). The APK is launcher/app/build/outputs/apk/release/app-release.apk. If the build fails, show me
   the first real error and stop; do not lower minSdk and do not fetch an APK from somewhere else.
5. Install it: `adb install -r` with that APK. Then make it Home:
   `adb shell cmd package set-home-activity com.gathin.tvhome/.MainActivity`.
6. Push the theme: create /sdcard/Android/data/com.gathin.tvhome/files if needed, then push
   theme/sekai/config.json there as config.json. Restart Home:
   `adb shell am force-stop com.gathin.tvhome`. Take a screenshot and show it to me.
7. Explain that the wallpaper and background music are not in the repository: I have to put my own
   wallpaper.jpg (1920x1080) and bgm.mp3 in the same folder. Offer to push them if I give you the paths.
8. Only if I say yes, disable the stock launcher after checking that com.gathin.tvhome is the current Home
   (`adb shell dumpsys activity activities`). Never uninstall anything. Never disable Android core
   packages, the TV input services, the keyboard, Play Store, Play Services, or anything I still use.
   Record the undo command (`adb shell pm enable <package>`) in a file next to the backup.
9. When everything works, close the debugging access I opened: switch network/USB debugging off on the TV
   and check that the ADB port no longer answers.

Rules:
- No root, no factory reset, no uninstall, nothing flashed.
- One package per shell command when disabling; ask me before touching apps I use (streaming apps, voice
  assistant, Chromecast, firmware updater).
- Verify with screenshots after each change, and tell me what you checked and what you could not check.
- Write files and commands in English.
```

If the agent gets stuck, [TUTORIAL.md](TUTORIAL.md) has the same steps by hand.
