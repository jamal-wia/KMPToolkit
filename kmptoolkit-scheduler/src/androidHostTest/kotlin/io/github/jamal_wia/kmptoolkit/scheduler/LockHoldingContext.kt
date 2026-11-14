package io.github.jamal_wia.kmptoolkit.scheduler

import android.content.Context
import android.content.ContextWrapper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A context whose `getPackageName` blocks the thread whose name starts with [holderThreadName] (the coroutine debug agent appends a suffix) until [release] is
 * counted down. The scheduler reads the package name only inside its process-wide lock (building an
 * intent), so a call on that thread holds the lock for as long as the test wants, with no dependence
 * on what the scheduler reads from the alarm before it takes the lock. [entered] is counted down
 * when the holder is inside the lock.
 */
internal class LockHoldingContext(
    base: Context,
    private val holderThreadName: String,
    val entered: CountDownLatch,
    val release: CountDownLatch,
) : ContextWrapper(base) {

    override fun getApplicationContext(): Context = this

    override fun getPackageName(): String {
        if (Thread.currentThread().name.startsWith(holderThreadName)) {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        return super.getPackageName()
    }
}
