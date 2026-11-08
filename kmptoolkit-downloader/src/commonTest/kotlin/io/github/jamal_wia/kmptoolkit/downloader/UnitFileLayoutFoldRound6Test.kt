package io.github.jamal_wia.kmptoolkit.downloader

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Round 5 folded with uppercase-then-lowercase to give `ß`/`ss`, `ſ`/`s` and the sigma forms one key. One more
 * character of the Unicode case-folding table is left over: the capital sharp s `ẞ` (U+1E9E) folds to `ss` like `ß`, but
 * `uppercase()` leaves it alone and `lowercase()` makes it `ß`, so it ends as `ß` where `ß` and `ss` end as `ss`.
 * APFS folds all three into one file name (checked by hand: creating `ẞ.probe` makes `ss.probe` exist). A search over
 * every code point for groups with one case folding but several keys finds this character and no other.
 */
class UnitFileLayoutFoldRound6Test {

    private val insensitive = UnitFileLayout(caseInsensitive = true)

    private fun unitWithId(id: String, relativePath: String = "files/x.bin"): DownloadUnit = object : DownloadUnit {
        override val id: String = id
        override val apiPath: String = "/x"
        override val relativePath: String = relativePath
        override val group: ResourceGroup = object : ResourceGroup {
            override val key: String = "g"
            override val units: List<DownloadUnit> = emptyList()
        }
    }

    @Test
    fun `ids that differ by the capital sharp s share one commit lock`() {
        val key: String = insensitive.lockKey(unitWithId("strasse"))
        assertEquals(key, insensitive.lockKey(unitWithId("straße")))
        assertEquals(key, insensitive.lockKey(unitWithId("STRAẞE")))
        assertEquals(key, insensitive.lockKey(unitWithId("straẞe")))
    }

    @Test
    fun `a capital sharp s spelling of the unit's own temp file is rejected`() {
        val unit: DownloadUnit = unitWithId("ss", relativePath = "tmp/ẞ.tmp")
        val inspection: UnitFileLayout.PathInspection = insensitive.inspectRelativePath(unit)
        assertEquals(
            InvalidDownloadUnitException.Reason.RESERVED_INTERNAL_PATH,
            (inspection as? UnitFileLayout.PathInspection.Rejected)?.reason,
        )
    }
}
