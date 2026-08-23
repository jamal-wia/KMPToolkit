# Documentation index

This is a learning path, not a reference dump. Read in order the first time; come back to
individual module docs afterward as you need them.

## Read first, once

1. [`00-getting-started.md`](00-getting-started.md) — install the BOM, pull in one module, run a
   working example.
2. [`01-architecture.md`](01-architecture.md) — the principles every module follows, and why: no
   bundled DI, no hardcoded consumer identifiers, no user-facing text, what `@ToolkitInternalApi`
   means, the ABI/semver policy.
3. [`02-platform-setup.md`](02-platform-setup.md) — one-time platform steps shared by several
   modules: Android manifest permissions, `Info.plist` entries, background modes, app-start
   initialization.

## Then, per module you use

Each module has its own `docs/<module>/` folder, numbered in learning order:

| File | Purpose |
|---|---|
| `01-overview.md` | What the module solves, and — just as important — what it explicitly does **not** do |
| `02-getting-started.md` | A minimal working example, five minutes to a compiling result |
| `03-guide.md` | Common scenarios from simple to advanced, error handling, lifecycle |
| `04-api-reference.md` | Every public symbol: signature, contract, thread-safety |
| `05-platform-notes.md` *(if present)* | Android vs. iOS behavior, required permissions/manifest entries |
| `06-testing.md` *(if present)* | Test fixtures the module ships, and how to use them |
| `07-faq.md` *(if present)* | Answers to recurring real questions |

A module with a substantial extension point may add a page for it — `kmptoolkit-outbox` documents
implementing its storage SPI in [`07-custom-store.md`](kmptoolkit-outbox/07-custom-store.md).

Start with the module's `01-overview.md` — if what you need doesn't fit that module's stated scope,
check the "What this is not" section for a pointer to the right one instead.

## Available modules

| Module | What it solves | Docs |
|---|---|---|
| `kmptoolkit-coroutines` | Testable dispatcher seam | [`kmptoolkit-coroutines/`](kmptoolkit-coroutines/01-overview.md) |
| `kmptoolkit-logging` | Tag/level logging with pluggable sinks | [`kmptoolkit-logging/`](kmptoolkit-logging/01-overview.md) |
| `kmptoolkit-haptics` | Haptic feedback | [`kmptoolkit-haptics/`](kmptoolkit-haptics/01-overview.md) |
| `kmptoolkit-audio-player` | Audio playback | [`kmptoolkit-audio-player/`](kmptoolkit-audio-player/01-overview.md) |
| `kmptoolkit-audio-recorder` | Audio recording | [`kmptoolkit-audio-recorder/`](kmptoolkit-audio-recorder/01-overview.md) |
| `kmptoolkit-scheduler` | Exact-time one-shot local alarms | [`kmptoolkit-scheduler/`](kmptoolkit-scheduler/01-overview.md) |
| `kmptoolkit-storage` | Key-value storage, plain and encrypted | [`kmptoolkit-storage/`](kmptoolkit-storage/01-overview.md) |
| `kmptoolkit-platform` | Connectivity, device info, file picker, wake lock, crash log | [`kmptoolkit-platform/`](kmptoolkit-platform/01-overview.md) |
| `kmptoolkit-logging-overlay` | On-screen log overlay for debug builds | [`kmptoolkit-logging-overlay/`](kmptoolkit-logging-overlay/01-overview.md) |
| `kmptoolkit-permission` | Runtime permission request flow | [`kmptoolkit-permission/`](kmptoolkit-permission/01-overview.md) |
| `kmptoolkit-biometric` | Biometric authentication gate | [`kmptoolkit-biometric/`](kmptoolkit-biometric/01-overview.md) |
| `kmptoolkit-settings` | Font scale, theme mode and app language | [`kmptoolkit-settings/`](kmptoolkit-settings/01-overview.md) |
| `kmptoolkit-systembars` | Status and navigation bar control | [`kmptoolkit-systembars/`](kmptoolkit-systembars/01-overview.md) |
| `kmptoolkit-notification` | Local notifications, channels and actions | [`kmptoolkit-notification/`](kmptoolkit-notification/01-overview.md) |
| `kmptoolkit-session` | Session lifecycle and teardown fan-out | [`kmptoolkit-session/`](kmptoolkit-session/01-overview.md) |
| `kmptoolkit-outbox` | Transactional outbox / offline effect queue | [`kmptoolkit-outbox/`](kmptoolkit-outbox/01-overview.md) |
| `kmptoolkit-outbox-sqldelight` | SQLDelight-backed store for the outbox | [`kmptoolkit-outbox-sqldelight/`](kmptoolkit-outbox-sqldelight/01-overview.md) |
| `kmptoolkit-proximity` | Proximity sensor (near/far, event-driven) | [`kmptoolkit-proximity/`](kmptoolkit-proximity/01-overview.md) |

Most of these also publish a `-testing` companion artifact holding their test double, documented in
the same folder's `06-testing.md` rather than a folder of their own. Four do not:
`kmptoolkit-logging`, `kmptoolkit-logging-overlay` and `kmptoolkit-systembars`, whose seams are
already interfaces a test implements directly, and `kmptoolkit-settings`, whose only useful double
is a store — `InMemoryKeyValueStorage` from `kmptoolkit-storage-testing`, as
[`kmptoolkit-settings/03-guide.md`](kmptoolkit-settings/03-guide.md#testing) shows.

The rest of the suite is on the roadmap — see the root [`README.md`](../README.md) module table and
[`CHANGELOG.md`](../CHANGELOG.md).
