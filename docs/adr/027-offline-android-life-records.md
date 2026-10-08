# Offline Android life-record ownership

**Status:** Complete
**Last Updated:** 2026-10-09
**Decision:** Accepted

## Context

The Android application needs offline life management while LifeOS provides a
server context layer. Writing the same task independently into both systems
would create ambiguous ownership and conflict handling.

## Decision

Android owns the goals, projects, tasks, calendar, habits, notes and reviews
created in its local SQLite database. LifeOS owns records created by its server
and integrations. The native application is in `mobile/android`; its source is
derived from the preserved `archive/pre-lifeos-2026-10-07` branch.

The user can export a plaintext Markdown knowledge snapshot into a dedicated
LifeOS vault directory. This is a read-only projection, not another writable task
store. Refresh replaces the complete dedicated directory so removed Android
records disappear. The projection excludes credentials, executable assistant
proposals, social queues and models.

Authenticated live integration must implement the mobile pairing contract and
explicit conflict/ownership handling. Accessibility and DNS VPN permissions do
not authenticate devices. Neither an export nor a local record edit silently
writes to the LifeOS server.

## Rationale

Local ownership keeps phone records available without a server. An explicit
read-only projection makes those records searchable by LifeOS without implying
that imported task text is authoritative for Android.

## Alternatives Considered

### Server-only records

Rejected because offline phone capture and life management must remain usable
without an active LifeOS installation.

### Independent writable copies

Rejected because there is no implemented authenticated conflict-resolution
contract that can safely reconcile two writers.

## Consequences

### Positive

Android can work independently, and the operator controls when knowledge leaves
the device. Credentials and executable proposals are excluded from projection.

### Negative

The operator must replace the whole exported directory to remove stale records.
Edits made to the projection do not flow back into Android. Live integration
requires separate authenticated pairing and conflict handling.

## Related Documents

### Specifications

- [Mobile pairing](../roadmap/mobile-pairing-protocol.md)

### Operational

- [Android setup](../guides/android-personal-os.md)
