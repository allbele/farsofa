# Farsofa

A remote play client for Meta Quest that streams a PS5 to a big screen in the headset, built on the
protocol library from [chiaki-ng](https://github.com/chiaki-ng/chiaki-ng).

This project is not affiliated with, endorsed by, or sponsored by Sony Interactive Entertainment.
"PlayStation", "PS5" and "DualSense" are trademarks of their owner and are used only to describe
compatibility.

## Status

Early development. What exists today:

- Sign-in to PSN in the headset to get the account ID automatically.
- Automatic discovery of consoles on the local network, pairing with the 8-digit code, wake from rest mode.
- 1080p60 HEVC streaming in a flat panel, with gamepad input and rumble.

- An immersive OpenXR screen (flat or curved, room or dark background) with resize handles on its
  corners, a control bar below it for size, distance and saved presets, and Touch controllers as a
  fallback gamepad.

Not built yet: play over the internet, the login passcode prompt in the immersive screen.
None of the above has been tested on a headset yet.

## Layout

| Path | What it is |
|---|---|
| `app/src/main/cpp/core/` | The only code that talks to libchiaki (JNI bridge) |
| `app/src/main/java/app/farsofa/vr/` | Kotlin app: `ui`, `psn`, `console`, `input`, `stream`, `core` |
| `third-party/chiaki-ng/` | chiaki-ng v1.10.1 as a git submodule; only `lib/` and the Android decoder helpers are compiled |

## Building

Requirements on macOS:

```sh
brew install openjdk@17 protobuf
brew install --cask android-commandlinetools
sdkmanager "platforms;android-35" "build-tools;35.0.0" "cmake;3.22.1" "ndk;28.2.13676358"

git submodule update --init --recursive

# The protobuf generator used by the library needs a Python with the protobuf module.
python3 -m venv .venv && .venv/bin/pip install protobuf
```

Build and install:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Native build output goes to `~/.cache/farsofa/cxx`, outside the project folder, because OpenSSL's
makefiles fail when the path contains spaces.

Unit tests: `./gradlew :app:testDebugUnitTest`.

## License

AGPL-3.0-only, see [LICENSE](LICENSE). The app links chiaki-ng, which is licensed under AGPL-3.0-only
with an OpenSSL linking exception.
