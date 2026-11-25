# Farsofa

A remote play client for Meta Quest that streams a PS5 to a big screen in the headset, built on the
protocol library from [chiaki-ng](https://github.com/chiaki-ng/chiaki-ng).

This project is not affiliated with, endorsed by, or sponsored by Sony Interactive Entertainment.
"PlayStation", "PS5" and "DualSense" are trademarks of their owner and are used only to describe
compatibility.

## Status

Working on a Quest 3 with a PS5 on the local network:

- Sign-in to PSN in the headset to get the account ID automatically.
- Automatic discovery of consoles on the local network and wake from rest mode.
- 1080p60 HEVC streaming on an immersive OpenXR screen at 120 Hz: flat or curved, in passthrough or a
  dark room, opened where the player is looking.
- The screen is moved by a grip under it and resized by its corners, with Touch controllers or hands;
  a control bar below it holds size, distance, saved presets and the console's login passcode pad.
- A Bluetooth gamepad with vibration, or the Touch controllers as a gamepad.
- A DualSense over Bluetooth with adaptive triggers, its lights, motion sensors and touchpad
  (experimental: it relies on a part of Android that apps are not promised).
- "Smooth motion" (experimental): frames made up for games that run at 30 a second. Level 3 works
  the motion out in shaders of its own (`app/src/main/cpp/xr/motion_flow.cpp`) and shows 60 pictures
  a second, one game picture behind; levels 1 and 2 use the headset chip's frame extrapolation and
  add no delay.
- Button assignment for gamepads Android does not describe the standard way.
- An optional error report the player can send (`tools/report-worker` receives it).

Not exercised on a headset yet: play over the internet through PSN, the button-assignment
wizard, detailed haptics, and pairing with the console's 8-digit code.

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

## Thanks

How to reach a DualSense over Bluetooth from a Quest (adaptive triggers, lights, haptics), and a good
part of what can go wrong on the way, was worked out and written down by the
[P5M](https://github.com/beecrepaldi-afk/P5M) project. Farsofa's implementation is its own, written
from that knowledge.

## License

AGPL-3.0-only, see [LICENSE](LICENSE). The app links chiaki-ng, which is licensed under AGPL-3.0-only
with an OpenSSL linking exception.
