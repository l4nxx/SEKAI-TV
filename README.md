# TV — lanceur, musique et thème

Sauvegarde du travail fait sur la TV (MiTV, Android TV 9) : le lanceur du dépôt
[CleanYourTV](https://github.com/Gathin23/cleanyourtv) restylé Project Sekai, l'application musique
hors ligne, l'application PC qui envoie les morceaux, et le thème de l'accueil.

## Contenu

| Dossier | Ce que c'est |
|---|---|
| `launcher/` | Le lanceur `com.gathin.tvhome` avec le thème Project Sekai : bandeau turquoise, panneaux blancs à liseré bleu nuit, onglet THÈME (langues, musique), carte CRÉDITS, page de réglages aux couleurs du thème. Sources Java, ressources, Gradle. |
| `music-app/` | L'application Android `com.gathin.tvmusic` : lecture des fichiers locaux du dossier privé de l'app, sans aucune permission ni réseau, avec le mode nuit. |
| `music-pc/` | L'application de bureau (Python) : glisser-déposer de plusieurs morceaux, envoi vers la TV par ADB avec vérification md5. |
| `theme/sekai/config.json` | La configuration du thème de l'accueil (5 applis du dock, fond, focus, animations…). |
| `scripts/` | Les scripts utilisés pour compiler, installer et envoyer la musique. |

## Ce qui n'est pas ici, volontairement

- L'APK compilé, le JDK et le SDK Android (le dossier `toolchain/` pèse plus d'un gigaoctet).
- La musique (`bgm*.mp3`) et le fond d'écran : œuvres de leurs ayants droit, non redistribuées.
- Les captures d'écran et l'état de la TV (`backup/`, `packages.txt`, `wifi.txt`).

Le fond d'écran se remet dans `/sdcard/Android/data/com.gathin.tvhome/files/wallpaper.jpg` et la
musique de fond dans le même dossier en `bgm.mp3` ; le lanceur les lit à l'ouverture.

## Compiler

Lanceur (JDK 17, Android SDK 34) :

```bash
cd launcher
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Application musique :

```bash
cd music-app
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Les morceaux se placent dans `/sdcard/Android/data/com.gathin.tvmusic/files/music` (sous-dossiers
compris, trois niveaux). L'application n'a besoin d'aucune permission : elle ne lit que ce dossier.

Application PC (Python 3, sans dépendance, ouvre le Chromium du PC) :

```bash
cd music-pc
python app.py
```

## Installer le thème

Recopier `theme/sekai/config.json` dans `/sdcard/Android/data/com.gathin.tvhome/files/config.json`
puis relancer l'accueil (`adb shell am force-stop com.gathin.tvhome`). Le lanceur superpose ce fichier
à sa configuration intégrée. Les clés sont celles du dépôt CleanYourTV.

## Licence

Le lanceur est un fork de CleanYourTV (MIT, © Gathin) ; sa licence est conservée dans
`launcher/LICENSE`. L'univers et l'illustration Project Sekai appartiennent à SEGA / Colorful Palette :
ce projet est un projet de fan non officiel, sans affiliation, et aucune de leurs images n'est
distribuée ici.
