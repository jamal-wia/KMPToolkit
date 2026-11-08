package io.github.jamal_wia.kmptoolkit.downloader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * A volume that folds case (an iOS volume may) folds by the Unicode case-folding table, which is more
 * than `lowercase()`: the long s (U+017F) is the same letter as `s`, so `tmp/ſtaging-b` is `tmp/staging-b`
 * on APFS. The reserved-name check of a case-insensitive layout must see through it, or a resource can
 * be committed on top of a directory the storage manages ("names something the storage manages itself").
 */
class UnitFileLayoutFoldRound5Test {

    private val insensitive = UnitFileLayout(caseInsensitive = true)

    private fun reasonOf(path: String, id: String = "u"): InvalidDownloadUnitException.Reason? {
        val unit: DownloadUnit = object : DownloadUnit {
            override val id: String = id
            override val apiPath: String = "/x"
            override val relativePath: String = path
            override val group: ResourceGroup = object : ResourceGroup {
                override val key: String = "g"
                override val units: List<DownloadUnit> = emptyList()
            }
        }
        return (insensitive.inspectRelativePath(unit) as? UnitFileLayout.PathInspection.Rejected)?.reason
    }

    @Test
    fun `the Kelvin sign spelling of the internal directory is rejected`() {
        assertEquals(
            InvalidDownloadUnitException.Reason.RESERVED_INTERNAL_PATH,
            reasonOf("tmp/Kmptoolkit-internal/x"),
        )
    }

    @Test
    fun `a long s spelling of a staging directory is rejected`() {
        assertEquals(
            InvalidDownloadUnitException.Reason.RESERVED_INTERNAL_PATH,
            reasonOf("tmp/ſtaging-other/x"),
        )
    }

    @Test
    fun `a long s spelling of the staged record directory is rejected`() {
        assertEquals(
            InvalidDownloadUnitException.Reason.RESERVED_INTERNAL_PATH,
            reasonOf("tmp/expect-ſtaged/other.sha256"),
        )
    }

    @Test
    fun `a long s spelling of the unit's own temp file is rejected`() {
        assertEquals(
            InvalidDownloadUnitException.Reason.RESERVED_INTERNAL_PATH,
            reasonOf("tmp/ſave.tmp", id = "save"),
        )
    }

    private fun unitWithId(id: String): DownloadUnit = object : DownloadUnit {
        override val id: String = id
        override val apiPath: String = "/x"
        override val relativePath: String = "files/x.bin"
        override val group: ResourceGroup = object : ResourceGroup {
            override val key: String = "g"
            override val units: List<DownloadUnit> = emptyList()
        }
    }

    @Test
    fun `ids that one case-insensitive file shares get one commit lock`() {
        assertEquals(insensitive.lockKey(unitWithId("stra\u00dfe")), insensitive.lockKey(unitWithId("STRASSE")))
        assertEquals(insensitive.lockKey(unitWithId("\u017fave")), insensitive.lockKey(unitWithId("save")))
        assertEquals(insensitive.lockKey(unitWithId("Model")), insensitive.lockKey(unitWithId("model")))
    }

    @Test
    fun `a case-sensitive layout keeps the spellings apart and a different id apart`() {
        val sensitive = UnitFileLayout(caseInsensitive = false)
        assertNotEquals(sensitive.lockKey(unitWithId("stra\u00dfe")), sensitive.lockKey(unitWithId("STRASSE")))
        assertNotEquals(sensitive.lockKey(unitWithId("\u017fave")), sensitive.lockKey(unitWithId("save")))
        assertNotEquals(insensitive.lockKey(unitWithId("save")), insensitive.lockKey(unitWithId("safe")))
    }

    @Test
    fun `upper case spellings of the reserved names are rejected and other tmp names still work`() {
        assertEquals(
            InvalidDownloadUnitException.Reason.RESERVED_INTERNAL_PATH,
            reasonOf("tmp/EXPECT-STAGED/x"),
        )
        assertEquals(null, reasonOf("tmp/trash/x"))
    }
}
