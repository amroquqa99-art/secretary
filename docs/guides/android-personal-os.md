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
GGUF models are separate explicit downloads/imports. Offline TTS needs an
installed local voice; system on-device recognition requires Android 12 and a
compatible installed provider.

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
