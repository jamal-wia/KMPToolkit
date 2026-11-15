package io.github.jamal_wia.kmptoolkit.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest

/**
 * Breaker round 2 of group sto-api (STO-2/STO-3, ticket release-5-1-0): the parts of the common API that
 * round 1 left alone.
 *
 * - The deprecated `...Or` overloads on [EncryptedKeyValueStorage] (R2/R5 of the storage plan, D-16): they
 *   must answer exactly what the plain helpers answer for every stored text and every failure, read the
 *   store exactly once per call (a recursive overload overflows the stack, a double read consumes two
 *   scripted failures), and never throw.
 * - The enum contracts of `StorageError.kt`: an exhaustive `when` without `else` over each enum compiles
 *   against today's set (StorageOperation with ENSURE_KEY and CHANGE_KEY, D-15/D-19), and every error's text
 *   names its case, so a logged error stays useful.
 * - The typed-read error for every typed getter and every kind of rejected text is the same value-free
 *   error: no text of a rejected value, of any shape, reaches the error.
 */
class StorageApiBreakerR2Test {

    /** An encrypted store that answers [answer] to every read and counts the reads. */
    private class ScriptedSecure(var answer: StorageResult<String?>) : SecureKeyValueStorage {
        var reads: Int = 0
            private set

        override val protection: SecureStorageProtection = SecureStorageProtection.OS_KEY_STORE_APP_ISOLATED

        override suspend fun ensureKey(): StorageResult<Unit> = StorageResult.Success(Unit)

        override suspend fun get(key: String): StorageResult<String?> {
            reads++
            return answer
        }

        override suspend fun put(key: String, value: String): StorageResult<Unit> = StorageResult.Success(Unit)

        override suspend fun remove(key: String): StorageResult<Unit> = StorageResult.Success(Unit)

        override suspend fun clear(): StorageResult<Unit> = StorageResult.Success(Unit)
    }

    private val answers: List<StorageResult<String?>> = listOf(
        StorageResult.Success(null),
        StorageResult.Success(""),
        StorageResult.Success("12"),
        StorageResult.Success("-0"),
        StorageResult.Success("007"),
        StorageResult.Success("2147483648"),
        StorageResult.Success("-9223372036854775808"),
        StorageResult.Success("9223372036854775808"),
        StorageResult.Success("true"),
        StorageResult.Success("false"),
        StorageResult.Success("True"),
        StorageResult.Success(" 1"),
        StorageResult.Success("garbage"),
        StorageResult.Success("\uD800"),
        StorageResult.Failure(StorageError.Unavailable()),
        StorageResult.Failure(StorageError.AccessDenied(platformCode = -128)),
        StorageResult.Failure(StorageError.Unsupported(UnsupportedReason.MISSING_ENTITLEMENT)),
        StorageResult.Failure(StorageError.Undecryptable("k")),
        StorageResult.Failure(StorageError.Undecryptable(null)),
        StorageResult.Failure(StorageError.OperationFailed(StorageOperation.GET, "k")),
        StorageResult.Failure(StorageError.InvalidValue("k")),
    )

    @Suppress("DEPRECATION")
    @Test
    fun `the deprecated overloads answer exactly what the plain helpers answer and read once`() = runTest {
        for (answer: StorageResult<String?> in answers) {
            val store = ScriptedSecure(answer)
            val encrypted: EncryptedKeyValueStorage = store
            val plain: KeyValueStorage = store

            fun <T> same(what: String, deprecated: T, expected: T, readsBefore: Int) {
                assertEquals(expected, deprecated, "$what over $answer differs from the plain helper")
                assertEquals(readsBefore + 2, store.reads, "$what over $answer: one read for each of the two calls")
            }

            var before: Int = store.reads
            same("getStringOrNull", encrypted.getStringOrNull("k"), plain.getStringOrNull("k"), before)
            before = store.reads
            same("getStringOr", encrypted.getStringOr("k", "d"), plain.getStringOr("k", "d"), before)
            before = store.reads
            same("getIntOr", encrypted.getIntOr("k", -1), plain.getIntOr("k", -1), before)
            before = store.reads
            same("getLongOr", encrypted.getLongOr("k", -1L), plain.getLongOr("k", -1L), before)
            before = store.reads
            same("getBooleanOr", encrypted.getBooleanOr("k", true), plain.getBooleanOr("k", true), before)
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun `a secure store typed as itself resolves to the deprecated overloads without recursion`() = runTest {
        // The receiver as an app declares it: the most specific overload is picked. A recursive body would
        // overflow the stack here rather than answer.
        val store = ScriptedSecure(StorageResult.Success("41"))
        val secure: SecureKeyValueStorage = store

        assertEquals(41, secure.getIntOr("k", 0))
        assertEquals(41L, secure.getLongOr("k", 0L))
        assertEquals("41", secure.getStringOr("k", "d"))
        assertEquals("41", secure.getStringOrNull("k"))
        assertEquals(false, secure.getBooleanOr("k", false))
        assertEquals(5, store.reads)
    }

    // -- enum contracts --------------------------------------------------------------------------------

    /** Exhaustive with no `else`: a value added or removed without a decision here stops this file compiling. */
    private fun weakestFirst(protection: SecureStorageProtection): Int = when (protection) {
        SecureStorageProtection.NONE -> 0
        SecureStorageProtection.APP_PROVIDED_KEY -> 1
        SecureStorageProtection.PASSPHRASE -> 1
        SecureStorageProtection.OS_KEY_STORE_USER_SCOPED -> 2
        SecureStorageProtection.OS_KEY_STORE_APP_ISOLATED -> 3
    }

    private fun fix(reason: UnsupportedReason): String = when (reason) {
        UnsupportedReason.NO_USER_SESSION -> "session"
        UnsupportedReason.NO_KEY_STORE -> "keyStore"
        UnsupportedReason.UNSUPPORTED_RUNTIME -> "runtime"
        UnsupportedReason.NATIVE_ACCESS_UNAVAILABLE -> "native"
        UnsupportedReason.MISSING_ENTITLEMENT -> "entitlement"
        UnsupportedReason.UNSUPPORTED_OS -> "os"
        UnsupportedReason.HARDWARE_UNAVAILABLE -> "hardware"
    }

    private fun keyOf(operation: StorageOperation): String = when (operation) {
        StorageOperation.GET, StorageOperation.PUT, StorageOperation.REMOVE -> "entry"
        StorageOperation.CLEAR, StorageOperation.ENSURE_KEY, StorageOperation.CHANGE_KEY -> "store"
    }

    @Test
    fun `every enum value has one decision and a distinct name`() {
        // No order is documented for the protections (only "an unknown value is the weakest"), so only the
        // exhaustive decision is pinned: NONE is the one value that encrypts nothing.
        assertEquals(
            listOf(SecureStorageProtection.NONE),
            SecureStorageProtection.entries.filter { weakestFirst(it) == 0 },
        )
        assertEquals(UnsupportedReason.entries.size, UnsupportedReason.entries.map(::fix).toSet().size)
        assertEquals("store", keyOf(StorageOperation.ENSURE_KEY))
        assertEquals("store", keyOf(StorageOperation.CHANGE_KEY))
        assertEquals(StorageOperation.entries.size, StorageOperation.entries.map { it.name }.toSet().size)
    }

    @Test
    fun `every error prints its case and the fields that decide the reaction`() {
        val texts: Map<String, StorageError> = mapOf(
            "Unavailable" to StorageError.Unavailable(),
            "AccessDenied" to StorageError.AccessDenied(platformCode = -128),
            "Unsupported" to StorageError.Unsupported(UnsupportedReason.HARDWARE_UNAVAILABLE),
            "Undecryptable" to StorageError.Undecryptable(null),
            "OperationFailed" to StorageError.OperationFailed(StorageOperation.CHANGE_KEY),
            "InvalidValue" to StorageError.InvalidValue("k"),
        )
        for ((case: String, error: StorageError) in texts) {
            assertEquals(true, error.toString().contains(case), "$case prints as $error")
        }
        assertEquals(true, texts.getValue("AccessDenied").toString().contains("-128"))
        assertEquals(true, texts.getValue("Unsupported").toString().contains("HARDWARE_UNAVAILABLE"))
        assertEquals(true, texts.getValue("OperationFailed").toString().contains("CHANGE_KEY"))
    }

    // -- typed reads: no shape of rejected text reaches the error --------------------------------------

    @Test
    fun `no rejected text of any shape reaches the typed-read error of any getter`() = runTest {
        val rejected: List<String> = listOf(
            "hunter2-password",
            "99999999999999999999999999",
            "-99999999999999999999999999",
            "4111 1111 1111 1111",
            "+79161234567",
            "1e9",
            "0xDEADBEEF",
            "١٢٣٤٥٦",
            "TRUE-SECRET",
            " 424242 ",
        )
        val store = FakeKeyValueStorage()
        for (text: String in rejected) {
            store.entries["k"] = text
            val results: List<Pair<String, StorageResult<*>>> = listOf(
                "getInt" to store.getInt("k"),
                "getLong" to store.getLong("k"),
                "getBoolean" to store.getBoolean("k"),
            )
            for ((getter: String, result: StorageResult<*>) in results) {
                val error: StorageError? = result.errorOrNull()
                if (error == null) continue
                val chain: MutableList<String> = mutableListOf(error.toString(), result.toString())
                var cause: Throwable? = (error as? StorageError.InvalidValue)?.cause
                while (cause != null) {
                    chain += cause.toString()
                    chain += cause.message.orEmpty()
                    chain += cause.stackTraceToString().lineSequence().first()
                    cause = cause.cause
                }
                val core: String = text.trim()
                for (line: String in chain) {
                    assertFalse(line.contains(core), "$getter of a rejected text leaks it: $line")
                }
            }
        }
    }
}
