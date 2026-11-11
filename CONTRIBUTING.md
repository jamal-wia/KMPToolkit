# Contributing

Thanks for considering a contribution to KMPToolkit. This file covers the mechanics; see
`.claude/CLAUDE.md` for the full set of repository rules (language policy, library invariants, API
compatibility, branching, testing, documentation).

## Before you start

- **Open an issue for anything beyond a small fix** — a new module, a public API change, a new
  dependency — so the design can be discussed before code is written. See `docs/01-architecture.md`
  for the principles a new module is expected to follow (no DI framework, no hardcoded consumer
  identifiers, no user-facing text).
- **Check the module boundary first.** Read the target module's `docs/<module>/01-overview.md`
  — specifically its "What this is not" section — before adding a feature. If what you want doesn't
  fit, it may belong in a new module instead of an existing one.

## Making a change

1. Branch from the current release branch (`release/x.y.z`, cut from `main`); never commit directly
   to `main`.
2. Make the change. Every public symbol needs KDoc; every behavioral change needs a matching update
   to that module's `docs/<module>/` (see `.claude/CLAUDE.md` § Documentation is mandatory).
3. Add tests. See `.claude/CLAUDE.md` § Tests — cases are derived from requirements, not from what the
   implementation happens to do; boundary and platform-specific cases are not optional.
4. Run the checks locally before opening a pull request:
   ```bash
   ./gradlew build checkKotlinAbi
   ./gradlew testAndroidHostTest iosSimulatorArm64Test
   ```
5. If your change adds or changes public API, run `./gradlew updateKotlinAbi` and commit the
   updated `api/` dump alongside the code change — don't hand-edit the dump file.
6. Add an entry under `## [Unreleased]` in `CHANGELOG.md`.

## Paths and the path-hygiene check

Tests and code that handle file paths must work on Windows as well as on macOS and Linux. Build a
path with `File(parent, child)` or `Path.resolve`, never by gluing a string with `/` or
`File.separator`; in tests use the helpers of the unpublished `:test-support` module (`TestPaths`,
`TestHost`, `TestFs`) instead of hand-written separators, POSIX permission bits or `/proc`.

`./gradlew build` enforces the most common mistakes: every module registers `checkPathHygiene`
(attached to `check`), a lexical scan of `src/<sourceSet>/kotlin` that fails with `file:line` and the
rule id. iOS source sets are never scanned. The rules:

| Rule | Where | Forbids |
|---|---|---|
| PH1 | every scanned source set | `File.separator`, `File.separatorChar` |
| PH2 | every scanned source set | `PosixFilePermission(s)`, `setPosixFilePermissions`, `.setReadable(` / `.setWritable(` / `.setExecutable(` / `.setReadOnly(`, `UnixOperatingSystemMXBean`, `"/proc/`, `"/dev/fd` |
| PH3 | `*Test` source sets | taking a path apart on `/`: `substringBeforeLast('/')`, `substringAfterLast('/')`, `lastIndexOf('/')`, `.split('/')` |
| PH4 | `*Test` source sets | a path-like name (`dir`, `path`, `base`, `root`, `folder`, `tmp`, `temp`, ...) joined with `/` in a template or a `+ "/..."` |
| PH5 | `*Main` source sets | `startsWith('/')` as an absolute-path check (use `isAbsoluteFilePath` from `kmptoolkit-core`) |

When a hit is legitimate — a URL, a logical name, a fake file system, a POSIX-only test that skips
itself on Windows — say so, with a reason (a suppression without one fails the task):

```kotlin
// path-hygiene-ignore: PH3 the value is a URL, not a file path
val last: String = url.substringAfterLast('/')
```

The comment goes on the offending line or alone on the line above it; several rules are written
`PH3,PH4`. A file whose strings are all logical paths of a fake file system takes one line,
`// path-hygiene-file: logical-paths <reason>`, which exempts PH3, PH4 and PH5 in that file (never
PH1 or PH2). Do not silence a hit you have not read: first try the migration the rule suggests.

The scan is fast feedback, not proof, and it is meant to be. It does **not** catch:

- a separator that reaches a path through a value or a function (`val sep = "/"`, `joinToString("/")`,
  `buildString`), or a join whose operand is not named like a path (`"$parent/$name"`, `"$prefix/x"`,
  `"${file.path}/x"`, `File(a + "/" + b)`);
- a native path compared with a `/` literal (`assertEquals("/data/x", file.path)`), a tree listing
  compared with `/`-separated names, or a `\` literal in code that builds POSIX paths;
- Windows limits that are not about the separator: the 260-character MAX_PATH of native code (the
  SQLite tests), names NTFS rejects or rewrites (trailing dots, `CON`, `:`), case-insensitive names,
  an open file that cannot be deleted, ACL semantics that differ from POSIX permission bits, `\r\n`
  line endings, a server resetting a connection instead of delivering an early reply;
- a POSIX API that is not in the PH2 list (`ProcessBuilder("sh")`, a hard-coded `/tmp`), anything
  outside `src/*/kotlin` (Java sources, resources, build scripts), and a stale suppression: one
  that no longer silences anything is not reported.

The only complete check is to run the tests on Windows (`jvmTest testAndroidHostTest`).

The scanner's own unit tests live in `build-logic` and run through `./gradlew checkBuildLogic`, which
`./gradlew check` and `./gradlew build` include.

## Code style

SOLID / KISS / YAGNI, composition over inheritance, immutability by default, explicit type
annotations wherever a declaration's type isn't otherwise visible — see `.claude/CLAUDE.md` § Code style
for the full list with examples.

## Language

Everything in the repository — code, comments, KDoc, commit messages, documentation — is in
English. See `.claude/CLAUDE.md` § Language policy.

## Reporting issues

Please include: the artifact and version, target platform (Android API level / iOS version),
minimal repro, and expected vs. actual behavior.
