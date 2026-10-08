# Android container policy

This Android branch has its own isolated Docker build toolchain.
Read [the Android container guide](README.md) before modifying build
dependencies or downloading host-global SDK packages.

- Keep Gradle, Android SDK (compile SDK 37), build-tools 37.0.0, NDK
  30.0.16248370, CMake 3.22.1, JDK 17 and verified CLI tool checksums
  synchronized with the real Gradle project files.
- Use `docker compose -f mobile/android/compose.yaml build android-build`
  and the service's test/assemble command for targeted reproducibility checks.
  Record actual execution results. This container is currently **unverified**.
- Commit only recipes, checksums and source. Keep user files, credentials,
  private voice/model payloads, APK signing keys and local Gradle caches out
  of the repository and container image.
- A debug APK from a container is not a release-signed APK; device testing,
  audio permissions, offline model quality and performance remain separate
  acceptance requirements.
- Do not automatically update the Android SDK/NDK/Gradle or replace a pinned
  llama.cpp CMake source hash with a mutable branch.
