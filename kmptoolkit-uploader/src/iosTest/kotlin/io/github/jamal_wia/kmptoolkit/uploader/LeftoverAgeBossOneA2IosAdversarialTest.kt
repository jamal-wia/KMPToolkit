package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.writeToFile

/**
 * A2 boss round 1, claim of 05-platform-notes (outcome journal): leftovers of a write are swept after five minutes on iOS.
 * The existing sweep test ages its leftovers by an hour, so the boundary itself was not pinned: a leftover six minutes
 * old is swept, one four minutes old (a write that may still be running) is kept. Ages are a minute away from the
 * boundary on either side, so the test does not depend on how long it runs.
 */
@OptIn(ExperimentalForeignApi::class)
class LeftoverAgeBossOneA2IosAdversarialTest {

    private val manager: NSFileManager = NSFileManager.defaultManager
    // timing-hygiene-ignore: TH4 private-dir a UUID directory of this test, deleted in cleanUp; nothing else is listed
    private val root: String = NSTemporaryDirectory() + "/kmptoolkit-a2boss1-" + NSUUID().UUIDString()
    private val dir: String = "$root/outcomes"
    private val partial: String = "$root/outcomes-partial"

    @AfterTest
    fun cleanUp() {
        manager.removeItemAtPath(root, error = null)
    }

    private fun names(path: String): List<String> =
        manager.contentsOfDirectoryAtPath(path, error = null).orEmpty().filterIsInstance<String>().sorted()

    private fun leftover(name: String, ageSeconds: Double) {
        val path = "$partial/$name"
        byteArrayOf(1).usePinned { NSData.create(bytes = it.addressOf(0), length = 1.convert()).writeToFile(path, atomically = false) }
        manager.setAttributes(mapOf(NSFileModificationDate to NSDate.dateWithTimeIntervalSinceNow(-ageSeconds)), ofItemAtPath = path, error = null)
    }

    @Test
    fun `a leftover six minutes old is swept and one four minutes old is kept`() {
        val storage = IosOutcomeStorage(dir)
        assertTrue(storage.write("item-1", byteArrayOf(1)), "precondition: the directories exist")
        leftover(".kmptoolkit-111.tmp", 360.0)
        leftover(".kmptoolkit-222.tmp", 240.0)
        leftover("item-2.legacy", 360.0)
        leftover("item-3.legacy", 240.0)

        storage.purgeLeftovers()

        assertEquals(listOf(".kmptoolkit-222.tmp", "item-3.legacy"), names(partial), "the five-minute boundary of 05-platform-notes")
        assertEquals(listOf("item-1"), names(dir), "an entry is never swept")
    }
}
