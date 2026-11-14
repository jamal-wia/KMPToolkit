package io.github.jamal_wia.kmptoolkit.files.testing

import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The published contract against the real Android factory under Robolectric. The `android.system.Os` ops of API 24-25
 * devices cannot run here (`Os` is a stub under Robolectric; an owner device check), but Robolectric's host runtime
 * has java.nio.file, so the factory selects the NIO ops at every SDK level, API 24 included.
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

/** API 24 under Robolectric: the host runtime has java.nio.file, so the NIO ops are selected. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class AndroidSdk24FileAccessContractTest {
    @Test
    fun `API 24 host runtime (NIO selected) keeps the whole contract`() {
        FileAccessContract { realFixture(createFileAccess()) }.verifyAll()
    }
}
