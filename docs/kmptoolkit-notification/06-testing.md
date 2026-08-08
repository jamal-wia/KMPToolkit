# kmptoolkit-notification — Testing

## `kmptoolkit-notification-testing`

```kotlin
commonTest.dependencies {
    implementation("io.github.jamal-wia:kmptoolkit-notification-testing:<version>")
}
```

A separate artifact so its test-only machinery never reaches your app's runtime classpath — see
[`docs/01-architecture.md`](../01-architecture.md#test-fixtures-ship-as-separate--testing-artifacts).

### `RecordingNotifier`

```kotlin
public class RecordingNotifier(
    public var result: NotificationResult = NotificationResult.Posted,
) : Notifier {
    public val posted: List<PostedNotification>            // every attempt, oldest first
    public val showing: Map<String, LocalNotification>     // what would be on screen
    public val cancelled: List<String>                     // every id passed to cancel
    public val cancelAllCount: Int
    public fun clear()
}

public data class PostedNotification(public val id: String, public val notification: LocalNotification)
```

Two distinct questions, two collections:

- **`posted` answers "did my code ask for this?"** — every attempt is recorded, including ones the
  configured `result` refused.
- **`showing` answers "would the user see it?"** — only a `Posted` result puts a notification there;
  a re-post under the same id replaces it, `cancel` removes one, `cancelAll` empties it.
  `Coalesced` leaves the previous frame in place, exactly as the platform would.

```kotlin
@Test
fun `finishing a download replaces the progress notification`() = runTest {
    val notifier = RecordingNotifier()
    val presenter = DownloadPresenter(notifier, strings)

    presenter.onProgress(50)
    presenter.onFinished()

    assertEquals(1, notifier.showing.size)
    assertEquals(strings.downloadComplete, notifier.showing.getValue("download").title)
    assertEquals(2, notifier.posted.size)
}

@Test
fun `a download still completes when notifications are denied`() = runTest {
    val notifier = RecordingNotifier(result = NotificationResult.PermissionDenied)

    val outcome = DownloadPresenter(notifier, strings).run()

    assertEquals(DownloadOutcome.Success, outcome)
}
```

**It coalesces nothing.** The double does not replicate the bucket/interval rule, on purpose: a test
that has to reason about a 500 ms throttle to know what its subject posted is testing the fixture
instead of its subject. Set `result = NotificationResult.Coalesced` when you want your code to face
that branch.

**Not thread-safe**, deliberately — plain collections. Drive it from one test coroutine and assert
once the work under test has finished.

## What this module's own suite covers

Derived from the contract in [`01-overview.md`](01-overview.md) and
[`04-api-reference.md`](04-api-reference.md), not from what the implementation happens to do.

**`commonTest`** — runs on JVM and on iOS:

- `ProgressCoalescerTest` — bucketing (including widths 1 and 100), the two frames that are never
  suppressed (non-determinate, and 100%), clamping of out-of-range percentages, per-id independence,
  `forget`/`clear`, and an exact-count assertion that a 0..100 run produces eleven posts.
- `NotificationConfigTest` — defaults, the application-id-derived broadcast action (and that two
  applications never resolve to the same one), and every rejected value.
- `NotificationModelTest` — the defaults a caller gets, and the blank ids/names that fail at
  construction.
- `NoOpNotifierTest` — reports `NotificationsDisabled` and stays callable.

**`androidUnitTest`** (Robolectric, via the `kmptoolkit.androidtest` convention plugin):

- `AndroidNotifierTest` — the happy path, channel creation from the spec, custom sound resolution,
  replace-vs-stack, and each way a post fails: permission absent, notifications disabled app-wide, a
  blocked channel, an icon that does not resolve. Plus cancelling an id that is not showing,
  `cancelAll` on an empty tray, coalescing through the real notifier (including that a coalesced post
  never hides a permission failure), the action-button broadcast and its distinct `PendingIntent`s,
  and the tap target's extras and flags.
- `LibraryManifestTest` — the merged manifest contributes nothing beyond the test harness's own
  entries. See [`05-platform-notes.md`](05-platform-notes.md#permissions-and-the-manifest) for the
  exact pinned set.

**`iosTest`** — what a test binary can honestly assert about `UNUserNotificationCenter`: that an
unauthorized post is reported without the centre being touched at all. Anything that does reach the
centre cannot be unit-tested — a test binary is not an app bundle, and
`currentNotificationCenter()` raises `NSInternalInconsistencyException` there — so the cancel path's
contract is covered on the Android side against a real notification manager, and by
`ProgressCoalescerTest` for the shared half. Whether a notification is *drawn* is a device question.

Virtual time throughout the coalescing tests: a `kotlin.time.TestTimeSource` the test advances by
hand, injected into `ProgressCoalescer`. Verifying a 500 ms throttle by waiting 500 ms would be slow
and flaky.

```bash
./gradlew :kmptoolkit-notification:build :kmptoolkit-notification-testing:build checkKotlinAbi
./gradlew :kmptoolkit-notification:testDebugUnitTest :kmptoolkit-notification:iosSimulatorArm64Test
```

`allTests` does not run the Robolectric tests — run `testDebugUnitTest` explicitly.
