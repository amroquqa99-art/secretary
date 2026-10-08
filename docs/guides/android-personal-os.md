# Android setup and LifeOS knowledge projection

**Status:** Complete
**Last Updated:** 2026-10-09
**Owner:** Secretary fork
**Audience:** Developers and Operators

Build `mobile/android` with JDK 21, Gradle 9.6.0, Android platform 37.0,
build-tools 36.0.0, NDK 30.0.16248370 and CMake 3.22.1. The Gradle bootstrap and
native inference source downloads are checksum-pinned. Run:

```bash
cd mobile/android
./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Minimum Android is 8.0/API 26. Native inference supports arm64-v8a and x86_64.
GGUF models are separate explicit downloads/imports. Arabic/English speech
recognition models are bundled in the APK and verified/extracted on first use.
The build downloads checksum-pinned speech assets into an ignored cache;
`SECRETARY_SPEECH_CACHE_DIR` can point to an existing verified cache. Offline TTS
needs an installed local system voice, and never falls back to a network voice.

In **أنا → السكرتير**, select a language and press **أمر صوتي**. Recognition fills
the input for review before sending. In **أنا → الإعدادات → التنبيه الصوتي
الاختياري**, choose a 2–5-word phrase and start listening. Grant microphone and
notification permissions. Say the phrase followed by a command, or speak the
command within 15 seconds of the phrase. Review pending changes in the app.
Use the notification's **إيقاف** action to stop; each session ends after one hour
and does not restart on boot. Starting foreground voice or typing in the
assistant stops background listening.

Arabic first-use extraction requires about 1.3 GB free space. Recognition is
refused when Android reports low memory or less than 1.1 GB available for Arabic
(500 MB for English). These limits do not certify operation on a 4 GB phone.
If notifications for **استماع السكرتير** are disabled, enable that channel in
Android app notification settings before starting a microphone session.

In **أنا → الإعدادات → ذاكرة LifeOS على الكمبيوتر**, export the life record ZIP to
a private location. Extract into a dedicated directory in the LifeOS vault and
run the installation's normal vault indexing operation. Replace the complete
dedicated directory at each refresh. Editing the projection does not synchronize
changes to Android. This plaintext export is not an encrypted backup.

Settings lists encrypted **الحالات السابقة قبل الاستعادة**. Export a snapshot and
restore it with the password used in the restore operation that created it.
Previous snapshots have distinct filenames and are retained.

An APK with a different signing certificate cannot update an installed app with
the same application ID. Export an encrypted backup before changing installations.
APK assembly and emulator tests do not certify physical-device audio, battery or
4 GB memory behavior. Live LifeOS pairing requires separate implementation.

## Related Documents

- [Features](../specs/product/android-personal-os.md)
- [Ownership](../adr/027-offline-android-life-records.md)
- [Pairing contract](../roadmap/mobile-pairing-protocol.md)
