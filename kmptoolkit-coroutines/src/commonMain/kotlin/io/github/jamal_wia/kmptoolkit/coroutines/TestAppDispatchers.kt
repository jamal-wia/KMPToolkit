package io.github.jamal_wia.kmptoolkit.coroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * [AppDispatchers] test double. All three dispatchers collapse onto one [testDispatcher] so code
 * under test runs deterministically on a single virtual-time scheduler instead of hopping across
 * real background threads.
 *
 * ```
 * val scheduler = TestCoroutineScheduler()
 * val dispatchers = TestAppDispatchers(scheduler)
 * val repository = UserRepository(dispatchers)
 * runTest(scheduler) {
 *     // ...
 * }
 * ```
 */
public class TestAppDispatchers(
    scheduler: TestCoroutineScheduler = TestCoroutineScheduler(),
) : AppDispatchers {
    public val testDispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(scheduler)
    override val io: CoroutineDispatcher = testDispatcher
    override val main: CoroutineDispatcher = testDispatcher
    override val default: CoroutineDispatcher = testDispatcher
}
