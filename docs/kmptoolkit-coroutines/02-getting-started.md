# kmptoolkit-coroutines — Getting started

A minimal working example, start to finish.

## 1. Add the dependency

```kotlin
dependencies {
    implementation(platform("io.github.jamal-wia:kmptoolkit-bom:<version>"))
    implementation("io.github.jamal-wia:kmptoolkit-coroutines")

    // Only if you want the TestAppDispatchers double (step 4 below)
    testImplementation("io.github.jamal-wia:kmptoolkit-coroutines-testing")
}
```

The production module has no dependencies on other `kmptoolkit-*` modules, and deliberately does
**not** pull in `kotlinx-coroutines-test` — that lives in the separate `-testing` artifact so it
never reaches your app's runtime classpath. See
[`../01-architecture.md`](../01-architecture.md#test-fixtures-ship-as-separate--testing-artifacts).

## 2. Depend on the interface, not on `Dispatchers`

Take `AppDispatchers` as a constructor parameter:

```kotlin
import io.github.jamal_wia.kmptoolkit.coroutines.AppDispatchers
import kotlinx.coroutines.withContext

class UserRepository(
    private val dispatchers: AppDispatchers,
) {
    suspend fun loadUserName(id: String): String = withContext(dispatchers.io) {
        // disk or network work
        "user-$id"
    }
}
```

## 3. Wire the real implementation in production

```kotlin
import io.github.jamal_wia.kmptoolkit.coroutines.DefaultAppDispatchers

val repository = UserRepository(dispatchers = DefaultAppDispatchers())
```

`DefaultAppDispatchers` is a plain class with a no-arg constructor — construct it wherever you build
your object graph. This module ships no DI bindings by design; see
[`../01-architecture.md`](../01-architecture.md) for why.

## 4. Substitute the test double in tests

```kotlin
import io.github.jamal_wia.kmptoolkit.coroutines.testing.TestAppDispatchers
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class UserRepositoryTest {

    @Test
    fun `loadUserName returns the formatted id`() {
        val scheduler = TestCoroutineScheduler()
        val repository = UserRepository(TestAppDispatchers(scheduler))

        runTest(scheduler) {
            assertEquals("user-42", repository.loadUserName("42"))
        }
    }
}
```

`TestAppDispatchers` ships in the module's **main** source set, not its test source set — so it's
available from your test code with the plain `implementation` dependency above, with no extra
test-only artifact to add.

## Expected result

The test passes without starting a background thread, and without touching process-global state
like `Dispatchers.setMain()`. That's the whole point of the module.

## Read next

- [`03-guide.md`](03-guide.md) — passing a shared scheduler, virtual time, common mistakes
- [`04-api-reference.md`](04-api-reference.md) — the full public surface
