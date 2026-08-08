package io.github.jamal_wia.kmptoolkit.platform.testing

import io.github.jamal_wia.kmptoolkit.platform.accessibility.ReducedMotionProbe

/**
 * A [ReducedMotionProbe] whose answer the test sets.
 *
 * @param enabled the initial answer.
 */
public class FakeReducedMotionProbe(enabled: Boolean = false) : ReducedMotionProbe {

    /**
     * What [isReducedMotionEnabled] returns. Flip it mid-test to model the user changing the
     * setting in Settings — code that read the value once and cached it will fail, which is the
     * point.
     */
    public var enabled: Boolean = enabled

    /** How many times [isReducedMotionEnabled] was called — proof that the value is read live. */
    public var readCount: Int = 0
        private set

    override fun isReducedMotionEnabled(): Boolean {
        readCount++
        return enabled
    }
}
