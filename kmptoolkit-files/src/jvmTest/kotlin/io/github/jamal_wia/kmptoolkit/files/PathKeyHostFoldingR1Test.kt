package io.github.jamal_wia.kmptoolkit.files

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * PathKey.of(path, Always) exists so that spellings of one file share a lock. The host file system is the oracle: when
 * it says two spellings are one entry, the keys must be equal (the other direction, over-folding, only costs
 * parallelism). Every code point that has a case or canonical variant is created as a file and each variant is looked
 * up through the host.
 */
class PathKeyHostFoldingR1Test {

    private fun codePointString(cp: Int): String = String(Character.toChars(cp))

    /** The decomposed spelling whose first letter is title case: polytonic Greek has a letter whose title form differs from both its cases. */
    private fun titleOfDecomposed(name: String): String {
        val decomposed: String = Normalizer.normalize(name, Normalizer.Form.NFD)
        val first: Int = decomposed.codePointAt(0)
        val width: Int = Character.charCount(first)
        return codePointString(Character.toTitleCase(first)) + decomposed.substring(width)
    }

    private fun hostFoldsCase(directory: Path): Boolean {
        Files.createFile(directory.resolve("Probe"))
        return Files.exists(directory.resolve("probe"))
    }

    @Test
    fun `spellings that the host treats as one entry share a key`() {
        // path-hygiene-ignore: PH1 the separator is read only to skip the POSIX-spelling comparison on a Windows host, never joined into a path
        if (File.separatorChar != '/') return // the comparison is of POSIX spellings; Windows has its own run
        val directory: Path = Files.createTempDirectory("kmptoolkit-fold").toRealPath()
        try {
            if (!hostFoldsCase(directory)) return // a case-sensitive host: nothing to fold
            val mismatches: MutableList<String> = ArrayList()
            var compared: Int = 0
            for (cp: Int in 0x20..0x1FFFF) {
                if (cp in 0xD800..0xDFFF || cp == '/'.code) continue
                val name: String = codePointString(cp)
                val variants: Set<String> = linkedSetOf(
                    name.lowercase(),
                    name.uppercase(),
                    codePointString(Character.toTitleCase(cp)),
                    Normalizer.normalize(name, Normalizer.Form.NFD),
                    Normalizer.normalize(name, Normalizer.Form.NFC),
                    titleOfDecomposed(name),
                    Normalizer.normalize(name.uppercase(), Normalizer.Form.NFD),
                    Normalizer.normalize(name.lowercase(), Normalizer.Form.NFD),
                ).filter { it != name && it != "." && it != ".." && it.isNotEmpty() && it.indexOf('/') < 0 }.toSet()
                if (variants.isEmpty()) continue
                val created: Path = directory.resolve(name)
                try {
                    Files.createFile(created)
                } catch (_: Exception) {
                    continue
                }
                try {
                    for (variant: String in variants) {
                        val other: Path = directory.resolve(variant)
                        val sameEntry: Boolean = try {
                            Files.exists(other) && Files.isSameFile(created, other)
                        } catch (_: Exception) {
                            false
                        }
                        if (!sameEntry) continue
                        compared++
                        val a: String = PathKey.of(created.toString(), CaseFolding.Always)
                        val b: String = PathKey.of(other.toString(), CaseFolding.Always)
                        if (a != b) {
                            mismatches += "U+" + cp.toString(16) + " " + name.map { it.code.toString(16) } +
                                " vs " + variant.map { it.code.toString(16) }
                        }
                    }
                } finally {
                    Files.deleteIfExists(created)
                }
            }
            assertTrue(compared > 500, "the probe compared too few pairs: $compared")
            assertTrue(mismatches.isEmpty(), "the host treats these as one file but PathKey gives them different keys: $mismatches")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
