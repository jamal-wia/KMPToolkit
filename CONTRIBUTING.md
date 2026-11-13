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
| PH1 | every scanned source set | `File.separator`, `File.separatorChar` (also as a static import), `"file.separator"`, `FileSystems.getDefault().separator` |
| PH2 | every scanned source set | `PosixFilePermission(s)`, `setPosixFilePermissions`, `.setReadable(` / `.setWritable(` / `.setExecutable(` / `.setReadOnly(` (also without a receiver, as `setWritable(false)` / `setReadOnly()`), `UnixOperatingSystemMXBean`, `"/proc/`, `"/dev/fd` |
| PH3 | `*Test` source sets | taking a path apart on `/`: `substringBeforeLast('/')`, `substringAfterLast('/')`, `lastIndexOf('/')`, `.split('/')`, with or without further arguments |
| PH4 | `*Test` source sets | a path-like name (`dir`, `path`, `base`, `root`, `folder`, `tmp`, `temp`, ...) joined with `/` in a template (`"$dir/x"`, `"${x.path}/x"`) or a `+ "/..."` |
| PH5 | every scanned source set that is not a `*Test` one | `startsWith('/')` or `startsWith(PATH_SEPARATOR)` as an absolute-path check, `path[0] == '/'`, `path.first() == '/'`, and a `val x = "/"` declaration (use `Paths.isAbsolute` from `kmptoolkit-files`) |

When a hit is legitimate — a URL, a logical name, a fake file system, a POSIX-only test that skips
itself on Windows — say so, with a reason (a suppression without one fails the task):

```kotlin
// path-hygiene-ignore: PH3 the value is a URL, not a file path
val last: String = url.substringAfterLast('/')
```

The comment goes on the offending line or alone on the line above it; several rules are written
`PH3,PH4`. A file whose strings are all logical paths of a fake file system takes one line,
`// path-hygiene-file: logical-paths <reason>`, which exempts PH3, PH4 and PH5 in that file (never
PH1 or PH2, and never in a production source set: there it is itself a violation). Prefer line
suppressions in a file that mixes host paths and logical ones. Do not silence a hit you have not read: first try the migration the rule suggests.

The scan is fast feedback, not proof, and it is meant to be: of the 48 separator and POSIX failures of the
first Windows run it catches about 43 (~90%) with the rules as shipped. It does **not** catch:

- a separator that reaches a path through a value or a function (`val sep = "/"` used later,
  `joinToString("/")`, `buildString`), or a join whose operand is not named like a path
  (`"$parent/$name"`, `"$prefix/x"`, `File(a + "/" + b)`); a name that merely contains a path word
  (`attempts` contains `temp`) is caught, wrongly, and needs a suppression;
- an absolute-path check spelled another way (`path.startsWith(root)`, `path.isNotEmpty() && path[0] ==
  SEP`), a separator constant that is not named `...SEPARATOR` / `...SLASH`, or one that is qualified
  (`startsWith(Paths.PATH_SEPARATOR)`);
- a permission setter called without a receiver and with a non-literal argument (`apply { setWritable(flag) }`);
  `setWritable(false)` and `setReadOnly()` are caught;
- a native path compared with a `/` literal (`assertEquals("/data/x", file.path)`), a tree listing
  compared with `/`-separated names, a `\` literal in code that builds POSIX paths;
- a rule hit that spans lines (the scan is per line), and Kotlin code outside `src/*/kotlin` (Java sources,
  resources, build scripts); the `jvmShared` folder of `:test-support` is scanned, iOS and other native
  source sets never;
- Windows limits that are not about the separator: the 260-character MAX_PATH of native code (the
  SQLite tests), names NTFS rejects or rewrites (trailing dots, `CON`, `:`), case-insensitive names,
  an open file that cannot be deleted, ACL semantics that differ from POSIX permission bits, `\r\n`
  line endings, a server resetting a connection instead of delivering an early reply;
- a POSIX API that is not in the PH2 list (`ProcessBuilder("sh")`, a hard-coded `/tmp`), and a
  suppression that no longer silences anything (not reported) or whose reason is not true (a reason is
  required, not verified).

The only complete check is to run the tests on Windows (`jvmTest testAndroidHostTest`).

The scanner's own unit tests live in `build-logic` and run through `./gradlew checkBuildLogic`, which
`./gradlew check` and `./gradlew build` include.

## Timing and determinism in tests

A test must give the same result on a loaded machine as on an idle one. Wait on what the code under
test produces (a latch, a `CompletableDeferred`, a flow value, an idle signal, a barrier after the last
request), never on elapsed time; use virtual time (`runTest`, an injected clock) for anything the library
times, keep real time to a hang bound that fails with its own message, draw every random choice from
`Random(seed)` with a fixed default seed and print the seed in the failure message, keep files in a
directory the test creates and deletes, and read no environment variable (knobs are system properties
forwarded from a Gradle property). Real media, sockets and WorkManager are measured from timestamps taken
where the value is produced or from protocol events, not by reading shared state after a sleep.

`./gradlew build` enforces the mechanical part: every module registers `checkTimingHygiene` (attached to
`check`), a lexical scan of `src/*Test/kotlin` that fails with `file:line` and the rule id:

| Rule | Forbids | Suppression category (line above) |
|---|---|---|
| TH1 | `Thread.sleep(`, `TimeUnit.<X>.sleep(`, a statically imported `<UNIT>.sleep(` or `sleep(`, `LockSupport.parkNanos(`; in iOS test source sets `usleep(` and `sleep(` | `inject` (latency a fake peer injects), `poll` (inside a bounded polling helper; the file carries `// timing-hygiene-file: poll-helper <reason>` and contains no `@Test`) |
| TH2 | `System.currentTimeMillis()`, `System.nanoTime()` (also as `System::nanoTime`), `measureTimeMillis`, `measureNanoTime`, `measureTime`, `measureTimedValue`, `TimeSource.Monotonic`, `Clock.System.now()`, `NSDate()`, `CFAbsoluteTimeGetCurrent` | `bound` (hang bound or deadline only), `measure R-<n>` (a measured quantity under a named ruling), `producer` (timestamp taken where the value is produced) |
| TH3 | `Random.next*`, `Random.Default`, `ThreadLocalRandom`, `Math.random(`, a no-argument `Random()`, `SecureRandom(`, `.shuffled()`, `.shuffle()`, `.random()` and `.randomOrNull()` without a `Random`, `Collections.shuffle(list)`, `UUID.randomUUID(` | `name` (a unique name, never a behavioural choice) |
| TH4 | `"java.io.tmpdir"`, `NSTemporaryDirectory(` | `private-dir` (the build-provided per-task directory, never listed or counted as a whole), `create` (only to create the test's own directory) |
| TH5 | `System.getenv(`; in iOS test source sets `getenv(` | `host` (a host fact, for example restoring `TZ`) |
| TH6 | per test class (ruling R-8): an `@Config(` (also over several lines) with a level outside 24 to 35, more than six levels, a level that is not an integer literal, or `minSdk` / `maxSdk` that are not both literals of at most six levels; and a file whose `@Config` levels (class plus methods) add up to more than six | none: extending it needs a ruling and its own heap |

A legitimate case says so, with the category and a reason, alone on the line directly above the hit (a
suppression without a category or a reason fails the task; stacked comments cover several rules):

```kotlin
// timing-hygiene-ignore: TH1 inject the fake server delays its reply to model a slow peer
Thread.sleep(50)
```

The hits that existed when the scan landed are recorded in `build-logic/timing-hygiene-baseline.txt`, one
line `<module>/src/<path>:<rule>:<count>` per file and rule. It is a ratchet: a count above its line (a
new hit) fails, and a count below it (the file improved) also fails until the line is lowered or removed
in the same commit, so the baseline can only shrink. Never raise a line and never regenerate the file
wholesale; a renamed or moved file moves its line with the same count in the same commit, which is the only
allowed added line; each module writes its current counts, in the same format, to
`<module>/build/reports/timing-hygiene/report.txt`, which is the quickest way to read the new number.

A count baseline cannot see a swap: removing one baselined construct and adding another of the same rule
in the same file leaves the count equal. So a change to a baselined file must not add any construct from
the table even when its count is unchanged, and a reviewer reads the added lines of every touched baselined
file. To check the baseline itself: `git diff <base>... -- build-logic/timing-hygiene-baseline.txt | grep
'^+[^+#]'`; every printed line must pair with a removed line of the same file and an equal or higher count.
Review checklist beyond the scan: a `delay(` on a real dispatcher (`runBlocking`, `Dispatchers.IO`) and a
busy spin (`Thread.yield()`, `Thread.onSpinWait()`) used as a wait are not flagged and are rejected in review.

The scan is lexical and per line: it cannot see a wait hidden behind a helper, a delay on a real
dispatcher (`delay` is not flagged), or a literal level passed through a constant. It stops new flaky tests;
it does not prove a test deterministic. Before committing a new or changed test, run the class repeatedly
alone and under CPU load; a single failure means the test is not ready. The scanner's unit tests run
through `./gradlew checkBuildLogic`, like those of the path-hygiene scan.

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
