# kmptoolkit-coroutines — Testing

`kmptoolkit-coroutines-testing` ships `TestAppDispatchers`, the `AppDispatchers` double your tests
substitute for `DefaultAppDispatchers`.

## Add the dependency

```kotlin
dependencies {
    implementation("io.github.jamal-wia:kmptoolkit-coroutines")
    testImplementation("io.github.jamal-wia:kmptoolkit-coroutines-testing")
}
```

It is a separate artifact so that `kotlinx-coroutines-test` never reaches your app's runtime
classpath — see [`../01-architecture.md`](../01-architecture.md#test-fixtures-ship-as-separate--testing-artifacts).

## Using it

`TestAppDispatchers` collapses `io`, `main` and `default` onto one `UnconfinedTestDispatcher`, so
code under test runs deterministically on a single virtual clock instead of hopping across real
background threads:

```kotlin
@Test
fun `loadUserName returns the formatted id`() {
    val scheduler = TestCoroutineScheduler()
    val repository = UserRepository(TestAppDispatchers(scheduler))

    runTest(scheduler) {
        assertEquals("user-42", repository.loadUserName("42"))
    }
}
```

Pass the scheduler explicitly whenever more than one collaborator is involved — two
`TestAppDispatchers` built without one run on **different** virtual clocks, so `advanceTimeBy` on
one will not move the other. [`03-guide.md`](03-guide.md#sharing-one-scheduler-across-the-test)
covers this and the rest of the scenarios in full; there is no separate testing story to learn,
because testing is what this module is for.

## What it replaces

Without the seam, a test that exercises `Dispatchers.Main` needs `Dispatchers.setMain()` /
`resetMain()`, which is process-global state that leaks between tests. Substituting
`TestAppDispatchers` removes that boilerplate entirely — nothing global is touched, so tests stay
independent of execution order.
