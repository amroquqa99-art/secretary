# syntax=docker/dockerfile:1.7
# Isolated Android build toolchain for Secretary. Never bake signing keys/models.
# The native Android project currently lives on feat/android-personal-os.
FROM eclipse-temurin:17-jdk-jammy

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_USER_HOME=/root/.gradle \
    PATH=/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:${PATH}

RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates curl unzip git make gcc g++ python3 \
    && rm -rf /var/lib/apt/lists/*

# Official command-line tools archive, verified against Google's SHA-256.
# Source: https://developer.android.com/studio#command-line-tools-only
RUN curl --fail --location --retry 3 \
      https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip \
      --output /tmp/android-cli.zip \
    && echo '4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583  /tmp/android-cli.zip' | sha256sum --check - \
    && mkdir -p "${ANDROID_HOME}/cmdline-tools/latest" \
    && unzip -q /tmp/android-cli.zip -d /tmp/android-cli-extracted \
    && mv /tmp/android-cli-extracted/cmdline-tools/* "${ANDROID_HOME}/cmdline-tools/latest/" \
    && rm -rf /tmp/android-cli.zip /tmp/android-cli-extracted

# Versions match app/build.gradle.kts: compileSdk=37, NDK 30.0.16248370,
# CMake=3.22.1. Do not upgrade independent of the Android build contract.
RUN yes | sdkmanager --sdk_root="${ANDROID_HOME}" --licenses >/dev/null \
    && sdkmanager --sdk_root="${ANDROID_HOME}" \
       'platform-tools' 'platforms;android-37' 'build-tools;37.0.0' \
       'ndk;30.0.16248370' 'cmake;3.22.1'

WORKDIR /workspace/mobile/android
# Project-specific checked SHA-256 Gradle 9.6.0 bootstrap script.
COPY mobile/android/gradlew ./gradlew
RUN chmod +x ./gradlew && ./gradlew --version

COPY mobile/android/ ./
# Warm Maven/Kotlin dependency caches as an image layer, not on every test.
RUN ./gradlew --no-daemon :app:dependencies

# Build a debug APK and run local unit tests. Does not sign a release APK.
CMD ["./gradlew", "--no-daemon", ":app:testDebugUnitTest", ":app:assembleDebug"]
