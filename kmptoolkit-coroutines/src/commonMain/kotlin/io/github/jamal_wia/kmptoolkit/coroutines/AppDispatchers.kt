package io.github.jamal_wia.kmptoolkit.coroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * Injected dispatcher seam. Reference [AppDispatchers] instead of `Dispatchers.IO` / `.Main` /
 * `.Default` directly, and your tests can substitute [TestAppDispatchers] to run deterministically
 * on a single dispatcher instead of exercising real background threads.
 */
public interface AppDispatchers {
    public val io: CoroutineDispatcher
    public val main: CoroutineDispatcher
    public val default: CoroutineDispatcher
}

/** The real dispatchers, backed by [Dispatchers.IO] / [Dispatchers.Main] / [Dispatchers.Default]. */
public class DefaultAppDispatchers : AppDispatchers {
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val main: CoroutineDispatcher = Dispatchers.Main
    override val default: CoroutineDispatcher = Dispatchers.Default
}
