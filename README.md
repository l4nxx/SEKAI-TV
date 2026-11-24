# SEKAI-TV

A Project Sekai–inspired theme for Android TV, built on the open-source
[CleanYourTV](https://github.com/Gathin23/cleanyourtv) launcher: a teal header band with its two diamonds,
white panels with a navy edge, Outfit type in capitals, a theme window for the launcher language and the
background music, a credits card, and a settings page in the same colours.

This repository holds the launcher source with the theme applied, the theme configuration, and two ways to
put it on a TV: an AI prompt and a manual tutorial.

## What is inside

| Path | What it is |
|---|---|
| `launcher/` | The CleanYourTV launcher (`com.gathin.tvhome`) with the theme applied. Java, AndroidX, Gradle. |
| `theme/sekai/config.json` | The theme's configuration: dock apps, wallpaper behaviour, colours, focus style, motion. |
| `app/` | The desktop companion app: finds the TVs on the network, picks one, installs the theme. |
| `PROMPT.md` | A prompt to paste into an AI coding agent, so it installs the theme for you. |
| `TUTORIAL.md` | The same job by hand, step by step, with the exact commands. |

## Put it on a TV

- **With an AI agent**: paste [PROMPT.md](PROMPT.md) into Claude Code, Codex, Cursor, Gemini CLI or any
  agent that can run commands on your computer.
- **Without AI**: follow [TUTORIAL.md](TUTORIAL.md).

Both need ADB access to the TV and a computer that can build an Android app (JDK 17 and Android SDK 34, for
example Android Studio).

## The desktop app

`app/` is a small Python program that finds the Android TVs on the local network, lets you pick one,
and installs or updates this theme on it over ADB, with a window drawn in the same colours. Ready-made
builds for Windows, macOS and Linux are on the [Releases](https://github.com/l4nxx/SEKAI-TV/releases)
page; [app/README.md](app/README.md) covers running and building it.

## What is not here

- **No APK.** Build it from `launcher/`; the tutorial shows how.
- **No wallpaper and no music.** The Project Sekai illustration and the background music belong to their
  rights holders. Put your own `wallpaper.jpg` (1920×1080) and, if you want, `bgm.mp3` in the launcher's
  files folder; the theme reads them from there.
- **No music apps.** This repository is the theme only.

## Credits and licence

- Launcher: [CleanYourTV](https://github.com/Gathin23/cleanyourtv) by Gathin, MIT licence. The licence is
  kept in `launcher/LICENSE`.
- Typeface: Outfit, SIL Open Font License 1.1 (`launcher/FONT-LICENSE-OFL.txt`).
- Project Sekai COLORFUL STAGE! feat. Hatsune Miku and its artwork belong to SEGA and Colorful Palette.
  This is an unofficial fan project with no affiliation, and none of their artwork is distributed here.
