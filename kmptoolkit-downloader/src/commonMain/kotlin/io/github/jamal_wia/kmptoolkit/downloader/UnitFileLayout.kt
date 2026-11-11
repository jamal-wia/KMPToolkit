package io.github.jamal_wia.kmptoolkit.downloader

/**
 * Where a [DownloadUnit]'s own files live under a storage's base directory, and which
 * [DownloadUnit.relativePath] values a storage accepts. Pure string logic, shared by both shipped
 * storages; every returned path is relative to the base directory and uses `/`.
 *
 * **Safe ids keep the 3.0.0 layout byte for byte** (`tmp/<id>.<ext>`, `tmp/<id>.<ext>.complete`,
 * `tmp/staging-<id>`, `tmp/expect/<id>.sha256`, `tmp/expect-staged/<id>.sha256`), because that
 * layout is documented and adopted. An id that cannot be a file name (it contains `/` or NUL, or a
 * derived name would pass 255 bytes) is stored under [INTERNAL_DIRECTORY] instead, named by [hashKey]
 * of the id. Nothing in the legacy layout can reach that subtree: legacy names never contain `/`,
 * and `kmptoolkit-internal` is neither `<id>.<ext>` (no `.`) nor `staging-<id>`.
 *
 * @param caseInsensitive true when the volume folds case (an iOS volume may). Only comparisons of
 *   reserved names and the commit [lockKey] depend on it; no file name is ever changed by it.
 */
internal class UnitFileLayout(private val caseInsensitive: Boolean) {

    /** The names of one unit's files, relative to the base directory. */
    class Names(
        val partial: String,
        val complete: String,
        val staging: String,
        val record: String,
        val stagedRecord: String,
        /** True when [partial] and [complete] are under [INTERNAL_DIRECTORY]. */
        val tempHashed: Boolean,
        /** True when [staging], [record] and [stagedRecord] are under [INTERNAL_DIRECTORY]. */
        val idHashed: Boolean,
    ) {
        val anyHashed: Boolean get() = tempHashed || idHashed
    }

    /** One step of moving a 3.0.0 file to its hashed place; [destination] null means delete [legacy]. */
    class MigrationStep(
        val legacy: String,
        val destination: String?,
        /** A partial or complete data file: only moved when it predates the layout marker. */
        val tempData: Boolean = false,
    )

    /** What [inspectRelativePath] decided. */
    sealed interface PathInspection {
        /**
         * Accepted. [normalizedPath] is the path every file operation must use: the segments kept
         * after `.`, `..` and empty ones are resolved lexically, joined with `/`, in their original
         * case. [underTmp] is true under `tmp/`, which the storage reserves; the caller logs a warning.
         */
        class Accepted(val normalizedPath: String, val underTmp: Boolean) : PathInspection

        class Rejected(val reason: InvalidDownloadUnitException.Reason) : PathInspection
    }

    fun namesOf(unit: DownloadUnit): Names {
        val id: String = unit.id
        val extension: String = unit.tempExtension
        val idSafe: Boolean = isIdLegacy(id)
        val tempSafe: Boolean = idSafe && isTempLegacy(id, extension)
        val key: String? = if (tempSafe) null else hashKey(id)
        val partial: String = if (tempSafe) {
            "$TMP/$id.$extension"
        } else {
            "$INTERNAL_DIRECTORY/$key.${hashedExtension(extension)}"
        }
        return Names(
            partial = partial,
            complete = "$partial.complete",
            staging = if (idSafe) "$TMP/staging-$id" else "$INTERNAL_DIRECTORY/staging-$key",
            record = if (idSafe) "$TMP/expect/$id.sha256" else "$INTERNAL_DIRECTORY/expect/$key.sha256",
            stagedRecord = if (idSafe) {
                "$TMP/expect-staged/$id.sha256"
            } else {
                "$INTERNAL_DIRECTORY/expect-staged/$key.sha256"
            },
            tempHashed = !tempSafe,
            idHashed = !idSafe,
        )
    }

    /**
     * The key two commits (or a commit and a cancel) of the same unit must share. Folded through
     * uppercase then lowercase on a case-insensitive volume (the full case mapping: `ß`/`ss`,
     * `ſ`/`s`, the sigma forms), where `Model` and `model` are one file; over-serializing two
     * distinct ids is harmless. Unicode normalization variants are not folded (no `Normalizer` in
     * common code), which the platform notes document.
     */
    fun lockKey(unit: DownloadUnit): String = if (caseInsensitive) foldCase(unit.id) else unit.id

    /** A new entry's path under the trash directory. The id is not part of it, so its length never matters. */
    fun trashEntry(number: ULong): String = "$TRASH_DIRECTORY/$number"

    /**
     * Decides whether [DownloadUnit.relativePath] may be used. Lexical only: the segments are
     * normalized, and the accepted result carries the normalized path, so the check and every file
     * operation agree on one path whether or not a `..` segment's directory exists, and a symbolic
     * link below the base can never turn a `..` into an escape the check did not see.
     */
    fun inspectRelativePath(unit: DownloadUnit): PathInspection {
        val path: String = unit.relativePath
        if (path.indexOf('\u0000') >= 0) return reject(InvalidDownloadUnitException.Reason.INVALID_CHARACTER)
        val segments: ArrayList<String> = ArrayList()
        var changed = false
        var start = 0
        while (start <= path.length) {
            var end: Int = path.indexOf('/', start)
            if (end < 0) end = path.length
            val length: Int = end - start
            when {
                length == 0 || (length == 1 && path[start] == '.') -> changed = true
                length == 2 && path[start] == '.' && path[start + 1] == '.' -> {
                    if (segments.isEmpty()) return reject(InvalidDownloadUnitException.Reason.ESCAPES_BASE_DIRECTORY)
                    segments.removeAt(segments.lastIndex)
                    changed = true
                }
                else -> segments.add(path.substring(start, end))
            }
            start = end + 1
        }
        if (segments.isEmpty()) return reject(InvalidDownloadUnitException.Reason.EMPTY)
        // No empty, `.` or `..` segment: no leading, trailing or doubled `/` either, so the original
        // string is already the normalized path.
        val normalized: String = if (changed) segments.joinToString("/") else path
        if (fold(segments[0]) != TMP) return PathInspection.Accepted(normalized, underTmp = false)
        if (segments.size == 1) return reject(InvalidDownloadUnitException.Reason.RESERVED_INTERNAL_PATH)
        val second: String = fold(segments[1])
        if (isReservedBelowTmp(second)) return reject(InvalidDownloadUnitException.Reason.RESERVED_INTERNAL_PATH)
        val own: Names = namesOf(unit)
        if (own.tempHashed) {
            // The migration moves the unit's 3.0.0 partial and complete files to their hashed place; a
            // committed resource at one of those paths would be moved with them after a restart.
            val legacyPartial = "$TMP/${unit.id}.${unit.tempExtension}"
            val candidate: String = fold(segments.joinToString("/"))
            if (candidate == fold(legacyPartial) || candidate == fold("$legacyPartial.complete")) {
                return reject(InvalidDownloadUnitException.Reason.RESERVED_INTERNAL_PATH)
            }
        }
        if (segments.size == 2) {
            // The unit's own temp files; a hashed name is below tmp/kmptoolkit-internal, rejected above.
            val candidate = "$TMP/$second"
            if (candidate == fold(own.partial) || candidate == fold(own.complete)) {
                return reject(InvalidDownloadUnitException.Reason.RESERVED_INTERNAL_PATH)
            }
        }
        return PathInspection.Accepted(normalized, underTmp = true)
    }

    /**
     * The 3.0.0 files of a unit whose [names] are hashed, and where each goes, in the order they
     * must be handled: the record, the stale staged record, the complete file, the partial file.
     * A legacy path is only listed when moving it cannot touch anything that belongs to another
     * unit: it must lie under `tmp/` with canonical segments, and a deeper path must not start with
     * a storage-managed directory name (so legacy `expect/foo` + `sha256`, `staging-a/b`, `../x`
     * and `trash/x` are never touched). Legacy staging trees are never migrated.
     */
    fun migrationSteps(unit: DownloadUnit, names: Names): List<MigrationStep> {
        val id: String = unit.id
        val legacyPartial = "$TMP/$id.${unit.tempExtension}"
        val steps: ArrayList<MigrationStep> = ArrayList()
        if (names.idHashed) {
            val record = "$TMP/expect/$id.sha256"
            if (isMigratable(record, firstUnreservedIndex = 2)) steps.add(MigrationStep(record, names.record))
            val staged = "$TMP/expect-staged/$id.sha256"
            if (isMigratable(staged, firstUnreservedIndex = 2)) steps.add(MigrationStep(staged, null))
        }
        if (names.tempHashed && isMigratable(legacyPartial, firstUnreservedIndex = 1)) {
            steps.add(MigrationStep("$legacyPartial.complete", names.complete, tempData = true))
            steps.add(MigrationStep(legacyPartial, names.partial, tempData = true))
        }
        return steps
    }

    private fun isMigratable(legacy: String, firstUnreservedIndex: Int): Boolean {
        if (legacy.indexOf('\u0000') >= 0) return false
        val segments: List<String> = legacy.split('/')
        if (segments[0] != TMP || segments.size < 2) return false
        for (segment: String in segments) {
            if (segment.isEmpty() || segment == "." || segment == "..") return false
        }
        // Deeper than the legacy name of a safe id would be: the first segment below the fixed
        // directories must not be one the storage manages.
        if (segments.size > firstUnreservedIndex + 1 && isManagedBelowTmp(fold(segments[firstUnreservedIndex]))) {
            return false
        }
        return true
    }

    /**
     * Names under `tmp/` that a [DownloadUnit.relativePath] may not use: exactly what 3.0.0 or this
     * library creates and deletes there. `trash` is not among them: it did not exist in 3.0.0 (the
     * trash lives under [INTERNAL_NAME] now), so a resource at `tmp/trash/...` worked and must keep
     * working.
     */
    private fun isReservedBelowTmp(segment: String): Boolean =
        segment == "expect" || segment == "expect-staged" ||
            segment == INTERNAL_NAME || segment.startsWith("staging-")

    /**
     * The wider set the migration refuses to move a legacy file out of: the reserved names plus
     * `trash`, because a legacy `tmp/trash/x.tmp` could only be the 3.0.0 unit `trash/x`'s own
     * partial and is deliberately left where it is (a conservative choice, not a necessity).
     */
    private fun isManagedBelowTmp(segment: String): Boolean = isReservedBelowTmp(segment) || segment == "trash"

    private fun reject(reason: InvalidDownloadUnitException.Reason): PathInspection =
        PathInspection.Rejected(reason)

    /**
     * Folds a path segment on a case-insensitive volume through lowercase, uppercase, lowercase, the full
     * case mapping: `ß`, the capital sharp s `ẞ` and `ss`, `ſ` (long s) and `s`, and the sigma forms each end up
     * equal, as the volume treats them. Two names whose uppercase forms after lowercasing are equal share a key;
     * over-folding is harmless. Checked against the full case-folding table of the Unicode version of the JDK used
     * (no group that folds to one string is split). Unicode normalization variants are not folded.
     */
    private fun fold(segment: String): String = if (caseInsensitive) foldCase(segment) else segment

    private fun foldCase(text: String): String = text.lowercase().uppercase().lowercase()

    companion object {
        private const val TMP: String = "tmp"
        private const val INTERNAL_NAME: String = "kmptoolkit-internal"

        /** The one directory under `tmp/` that only this library writes: hashed names and the trash. */
        const val INTERNAL_DIRECTORY: String = "$TMP/$INTERNAL_NAME"

        /**
         * One-time per-base marker, `fresh` or `upgraded`, written before 4.0.0's first write under
         * `tmp/`; its modification time is the moment after which no file can be a 3.0.0 temp file.
         */
        const val LEGACY_LAYOUT_MARKER: String = "$INTERNAL_DIRECTORY/legacy-layout"

        /** The directory below `tmp/` whose entries the marker decision ignores. */
        const val INTERNAL_DIRECTORY_NAME: String = INTERNAL_NAME

        /** Detached extraction trees waiting to be deleted. */
        const val TRASH_DIRECTORY: String = "$INTERNAL_DIRECTORY/trash"

        private const val MAX_NAME_BYTES: Int = 255
        private const val MAX_HASHED_EXTENSION_BYTES: Int = 64
        private const val BASE32_ALPHABET: String = "abcdefghijklmnopqrstuvwxyz234567"

        /**
         * Lowercase base32 (RFC 4648 alphabet, no padding, 52 characters) of the SHA-256 of [id]'s
         * UTF-16BE code units. UTF-16 rather than `encodeToByteArray()`, which turns every lone
         * surrogate into `?` and would let two different ids share a key; lowercase so it is safe on
         * a case-insensitive volume.
         */
        fun hashKey(id: String): String {
            val bytes = ByteArray(id.length * 2)
            for (index: Int in id.indices) {
                val code: Int = id[index].code
                bytes[index * 2] = (code shr 8).toByte()
                bytes[index * 2 + 1] = code.toByte()
            }
            return base32Lower(sha256Digest(bytes))
        }

        private fun base32Lower(data: ByteArray): String {
            val out = StringBuilder((data.size * 8 + 4) / 5)
            var buffer = 0
            var bits = 0
            for (byte: Byte in data) {
                buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
                bits += 8
                while (bits >= 5) {
                    out.append(BASE32_ALPHABET[(buffer shr (bits - 5)) and 31])
                    bits -= 5
                }
                buffer = buffer and ((1 shl bits) - 1)
            }
            if (bits > 0) out.append(BASE32_ALPHABET[(buffer shl (5 - bits)) and 31])
            return out.toString()
        }

        /** Every unit that could commit in 3.0.0 passes this and [isTempLegacy]: `staging-<id>` and `<id>.sha256` fit one name. */
        fun isIdLegacy(id: String): Boolean = isPlain(id) && utf8Length(id) <= MAX_NAME_BYTES - "staging-".length

        /** [isIdLegacy] plus an extension that is one plain name component and leaves room for `.` and `.complete`. */
        fun isTempLegacy(id: String, extension: String): Boolean =
            isPlain(extension) && utf8Length(id) + utf8Length(extension) + ".complete".length + 1 <= MAX_NAME_BYTES

        private fun hashedExtension(extension: String): String =
            if (isPlain(extension) && utf8Length(extension) <= MAX_HASHED_EXTENSION_BYTES) extension else "tmp"

        /**
         * A name that can be a file name as it is: no `/`, no NUL, and no unpaired surrogate. A lone surrogate has no
         * UTF-8 form, so a file system either refuses the name (Android) or maps it to a replacement character that
         * other ids share (iOS); such an id is stored under a hashed name, which is injective over UTF-16 units.
         */
        private fun isPlain(text: String): Boolean =
            text.indexOf('/') < 0 && text.indexOf('\u0000') < 0 && !hasLoneSurrogate(text)

        private fun hasLoneSurrogate(text: String): Boolean {
            var index = 0
            while (index < text.length) {
                val char: Char = text[index]
                if (char.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
                    index += 2
                    continue
                }
                if (char.isSurrogate()) return true
                index++
            }
            return false
        }

        /** UTF-8 length, counting a lone surrogate as 3 bytes (the worst case). */
        private fun utf8Length(text: String): Int {
            var bytes = 0
            var index = 0
            while (index < text.length) {
                val char: Char = text[index]
                when {
                    char.code < 0x80 -> bytes += 1
                    char.code < 0x800 -> bytes += 2
                    char.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate() -> {
                        bytes += 4
                        index++
                    }
                    else -> bytes += 3
                }
                index++
            }
            return bytes
        }
    }
}
