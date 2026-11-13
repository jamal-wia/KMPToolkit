package io.github.jamal_wia.kmptoolkit.audio.player

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL

/**
 * Boss round 1 of unit C: where [AudioSource.Asset] is looked up on iOS. The factory's KDoc says the
 * bundle root is searched first and the configured subdirectories after it, and the default list is
 * empty, so an asset at the root of the bundle is the common case. Survivor of the boss's iOS mutant
 * P6 (the root dropped from the search) before this class.
 *
 * Only the lookup is asserted: the files are empty, so what the item does after the lookup (fails or
 * stays loading) is not this test's business; real time is only the hang bound of each load.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalForeignApi::class)
class AvPlayerEngineAssetLookupBoss1Test {

    private lateinit var root: String

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
        val base: NSURL = NSURL.fileURLWithPath(NSTemporaryDirectory())
        root = requireNotNull(
            base.URLByAppendingPathComponent("boss1-assets-" + NSProcessInfo.processInfo.globallyUniqueString)?.path
        )
        val manager: NSFileManager = NSFileManager.defaultManager
        check(manager.createDirectoryAtPath(child(root, "sub"), true, null, null))
        check(manager.createFileAtPath(child(root, "tone.wav"), null, null))
        check(manager.createFileAtPath(child(child(root, "sub"), "beep.wav"), null, null))
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(root, null)
        Dispatchers.resetMain()
    }

    private fun child(parent: String, name: String): String =
        requireNotNull(NSURL.fileURLWithPath(parent).URLByAppendingPathComponent(name)?.path)

    private fun engine(): AvPlayerEngine = AvPlayerEngine(
        assetBundle = requireNotNull(NSBundle.bundleWithPath(root)) { "no bundle at $root" },
        assetSubdirectories = listOf("sub"),
        managesAudioSession = false,
    )

    /** Whether the load got past the lookup: anything but "Asset not found" counts. */
    private fun lookupSucceeded(path: String): Boolean = runBlocking {
        val engine: AvPlayerEngine = engine()
        try {
            withTimeoutOrNull(BOUND_MS) { engine.load(AudioSource.Asset(path)) }
            true
        } catch (failure: IllegalArgumentException) {
            if (failure.message?.startsWith("Asset not found") != true) throw failure
            false
        } catch (@Suppress("TooGenericExceptionCaught") other: IllegalStateException) {
            // The empty file failed as an item: the lookup itself found it.
            true
        } finally {
            engine.release()
        }
    }

    @Test
    fun anAssetAtTheBundleRootIsFoundWithSubdirectoriesConfigured() {
        assertTrue(lookupSucceeded("tone.wav"), "an asset at the bundle root was not found")
    }

    @Test
    fun anAssetInAConfiguredSubdirectoryIsFound() {
        assertTrue(lookupSucceeded("beep.wav"), "an asset in a configured subdirectory was not found")
    }

    @Test
    fun aMissingAssetFailsTheLookupAndHoldsNoPlayer() = runBlocking {
        assertFalse(lookupSucceeded("missing.wav"), "a missing asset was reported as found")
        val engine: AvPlayerEngine = engine()
        try {
            engine.load(AudioSource.Asset("missing.wav"))
            error("load of a missing asset returned")
        } catch (expected: IllegalArgumentException) {
            assertFalse(engine.hasPlayer)
        } finally {
            engine.release()
        }
    }

    private companion object {
        const val BOUND_MS: Long = 3_000
    }
}
