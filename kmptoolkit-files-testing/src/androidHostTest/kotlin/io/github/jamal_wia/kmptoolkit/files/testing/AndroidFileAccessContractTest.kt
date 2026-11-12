package io.github.jamal_wia.kmptoolkit.files.testing

import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The published contract against the real Android factory under Robolectric. API 24 is not run: `android.system.Os`,
 * which the API 24 and 25 ops use, is a stub under Robolectric (see the kmptoolkit-files tests and docs), so that
 * path is an owner device check.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidDefaultSdkFileAccessContractTest {
    @Test
    fun `the real Android FileAccess keeps the whole contract on the default SDK`() {
        FileAccessContract { realFixture(createFileAccess()) }.verifyAll()
    }
}

/** The same on API 26, the first level that has java.nio. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class AndroidSdk26FileAccessContractTest {
    @Test
    fun `the real Android FileAccess keeps the whole contract on API 26`() {
        FileAccessContract { realFixture(createFileAccess()) }.verifyAll()
    }
}
