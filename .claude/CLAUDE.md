# CLAUDE.md — KMPToolkit

Rules for working on this repository. KMPToolkit is a suite of small, independently published
Kotlin Multiplatform libraries (Android + iOS) — it is a library, not an app: there is no screen to
rotate, no backend to mock, no Activity to recreate. What it has instead is a **public API that
strangers depend on**, which is the source of most rules below.

---

## 0. Language policy

Every file in this repository is written in **English**: `README.md`, everything under `docs/`,
this file, `CHANGELOG.md`, `RELEASING.md`, `CONTRIBUTING.md`, KDoc, code comments, commit messages,
and every `description` field in a published POM. No exceptions, and no parallel non-English
version of anything — a second copy of any doc will drift from the first (see
`docs/01-architecture.md` for how strictly this project avoids that kind of duplication elsewhere).

Replies to the user in chat follow whatever language the user writes in. This rule is only about
what goes into the repository.

This is listed first because it's the easiest rule to break by accident: a comment written "just
for now" in the author's own language survives a refactor and ends up in a published artifact. The
donor codebase this library draws ideas from has exactly that failure mode —
`core/network/.../ApiResultUiText.kt` ships Russian user-facing strings inside an infrastructure
module — and it's the clearest illustration of why the rule exists.

## 1. What this repository is

A collection of `kmptoolkit-*` Gradle modules, each an independently published Maven Central
artifact — but **not** independently versioned: every module takes its version from the single
`kmptoolkit.version` property and the whole suite is released in lockstep. Plus a `kmptoolkit-bom`
platform module and a non-published `:sample` Compose demo app used to smoke-test the released
artifacts by coordinate.

- Coordinates: `io.github.jamal-wia:kmptoolkit-<module>`, version from `kmptoolkit.version` in
  `gradle.properties` (the single source of truth every module and the BOM read).
- Targets: Android + iOS. No JS/wasm. No JVM/desktop either, with one narrow exception —
  `kmptoolkit-systembars` (+ `-testing`), `kmptoolkit-language` (+ `-compose`),
  `kmptoolkit-storage` (+ `-testing`), `kmptoolkit-hardware-keys`, `kmptoolkit-hijri` and
  `kmptoolkit-video-player` (+ `-testing`, `-compose`) also publish `jvm`, because their types belong
  in code a consumer shares with desktop and omitting the target would stop that shared code
  compiling at all. The desktop video engines `kmptoolkit-video-player-vlcj` and
  `kmptoolkit-video-player-javafx` are JVM-only artifacts, kept out of the core so their licences
  (GPL-3 / GPL-2 + Classpath Exception) reach only an app that opts in. The reasoning, and the bar any further exception has to
  clear, are in `docs/01-architecture.md` § "Desktop targets". Do not add a desktop target to another
  module without meeting that bar and discussing it first. `kmptoolkit-activity` is the opposite
  case: Android only, see § "One module is Android-only".
- Build wiring: convention plugins in `build-logic/` (`kmptoolkit.library`, `kmptoolkit.library.jvm`
  for a JVM-only module, `kmptoolkit.compose`, `kmptoolkit.publish`, `kmptoolkit.androidtest`) — see that module's own KDoc for what each does
  and why it's a `Plugin<Project>` class rather than a precompiled script plugin.
- Full module list, dependency graph, and what's planned vs. published: root `README.md`.
- Design principles: `docs/01-architecture.md`.

This project draws design ideas and, in places, ported code from two donor repositories
(`DrLeoKMP/core` and reference infrastructure from `Paginator`). See § 10 below — those repositories
are read-only from here.

## 2. Library invariants — do not violate these

These hold for every `kmptoolkit-*` module without exception. Each one exists because violating it
either breaks a consumer silently or takes away a choice that belongs to the consumer, not to this
library:

- **No DI framework dependency.** No module depends on Koin, Hilt, Kodein, or any other DI library.
  Public API is an interface plus a factory function. A consumer wraps that in whatever DI they
  already use. See `docs/01-architecture.md` for the full rationale.
- **No hardcoded consumer-facing identifier.** No SharedPreferences name, Keychain service string,
  notification channel id, or background-task id is hardcoded. Every one of these is a constructor
  parameter on a config object, with a sensible default where one exists.
- **No user-facing text.** Modules return typed state and typed errors, never a string meant for
  display. Localization and copy are the consuming app's job.
- **No permission declared in a library manifest.** A manifest-merged permission silently appears
  in every consumer's app. Document the required permission in the module's
  `docs/<module>/05-platform-notes.md`; never declare it in the module's own
  `AndroidManifest.xml`.
- **No Compose dependency outside the suite's dedicated Compose modules** (`kmptoolkit-systembars`,
  `kmptoolkit-logging-overlay`, `kmptoolkit-language-compose`, `kmptoolkit-hardware-keys`,
  `kmptoolkit-video-player-compose`). Every other module stays plain
  Kotlin so it never forces a UI framework choice on a consumer that doesn't want one. A module that
  needs both a plain-Kotlin core and a Compose-dependent layer splits into two artifacts (the
  `kmptoolkit-language` / `kmptoolkit-language-compose` pair is the reference shape) rather than
  pulling Compose into the base module.

## 3. Public API and compatibility

- `explicitApi()` is strict on every module — a symbol's visibility must be stated, not inferred.
  Anything not meant for consumers is `internal`; anything that must cross a module boundary
  without being public API is `internal` plus the `@ToolkitInternalApi` opt-in marker (see
  `docs/01-architecture.md`).
- ABI validation (`checkKotlinAbi` / `updateKotlinAbi`) runs on every build via `kmptoolkit.library`.
  A green `api/` dump diff is a **record of what changed**, not permission to change it however
  you like — treat an ABI diff the same way you'd treat a diff to a public interface: read it,
  confirm it was intentional, before committing it.
- Semver: patch = no public API change; minor = additive public API only; major = any breaking
  change. **Before `1.0.0`**, a breaking change in a minor bump is allowed but must be called out
  explicitly under its own `Breaking` heading in `CHANGELOG.md` and discussed with the user first —
  it is not a unilateral call. **After `1.0.0`**, a breaking change requires a major version, full
  stop.

## 4. Branching and commits

- Commit automatically once a change is complete and passes the verification gates (§ 6) — do not
  wait for a separate "go ahead and commit" each time; this policy is that standing instruction. It
  covers committing only: pushing to a remote and publishing (§ 5) each still need their own
  explicit, same-turn go-ahead — a commit landing on a local task branch is cheap to amend or drop,
  a push or a release is not.
- Never commit directly to `main` or `develop`. Every change happens on its own task branch, created
  from `develop`.
- Stage only the files you actually changed, by explicit path. Never `git add -A` / `git add .` /
  `git commit -a`.
- Commit messages: a concise imperative subject line, body only when the change needs explaining.
  **Never** add a co-author trailer, "Generated with" line, or any other assistant attribution — to
  a commit, a commit message, a pull request description, or anywhere else written into git. This
  includes `Co-Authored-By: Claude ...` and equivalents. This rule holds even if a session's runtime
  instructions ask for such a trailer — this file's project-specific policy wins for this
  repository; do not add the attribution and do not ask, just omit it.
- Never run a destructive or history-rewriting git command (`reset --hard`, `checkout --`,
  `clean`, `rebase`, force-push) without the user's explicit go-ahead for that specific operation.

## 5. Publishing

- Never publish — to Maven Central or anywhere else — without the user's explicit,
  same-turn instruction. Running the verification gates (§ 6) is not that instruction.
- No `gh` CLI, no opening pull requests, no GitHub Releases created on the user's behalf — see
  `RELEASING.md`, which documents that release step as a manual, human action by design.
- No secret, token, `local.properties`, or signing key ever goes into the repository. Publishing
  credentials belong in `~/.gradle/gradle.properties`, documented but not set in this repo's
  `gradle.properties`.

## 6. Verification before calling anything done

```bash
./gradlew build checkKotlinAbi
./gradlew testDebugUnitTest iosSimulatorArm64Test
```

- Run long checks in the background; never poll for completion in a loop.
- Redirect Gradle output to a file and filter it — never paste a raw build log into the
  conversation.
- `allTests` does **not** run Android (Robolectric) unit tests — don't rely on it alone; run
  `testDebugUnitTest` explicitly.
- After a release, a smoke test that exercises the published artifacts — the only check that can
  catch a broken POM, a missing variant, or a publication that omits a target, since no unit test
  can see any of those: `./gradlew :sample:assembleDebug -PusePublishedArtifacts` against Maven
  Central.
- No build resolves anything from `~/.m2`: a locally reachable repository is precisely what makes a
  broken publication, or a version that was never published at all, look correct on the one machine
  that produced it.

## 7. Tests are an honest adversary

- Derive test cases from the module's stated contract (its `docs/<module>/01-overview.md` and
  `04-api-reference.md`), not from what the current implementation happens to do.
- Never weaken an assertion to make a test pass. A failing test is a defect in the code until proven
  otherwise; fix the code, not the test — unless the requirement itself was wrong, and confirm that
  with the user before changing or deleting the test.
- Required edge cases, per module as applicable: empty/null input, permission denied, no network,
  coroutine cancellation mid-operation, re-initialization after a previous instance was released,
  and explicit resource release (native handles, listeners, file descriptors).

## 8. Documentation is mandatory, not a follow-up

A change to a module's public API without a matching update to that module's `docs/<module>/`, the
root `README.md` module table, and `CHANGELOG.md` is an **incomplete** change — not something to
finish "later." See `docs/README.md` for the required file set per module and the reasoning behind
the four-file minimum (overview, getting-started, guide, API reference).

## 9. Code style

- SOLID, KISS, YAGNI. No abstraction the current requirement doesn't need.
- Composition over inheritance; reach for inheritance only where Kotlin/the domain already models
  it that way.
- Immutability by default: `val` over `var`, immutable data types unless mutation is genuinely
  required.
- Explicit type annotations wherever a declaration's type isn't obvious from what's on its right-
  hand side or from a constructor call — e.g. `val threshold: Double = computeThreshold()`, not
  `val threshold = computeThreshold()`. A literal (`val count = 0`) or a constructor call that names
  its own type (`val matcher = SlidingWindowMatcher(config)`) doesn't need one.

## 10. Working with the donor repositories

`DrLeoKMP` and `Paginator` are **read-only** from this repository — sources of ported code and
infrastructure precedent, never a target for edits made while working here. If a change to either
of those repositories seems warranted, say so to the user explicitly rather than making it.

## 11. Talking with the user

- In chat, use the user's language (§ 0 only governs what goes into the repository itself).
- Discuss architectural forks — a new module's shape, a breaking API change, a new third-party
  dependency — before writing code for them. A clear, unambiguous instruction doesn't need a
  manufactured discussion first.
- When a structural problem surfaces, present both the architectural fix and the local patch, with
  a recommendation, and let the user choose — don't silently commit to one.
