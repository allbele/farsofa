# Farsofa

A remote play client for Meta Quest that streams a PS5 to a big screen in the headset, built on the
protocol library from [chiaki-ng](https://github.com/chiaki-ng/chiaki-ng).

This project is not affiliated with, endorsed by, or sponsored by Sony Interactive Entertainment.
"PlayStation", "PS5" and "DualSense" are trademarks of their owner and are used only to describe
compatibility.

This repository holds the source code of the published app, as its license requires.

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

## Thanks

How to reach a DualSense over Bluetooth from a Quest was worked out and written down by the
[P5M](https://github.com/beecrepaldi-afk/P5M) project. Farsofa's implementation is its own, written
from that knowledge.

## License

AGPL-3.0-only, see [LICENSE](LICENSE). The app links chiaki-ng, which is licensed under AGPL-3.0-only
with an OpenSSL linking exception.
