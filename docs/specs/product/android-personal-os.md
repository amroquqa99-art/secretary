# Android personal secretary

**Status:** Partial
**Last Updated:** 2026-10-09
**Owner:** Secretary fork

The native Android application owns offline life-management records. Its Arabic
interface uses `0–9` for generated numbers and preserves authored content.

| Area | Behavior |
| --- | --- |
| Life hierarchy | Four life areas; lifetime, 10-year, annual, monthly, weekly and daily goals |
| Goals and projects | SMART validation, measurements, milestones, risks and project attachments |
| Tasks and planning | Dependencies, subtasks, recurrence, calendar, proposed day/week plans |
| Habits and review | Weekly targets, habit history, daily/weekly reviews and behavior records |
| Knowledge | Markdown, links/backlinks and bounded search across local life-record categories |
| Assistant | Durable conversation, local commands and optional GGUF inference with bounded context |
| Speech | Bundled Arabic/English offline recognition; installed offline system voices for speech output |
| Wake listening | Explicit microphone session, visible stop notification, at most one hour; foreground interaction stops it |
| Approval | Exact proposals need confirmation; repeated confirmation does not execute twice |
| Proactivity | Opt-in generic local daily attention notification, without record changes |
| Recovery | Encrypted backups and exportable retained previous-state snapshots |
| LifeOS | User-requested plaintext read-only knowledge export |

SQLite relies on the Android sandbox/device encryption, rather than SQLCipher.
Optional social collaboration needs its own compatible backend. Local model
quality, phone memory, microphone behavior and battery use need device acceptance.
Recognition models are verified and extracted on first use. Background speech
before a wake phrase is discarded; the recognized command following a phrase
is stored in the assistant inbox. Background replies are generic; private reply
details remain in the app. Voice commands cannot confirm proposals in the
background. Listening stops when its notification is unavailable. Device
acceptance for Arabic recognition quality, memory and battery remains pending.
Automatic LifeOS pairing and live synchronization are not implemented.

## Related Documents

- [Android setup](../../guides/android-personal-os.md)
- [Offline ownership](../../adr/027-offline-android-life-records.md)
