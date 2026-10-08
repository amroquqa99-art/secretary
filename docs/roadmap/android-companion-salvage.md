# Native Android modules and ownership

**Status:** Partial
**Last Updated:** 2026-10-09
**Owner:** Secretary fork

The Android application in `mobile/android` owns offline life-management records.
LifeOS owns its server integrations and vault. ADR-027 defines this boundary;
the native SQLite schema is not added to the LifeOS server database.

The native module contains goals, projects, tasks, calendar, habits, knowledge,
reviews, encrypted recovery and optional local inference. Its source comes from
the preserved `archive/pre-lifeos-2026-10-07` branch. Android-specific tests and
physical-device acceptance are required; browser tests do not certify it.

An explicit Markdown export makes Android records searchable in the LifeOS vault
as a read-only projection. It excludes credentials, executable proposals,
social queues and models. It does not authenticate or synchronize devices.

Accessibility blocking and DNS VPN focus filtering are optional permissions,
not authentication mechanisms. DNS forwarding requires explicit Cloudflare
consent for each session. Encrypted backups retain exportable previous-state
snapshots. SQLite itself relies on the application sandbox/device encryption.

Arabic/English recognition is bundled offline; speech output uses installed
offline system voices. Wake listening is an explicit, visible, bounded microphone
session. Device acceptance and authenticated live integration remain pending.
The mobile pairing contract remains authoritative for
that integration.

## Related Documents

- [Record ownership](../adr/027-offline-android-life-records.md)
- [Android features](../specs/product/android-personal-os.md)
- [Android setup](../guides/android-personal-os.md)
- [Pairing contract](mobile-pairing-protocol.md)
