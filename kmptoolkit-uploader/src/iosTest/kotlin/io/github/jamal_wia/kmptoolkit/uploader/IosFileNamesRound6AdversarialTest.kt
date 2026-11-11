package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * Round 6 (contract + resource accounting): the on-disk name scheme of the iOS journal and temporary body under ids the
 * earlier rounds did not try (empty, Windows-illegal characters, Unicode in both normalization forms, lone surrogates,
 * names that look like escapes, random fuzz), on pure functions and on the real volume, plus what a directory listing
 * shows while writes are in flight.
 */
@OptIn(ExperimentalForeignApi::class)
class IosFileNamesRound6AdversarialTest {

    private val root: String = NSTemporaryDirectory() + "/kmptoolkit-ios-r6-" + NSUUID().UUIDString()
    private val dir: String = "$root/outcomes"

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, error = null)
    }

    private val corpus: List<String> = listOf(
        "", ".", "..", "...", "a.", "a ", " a", "a b", "CON", "con", "NUL", "aux.txt", "COM1", "LPT9",
        "a<b>c", "a:b", "a\"b", "a|b", "a?b", "a*b", "a\\b", "a/b", "../x", "x/..", "\u0000", "a\u0000b", "\n", "\t",
        "\u00E9", "e\u0301", "\u00C9", "E\u0301", "\u00DF", "SS", "ss", "\u0130", "\u0131", "I", "i", "\u2167", "\u2177", "\u01C5", "\u01C6", "\u01C4",
        "\u65E5\u672C\u8A9E", "\u0424\u043E\u0442\u043E", "\u0424\u041E\u0422\u041E", "\u0444\u043E\u0442\u043E", "\uD83D\uDE00", "\uD83D\uDE00\uD83D\uDE00", "\uD800", "\uDC00", "\uD800\uDC00", "a\uD800", "a\uDC00",
        "~", "~_", "~_~a", "~_~~0041", "~~0041", "~a", "~A", "~~", "~_a", "~_A", "a~b", "a~~b", "x".repeat(200),
        "X".repeat(100), "kmptoolkit_upload_a.tmp", "-rf", "-", "_", "0", "00000000-0000-0000-0000-000000000000",
        "ABCDEFAB-ABCD-ABCD-ABCD-ABCDEFABCDEF", "abcdefab-abcd-abcd-abcd-abcdefabcdef", "AbCdEfAb-ABCD-abcd-ABCD-abcdefabcdef",
        "ABCDEFAB-ABCD-ABCD-ABCD-ABCDEFABCDEG", "ABCDEFAB-ABCD-ABCD-ABCD-ABCDEFABCDEFF",
    )

    /** The escaped name of every id decodes to the id, uses a safe alphabet, and no two ids share a name even case-folded. */
    @Test
    fun `journal names of odd ids round trip stay in a safe alphabet and never fold onto each other`() {
        val ids: List<String> = corpus.distinct()
        val names: List<String> = ids.map(::journalFileName)
        ids.zip(names).forEach { (id, name) ->
            assertEquals(id, itemIdOfJournalFileName(name), "'${id.escapedForMessage()}' became '$name' and read back as another id")
            assertTrue(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\u0000' !in name, "unusable name '$name'")
            assertTrue(name.all { it.code in 0x21..0x7e }, "'$name' leaves printable ASCII")
        }
        val folded: Map<String, List<String>> = ids.zip(names).groupBy({ it.second.lowercase() }, { it.first.escapedForMessage() })
        assertTrue(folded.values.all { it.size == 1 }, "ids sharing one case-folded name: ${folded.filterValues { it.size > 1 }}")
    }

    /** A kept name is the id; every other name starts with `~_`, so the two never meet. */
    @Test
    fun `kept names and escaped names are disjoint and an escaped name never equals a kept id`() {
        val kept: List<String> = corpus.filter(::keepsOwnFileName)
        kept.forEach { assertEquals(it, journalFileName(it)) }
        corpus.filterNot(::keepsOwnFileName).forEach { id ->
            val name: String = journalFileName(id)
            assertTrue(name.startsWith("~_"), "'${id.escapedForMessage()}' is not kept but its name '$name' has no escape prefix")
            assertTrue(!keepsOwnFileName(name), "the escaped name '$name' of '${id.escapedForMessage()}' is itself a kept id")
        }
    }

    /** Random ids over a hostile alphabet: round trip, injectivity case-folded, and a decoder that never throws on any name. */
    @Test
    fun `a fuzz of random ids and random file names keeps the mapping a bijection and the decoder total`() {
        val random = Random(60_606)
        val alphabet: List<Char> = listOf(
            'a', 'b', 'z', 'A', 'B', 'Z', '0', '9', '-', '_', '.', '~', ' ', '/', ':', '*', '\u00E9', '\u0301', '\u00DF', '\u0130', '\u65E5', '\uD83D', '\uDE00', '\uD800',
        )
        val ids: MutableSet<String> = linkedSetOf()
        repeat(3000) { ids += buildString { repeat(random.nextInt(0, 9)) { append(alphabet.random(random)) } } }
        val seenNames: MutableMap<String, String> = hashMapOf()
        ids.forEach { id ->
            val name: String = journalFileName(id)
            assertEquals(id, itemIdOfJournalFileName(name), "round trip of '${id.escapedForMessage()}' via '$name'")
            val other: String? = seenNames.put(name.lowercase(), id)
            assertTrue(other == null, "'${id.escapedForMessage()}' and '${other?.escapedForMessage()}' share the case-folded name '$name'")
        }
        val nameAlphabet: List<Char> = "~_aAbB0z.-9fF".toList()
        repeat(5000) {
            val name: String = buildString { repeat(random.nextInt(0, 14)) { append(nameAlphabet.random(random)) } }
            val id: String = itemIdOfJournalFileName(name)
            // A name this version did not produce is the id itself (a 4.0.0 spelling); one it did produce decodes to the id it came from.
            assertTrue(id == name || journalFileName(id) == name, "'$name' decoded to '$id' whose own name is '${journalFileName(id)}'")
        }
    }

    /** The temporary body name of odd ids: ASCII, one path component, bounded, and distinct case-folded. */
    @Test
    fun `temporary body names of odd ids are usable file names that stay apart`() {
        val ids: List<String> = corpus.distinct().filterNot { it.any { c -> c.isSurrogate() } }
        val names: List<String> = ids.map(::temporaryBodyFileName)
        names.forEach { name ->
            assertTrue(name.length <= 255 && '/' !in name && name.all { it.code in 0x21..0x7e }, "unusable body name '$name'")
        }
        val folded: Map<String, List<String>> = ids.zip(names).groupBy({ it.second.lowercase() }, { it.first.escapedForMessage() })
        assertTrue(folded.values.all { it.size == 1 }, "ids sharing one case-folded body name: ${folded.filterValues { it.size > 1 }}")
    }

    /** Every corpus id round trips through the real volume (APFS ignores case and normalization): own bytes back, own id listed, own delete. */
    @Test
    fun `every odd id keeps its own entry on the real volume`() {
        val storage = IosOutcomeStorage(dir)
        val ids: List<String> = corpus.distinct().filter { it.isNotEmpty() && it != "." && it != ".." && '\u0000' !in it && '/' !in it }
        ids.forEachIndexed { index, id ->
            assertTrue(storage.write(id, byteArrayOf(index.toByte(), 42)), "could not write '${id.escapedForMessage()}'")
        }
        ids.forEachIndexed { index, id ->
            assertContentEquals(byteArrayOf(index.toByte(), 42), storage.read(id), "'${id.escapedForMessage()}' reads another entry")
        }
        assertEquals(ids.sorted(), storage.itemIds(), "the sweep lists other ids than were written")
        ids.forEach { storage.delete(it) }
        assertEquals(emptyList(), storage.itemIds())
        assertEquals(emptyList(), NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, error = null).orEmpty())
    }

    private fun String.escapedForMessage(): String = map { if (it.code in 0x20..0x7e) it.toString() else "\\u" + it.code.toString(16).padStart(4, '0') }.joinToString("")
}
