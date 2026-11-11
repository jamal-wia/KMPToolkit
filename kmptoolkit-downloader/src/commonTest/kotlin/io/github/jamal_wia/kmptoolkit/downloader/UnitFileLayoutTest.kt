package io.github.jamal_wia.kmptoolkit.downloader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The pure half of the path-safety contract: which names a unit gets, which relative paths are
 * accepted, and what a migration may touch. No file is created.
 */
class UnitFileLayoutTest {

    private val sensitive = UnitFileLayout(caseInsensitive = false)
    private val insensitive = UnitFileLayout(caseInsensitive = true)

    // 16
    @Test
    fun `a case-insensitive layout rejects folded reserved names and folds the lock key`() {
        assertEquals(
            InvalidDownloadUnitException.Reason.RESERVED_INTERNAL_PATH,
            reasonOf(insensitive, path = "TMP/Expect/x"),
        )
        assertEquals(insensitive.lockKey(unit("Model")), insensitive.lockKey(unit("model")))
    }

    @Test
    fun `a relativePath under tmp trash is accepted because 3_0_0 had no trash there`() {
        for (layout: UnitFileLayout in listOf(sensitive, insensitive)) {
            assertNull(reasonOf(layout, path = "tmp/trash/a.bin"))
            assertNull(reasonOf(layout, path = "tmp/trash"))
        }
    }

    // 16
    @Test
    fun `a case-sensitive layout accepts a differently cased path and keeps the lock keys apart`() {
        assertNull(reasonOf(sensitive, path = "TMP/Expect/x"))
        assertNotEquals(sensitive.lockKey(unit("Model")), sensitive.lockKey(unit("model")))
    }

    // 17
    @Test
    fun `the hashed key is 52 characters of the lowercase base32 alphabet`() {
        val key: String = UnitFileLayout.hashKey("models/v1")

        assertEquals(52, key.length)
        assertTrue(key.all { it in 'a'..'z' || it in '2'..'7' }, "key was $key")
    }

    // 17
    @Test
    fun `the hashed key is injective over lone surrogates and over empty and unusual ids`() {
        val ids: List<String> = listOf("\uD800", "\uDC00", "?", "", "a", "A", "a/b", "a\u0000b", "😀")
        val keys: Set<String> = ids.map { UnitFileLayout.hashKey(it) }.toSet()

        assertEquals(ids.size, keys.size)
    }

    @Test
    fun `the hashed key of a known id is stable across platforms`() {
        // Pins the algorithm itself (SHA-256 over UTF-16BE, base32): a change would orphan every
        // hashed file on disk. The value was computed with python3: sha256("a".encode("utf-16-be")).
        assertEquals("aivgs6pg3k32uwxeyps6ix36s5yrfj7ggwjyedn6yhwhhcre7e6a", UnitFileLayout.hashKey("a"))
    }

    // 8 (pure part)
    @Test
    fun `a safe id keeps exactly the 3_0_0 names`() {
        val names: UnitFileLayout.Names = sensitive.namesOf(unit("model-1"))

        assertEquals("tmp/model-1.tmp", names.partial)
        assertEquals("tmp/model-1.tmp.complete", names.complete)
        assertEquals("tmp/staging-model-1", names.staging)
        assertEquals("tmp/expect/model-1.sha256", names.record)
        assertEquals("tmp/expect-staged/model-1.sha256", names.stagedRecord)
        assertFalse(names.anyHashed)
    }

    @Test
    fun `an id at the longest legacy length keeps its names and one byte more is hashed`() {
        val longest: String = "a".repeat(247)
        // 247 + "." + "tmp" + ".complete" is more than 255, so the temp names fall back; the id-keyed ones do not.
        val names: UnitFileLayout.Names = sensitive.namesOf(unit(longest, extension = ""))
        assertEquals("tmp/staging-$longest", names.staging)
        assertEquals("tmp/expect/$longest.sha256", names.record)
        assertTrue(names.tempHashed, "247 bytes plus '.' plus '.complete' passes 255")

        val tooLong: UnitFileLayout.Names = sensitive.namesOf(unit("a".repeat(248)))
        assertTrue(tooLong.idHashed)
    }

    @Test
    fun `no name of any unit passes 255 bytes or leaves the tmp directory`() {
        val ids: List<String> = listOf(
            "a".repeat(300), "models/v1", "../x", "a\u0000b", "é".repeat(200), "x/", "/x", "..", "trash/x",
        )
        for (id: String in ids) {
            for (extension: String in listOf("tmp", "a/b", "b".repeat(300), "")) {
                val names: UnitFileLayout.Names = sensitive.namesOf(unit(id, extension = extension))
                for (name: String in listOf(names.partial, names.complete, names.staging, names.record, names.stagedRecord)) {
                    assertTrue(name.startsWith("tmp/"), "$name of '$id'/'$extension'")
                    // path-hygiene-ignore: PH3 UnitFileLayout names are logical and '/'-separated on every platform by contract
                    val segments: List<String> = name.split('/')
                    assertFalse(segments.any { it == ".." || it == "." || it.isEmpty() }, name)
                    assertFalse(name.contains('\u0000'), name)
                    assertTrue(segments.all { it.encodeToByteArray().size <= 255 }, name)
                    if (names.anyHashed && (name == names.partial || name == names.complete) && names.tempHashed) {
                        assertTrue(name.startsWith("${UnitFileLayout.INTERNAL_DIRECTORY}/"), name)
                    }
                }
            }
        }
    }

    @Test
    fun `hashed names of different ids are different and legacy names never reach the internal directory`() {
        val a: UnitFileLayout.Names = sensitive.namesOf(unit("a/b"))
        val b: UnitFileLayout.Names = sensitive.namesOf(unit("a/c"))
        assertNotEquals(a.partial, b.partial)
        assertNotEquals(a.record, b.record)
        assertNotEquals(a.staging, b.staging)

        val legacy: UnitFileLayout.Names = sensitive.namesOf(unit("kmptoolkit-internal"))
        assertFalse(legacy.partial.startsWith("${UnitFileLayout.INTERNAL_DIRECTORY}/"))
        assertFalse(legacy.staging.startsWith("${UnitFileLayout.INTERNAL_DIRECTORY}/"))
        assertNotEquals(UnitFileLayout.INTERNAL_DIRECTORY, legacy.partial)
        assertNotEquals(UnitFileLayout.INTERNAL_DIRECTORY, legacy.staging)
    }

    @Test
    fun `an extension that is not one plain name falls back to tmp in the hashed name`() {
        val names: UnitFileLayout.Names = sensitive.namesOf(unit("model", extension = "a/b"))

        assertTrue(names.tempHashed)
        assertFalse(names.idHashed, "the record stays keyed by the id alone")
        assertEquals("tmp/expect/model.sha256", names.record)
        assertTrue(names.partial.endsWith(".tmp"), names.partial)
    }

    @Test
    fun `relative paths that are broken are rejected with the right reason`() {
        val cases: Map<String, InvalidDownloadUnitException.Reason> = mapOf(
            "" to InvalidDownloadUnitException.Reason.EMPTY,
            "/" to InvalidDownloadUnitException.Reason.EMPTY,
            "./" to InvalidDownloadUnitException.Reason.EMPTY,
            "a/.." to InvalidDownloadUnitException.Reason.EMPTY,
            "../x" to InvalidDownloadUnitException.Reason.ESCAPES_BASE_DIRECTORY,
            "a/../../x" to InvalidDownloadUnitException.Reason.ESCAPES_BASE_DIRECTORY,
            "x\u0000" to InvalidDownloadUnitException.Reason.INVALID_CHARACTER,
            "tmp" to InvalidDownloadUnitException.Reason.RESERVED_INTERNAL_PATH,
            "tmp/" to InvalidDownloadUnitException.Reason.RESERVED_INTERNAL_PATH,
            "tmp/expect" to InvalidDownloadUnitException.Reason.RESERVED_INTERNAL_PATH,
            "tmp/expect-staged/q" to InvalidDownloadUnitException.Reason.RESERVED_INTERNAL_PATH,
            "tmp/expect/foo.sha256" to InvalidDownloadUnitException.Reason.RESERVED_INTERNAL_PATH,
            "tmp/staging-q/r" to InvalidDownloadUnitException.Reason.RESERVED_INTERNAL_PATH,
            "tmp/kmptoolkit-internal/z" to InvalidDownloadUnitException.Reason.RESERVED_INTERNAL_PATH,
            "a/../tmp/expect/x" to InvalidDownloadUnitException.Reason.RESERVED_INTERNAL_PATH,
            "/tmp/expect/x" to InvalidDownloadUnitException.Reason.RESERVED_INTERNAL_PATH,
            "tmp/own.tmp" to InvalidDownloadUnitException.Reason.RESERVED_INTERNAL_PATH,
            "tmp/own.tmp.complete" to InvalidDownloadUnitException.Reason.RESERVED_INTERNAL_PATH,
            "./tmp/./own.tmp" to InvalidDownloadUnitException.Reason.RESERVED_INTERNAL_PATH,
        )
        for ((path: String, expected: InvalidDownloadUnitException.Reason) in cases) {
            assertEquals(expected, reasonOf(sensitive, path = path, id = "own"), "path '$path'")
        }
    }

    // 14 (pure part)
    @Test
    fun `relative paths that work in 3_0_0 are accepted`() {
        for (path: String in listOf("resources/x.bin", "/abs/x", "a/./b", "a/../b", "tmp/foo.bin", "tmpx/a", "tmp.bin", "Tmp/x", "a..b/c")) {
            assertNull(reasonOf(sensitive, path = path, id = "own"), "path '$path'")
        }
        val underTmp = sensitive.inspectRelativePath(unit("own", path = "tmp/foo.bin"))
        assertTrue(underTmp is UnitFileLayout.PathInspection.Accepted && underTmp.underTmp)
        val plain = sensitive.inspectRelativePath(unit("own", path = "a/x"))
        assertTrue(plain is UnitFileLayout.PathInspection.Accepted && !plain.underTmp)
    }

    @Test
    fun `migration lists only the files a hashed unit can own and never another unit's`() {
        fun legacyPaths(id: String, extension: String = "tmp"): List<String> {
            val unit: DownloadUnit = unit(id, extension = extension)
            return sensitive.migrationSteps(unit, sensitive.namesOf(unit)).map { it.legacy }
        }

        assertEquals(
            listOf(
                "tmp/expect/models/v1.sha256",
                "tmp/expect-staged/models/v1.sha256",
                "tmp/models/v1.tmp.complete",
                "tmp/models/v1.tmp",
            ),
            legacyPaths("models/v1"),
        )
        // Another unit's records and staging trees, the parent directory, and the trash are never touched.
        assertEquals(emptyList(), legacyPaths("staging-a/b").filter { it.startsWith("tmp/staging-") })
        assertEquals(emptyList(), legacyPaths("expect/foo", extension = "sha256"))
        assertEquals(emptyList(), legacyPaths("../x").filter { it.contains("..") })
        assertEquals(emptyList(), legacyPaths("trash/x").filter { it.startsWith("tmp/trash/") })
        assertEquals(emptyList(), legacyPaths("model"), "a safe id has nothing to migrate")
        assertEquals(
            listOf("tmp/model.a/b.complete", "tmp/model.a/b"),
            legacyPaths("model", extension = "a/b"),
            "an unsafe extension moves the temp files only; the record is keyed by the id",
        )
    }

    @Test
    fun `a relativePath equal to a hashed unit's own 3_0_0 partial or complete path is rejected`() {
        for (layout: UnitFileLayout in listOf(sensitive, insensitive)) {
            assertEquals(
                InvalidDownloadUnitException.Reason.RESERVED_INTERNAL_PATH,
                reasonOf(layout, path = "tmp/m/v1.tmp", id = "m/v1"),
            )
            assertEquals(
                InvalidDownloadUnitException.Reason.RESERVED_INTERNAL_PATH,
                reasonOf(layout, path = "tmp/./m//v1.tmp.complete", id = "m/v1"),
            )
            // Another unit's id: nothing of this unit's, accepted under tmp/ as any other path.
            assertNull(reasonOf(layout, path = "tmp/m/v1.tmp", id = "other"))
        }
        assertEquals(
            InvalidDownloadUnitException.Reason.RESERVED_INTERNAL_PATH,
            reasonOf(insensitive, path = "TMP/M/V1.TMP", id = "m/v1"),
        )
        assertNull(reasonOf(sensitive, path = "TMP/M/V1.TMP", id = "m/v1"))
    }

    private fun reasonOf(
        layout: UnitFileLayout,
        path: String,
        id: String = "u",
    ): InvalidDownloadUnitException.Reason? =
        (layout.inspectRelativePath(unit(id, path = path)) as? UnitFileLayout.PathInspection.Rejected)?.reason

    private fun unit(id: String, extension: String = "tmp", path: String = "resources/x.bin"): DownloadUnit =
        object : DownloadUnit {
            override val id: String = id
            override val apiPath: String = "/x"
            override val relativePath: String = path
            override val tempExtension: String = extension
            override val group: ResourceGroup = object : ResourceGroup {
                override val key: String = "g"
                override val units: List<DownloadUnit> = emptyList()
            }
        }

    private fun normalizedOf(layout: UnitFileLayout, path: String): String {
        val inspection: UnitFileLayout.PathInspection = layout.inspectRelativePath(unit("own", path = path))
        assertTrue(inspection is UnitFileLayout.PathInspection.Accepted, "path '$path' was $inspection")
        return inspection.normalizedPath
    }

    @Test
    fun `every spelling of one path normalizes to it`() {
        for (path: String in listOf(
            "files/x/../a.bin", "a/b/../../files/a.bin", "/files/a.bin", "files//a.bin", "files/a.bin/",
            "./files/a.bin", "files/a.bin",
        )) {
            assertEquals("files/a.bin", normalizedOf(sensitive, path), "path '$path'")
        }
    }

    @Test
    fun `normalizing never folds case`() {
        assertEquals("Files/A.bin", normalizedOf(insensitive, "Files/A.bin"))
        assertEquals("Files/A.bin", normalizedOf(insensitive, "Files/x/../A.bin"))
    }

    @Test
    fun `an already canonical path comes back as the same instance`() {
        val path: String = "files/a" + ".bin"
        val inspection: UnitFileLayout.PathInspection = sensitive.inspectRelativePath(unit("own", path = path))
        assertTrue(inspection is UnitFileLayout.PathInspection.Accepted)
        assertSame(path, inspection.normalizedPath)
    }
}
