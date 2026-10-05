package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.w

/**
 * What a storage found when it looked for the expectation record of a unit's temp file — the
 * record [DownloaderStorage.beginTempFile] writes. Shared by both platform storages so the record
 * format and the "compare every stated expectation" rule exist once; reading and writing the file
 * itself stays per platform.
 */
internal sealed interface RecordedExpectation {

    /** No record: a partial from before the record existed, or a downloader that never began one. */
    data object Missing : RecordedExpectation

    /** A record saying the transfer began without a hash to check. */
    data object NoHash : RecordedExpectation

    /** A record holding the hash the bytes must have. */
    data class Hash(val sha256: Sha256) : RecordedExpectation

    /** A record that exists but cannot be read or understood. Never treated as "no check". */
    data object Unreadable : RecordedExpectation
}

private const val NO_HASH_RECORD: String = "none"
private const val HASH_RECORD_PREFIX: String = "sha256:"

/** The text stored in the record for [expected]; an explicit marker when there is no hash. */
internal fun encodeExpectation(expected: Sha256?): String =
    if (expected == null) "$NO_HASH_RECORD\n" else "$HASH_RECORD_PREFIX${expected.hex}\n"

/** Reads back what [encodeExpectation] wrote; anything else is [RecordedExpectation.Unreadable]. */
internal fun decodeExpectation(text: String): RecordedExpectation {
    val trimmed: String = text.trim()
    if (trimmed == NO_HASH_RECORD) return RecordedExpectation.NoHash
    if (trimmed.startsWith(HASH_RECORD_PREFIX)) {
        val sha256: Sha256? = Sha256.parseOrNull(trimmed.removePrefix(HASH_RECORD_PREFIX))
        if (sha256 != null) return RecordedExpectation.Hash(sha256)
    }
    return RecordedExpectation.Unreadable
}

/**
 * Fails a contradictory catalogue before any byte is spent: a unit that states one hash and a
 * transfer that expects another can never both hold.
 */
internal fun requireConsistentExpectations(unit: DownloadUnit, expectedSha256: Sha256?) {
    val unitSha256: Sha256? = unit.sha256
    require(unitSha256 == null || expectedSha256 == null || unitSha256 == expectedSha256) {
        "DownloadUnit.sha256 of $unit (${unitSha256?.hex}) contradicts the hash the transfer " +
            "expects (${expectedSha256?.hex})"
    }
}

/** Every hash a commit must verify: the unit's own, and the one recorded when the transfer began. */
internal fun requiredHashes(unitSha256: Sha256?, recorded: RecordedExpectation): List<Sha256> =
    listOfNotNull(unitSha256, (recorded as? RecordedExpectation.Hash)?.sha256).distinct()

/** Null when [actualHex] equals every hash in [required]; the failure to throw otherwise. */
internal fun integrityFailure(actualHex: String, required: List<Sha256>): ResourceIntegrityException? {
    val wrong: Sha256 = required.firstOrNull { it.hex != actualHex } ?: return null
    return ResourceIntegrityException(
        "Downloaded resource failed integrity check: SHA-256 is $actualHex, expected ${wrong.hex}",
    )
}

internal const val UNREADABLE_RECORD_MESSAGE: String =
    "Downloaded resource failed integrity check: the recorded expectation of its hash is unreadable"

/**
 * The decision half of a commit's hash check, shared by both platform storages; only reading the
 * record and hashing the file stay per platform.
 *
 * Checks the complete temp file against every hash expected of it — [unitSha256] and the one in
 * [recorded] — hashing it once through [computeHex], and only when there is something to compare.
 * On a mismatch, or on a record that cannot be read (never "no check"), it calls [deleteTempFile]
 * and throws [ResourceIntegrityException]. A missing record only logs a warning: a partial from
 * before records existed, or a downloader that never called [DownloaderStorage.beginTempFile].
 */
internal fun checkExpectations(
    unit: DownloadUnit,
    unitSha256: Sha256?,
    recorded: RecordedExpectation,
    logger: Logger,
    computeHex: () -> String,
    deleteTempFile: () -> Unit,
) {
    if (recorded == RecordedExpectation.Unreadable) {
        deleteTempFile()
        throw ResourceIntegrityException(UNREADABLE_RECORD_MESSAGE)
    }
    if (recorded == RecordedExpectation.Missing) {
        logger.w {
            "No expected-hash record for $unit: a partial from before records existed, or a " +
                "downloader that never called beginTempFile. Only DownloadUnit.sha256 is checked."
        }
    }
    val required: List<Sha256> = requiredHashes(unitSha256, recorded)
    if (required.isEmpty()) return
    val failure: ResourceIntegrityException? =
        integrityFailure(actualHex = computeHex(), required = required)
    if (failure != null) {
        deleteTempFile()
        throw failure
    }
}
