# Secretary native Android — container build

**Status:** Android Docker toolchain added to branch
`infra/android-docker-20261009` (based on `feat/android-personal-os`).
Docker image build, Gradle unit tests and debug APK output are **not verified**
by this change.

## Commands

From the repository root, with Docker Engine + Compose v2 and Linux/amd64
container support:

```sh
docker compose -f mobile/android/compose.yaml build android-build
docker compose -f mobile/android/compose.yaml run --rm android-build
```

The first build downloads and installs a **version-matched** toolchain:
JDK 17; Google's SHA-256-verified command-line tools; Android API 37;
Build Tools 37.0.0; NDK 30.0.16248370; CMake 3.22.1; and Gradle 9.6.0
via this repository's verified `gradlew` bootstrap script. Gradle/Maven
dependencies are prewarmed in the image. The NDK and native llama.cpp build
may require significant disk space, RAM and network access on first build.

The Compose command runs local unit tests then assembles a debug APK. When
it succeeds, the expected host output is:

```text
mobile/android/app/build/outputs/apk/debug/app-debug.apk
```

The Compose volume exports only build outputs; it does **not** mount
credentials, secrets, model weights or an entire host checkout. This is an
APK build toolchain, **not** Android running inside Docker and **not** a
release-signing or physical-device validation system.

## Offline re-use

After the image has successfully built, a compatible host can reuse it
without downloading its SDK toolchain:

```sh
docker image save secretary-android-dev:local -o secretary-android-image.tar
docker image load -i secretary-android-image.tar
```

The first full native build may still fetch the pinned llama.cpp tarball via
CMake's FetchContent. Before claiming completely offline rebuilds, verify
that all artifacts and dependencies are cached and then run Gradle with
`--offline`, on the actual image and platform. A saved toolchain image alone
is **not** proof that all project dependencies are present offline.

## Constraints

- Keep Gradle wrapper checksum, SDK/NDK/CMake pins and official
  command-line-tools checksum synchronized with Android sources.
- Install SDK/NDK only through the image; no routine host-wide setup.
- Build with isolated inputs. Never copy signing material or personal model
  data into images.
- Verify unit tests, APK creation, installation on the user's physical phone,
  microphone/voice, architecture and offline operation separately.
- A successful debug build does not imply release readiness.
