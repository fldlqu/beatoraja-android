# beatoraja (Android)

Repository: https://github.com/fldlqu/beatoraja-android

A from-scratch Android port of [beatoraja](https://github.com/exch-bms2/beatoraja), the Java/libGDX BMS
rhythm game. The project is **Android-only**: the repository is a single Android application module, and
the former desktop launcher/backends (LWJGL3, JavaFX, PortAudio, FFmpeg/JavaCV, Discord RPC, OBS) have been
removed along with the platform abstraction they required.

## Requirements

- Android 8.0+ (API 26) device, **arm64-v8a**
- JDK 17+ and Android SDK 36 (build tools included)
- Gradle 9.6+ (the wrapper `./gradlew` is included)

## Build and run

```bash
./scripts/build.sh          # assembleDebug (uses ./gradlew automatically) -> build/outputs/apk/debug/beatoraja-debug.apk
./scripts/run.sh            # install + launch + capture a screenshot (adb or on-device root)
./scripts/push-skin.sh ModernChic   # copy a skin into the app data directory
./scripts/select-skin.sh ModernChic # point the player config at it
./scripts/prepare-ir.sh <beatoraja-full.zip>  # extract IR plugins (Mocha) from an official package
./scripts/set-ir.sh mocha <user-id> <password> # configure an IR account
```

`scripts/device.sh` contains the shared adb/`su` helpers used by the other scripts. On Termux the scripts
run on the device itself (root `su`), otherwise `adb` is used.

## Project layout

| Path | Contents |
| --- | --- |
| `src/main/java` | the whole game plus the native start/settings screens (`settings/`) |
| `src/main/jniLibs/arm64-v8a/libffmpeg.so` | stripped Android/arm64 FFmpeg executable used only to decode MPEG-1/2 BGA movies (`.mpg`), LGPL build |
| `src/main/assets/game` | bundled game data (default skin/fonts/sounds, test song, placeholder BGM/SE) extracted on first run |
| `src/main/assets/glsl` | shaders, loaded directly from assets |
| `src/main/AndroidManifest.xml`, `src/main/res` | Android app manifest/resources |
| `libs/` | non-Maven pure-Java libraries (BMS parser, LuaJ, FLAC) |
| `scripts/` | build/run/device helper scripts |
| `docs/` | build/usage notes, verification screenshots, Lua API reference |
| `skin/` | local user skins (not part of the APK; `skin/default` is the bundled one) |

Game data is written to
`/storage/emulated/0/Android/data/bms.player.beatoraja/files/beatoraja` (external app dir), with logs in
`logs/beatoraja.log.0`.

## Debug bridge

A debug-only broadcast receiver drives the game headlessly:

```bash
am broadcast -a bms.player.beatoraja.CAPTURE                 # framebuffer screenshot
am broadcast -a bms.player.beatoraja.KEY --ei key 54 --ez down true   # key down (libGDX key code)
am broadcast -a bms.player.beatoraja.TAP --ef x 1200 --ef y 700       # tap
am broadcast -a bms.player.beatoraja.SCAN                     # rescan song database
am broadcast -a bms.player.beatoraja.DUMP                     # dump state
am broadcast -a bms.player.beatoraja.QUIT
```

## Documentation

- [docs/README.md](docs/README.md) - build/usage, start screen and settings, feature matrix, IR setup,
  verification results and the list of intentionally unimplemented functions.
- [third_party/ffmpeg](third_party/ffmpeg/) - licensing/source notes for the bundled FFmpeg executable.
- [docs/lua-main-state-api.md](docs/lua-main-state-api.md) - skin Lua API reference.

## License

The port keeps the upstream beatoraja license (see `LICENSE`); bundled third-party libraries retain their
own licenses.
