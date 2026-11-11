package io.github.jamal_wia.kmptoolkit.downloader

/**
 * One downloadable thing — the smallest unit this library fetches, commits and can be asked about.
 * It maps 1:1 to a remote location (via [apiPath], resolved to a real URL by the host's
 * [io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadUrlResolver]) and to one place on disk
 * (via [relativePath]).
 *
 * **The library ships no units.** Which resources exist is the host application's catalogue, not
 * this module's business — the same split [io.github.jamal_wia.kmptoolkit.uploader] draws between
 * owning the queue and not owning any effect. A host declares its own units as `data object`s
 * (fixed assets it ships against — a bundled dataset, a downloadable model file) or `data class`es
 * (a unit named by a server-provided manifest, constructed at runtime); the engine never enumerates
 * them and never branches on which one it holds, so adding a resource is writing one implementation
 * of this interface and nothing else.
 *
 * [id] is the identity that crosses boundaries a typed reference cannot: an Android `Intent` extra,
 * an iOS background-session identifier, a persisted stall-count key. It must be stable across app
 * versions (it is written to disk and read back) and unique within the host's catalogue.
 *
 * Any string is accepted as an [id]; one that is not a plain file name (it contains `/`, NUL or an
 * unpaired surrogate, or a
 * name derived from it would pass 255 bytes) is stored under a hashed name by the shipped storages,
 * so it never collides with another unit or leaves `tmp/`. Prefer `[A-Za-z0-9._-]`, and keep ids
 * unique ignoring case (and, on iOS, Unicode normalization): an iOS volume may treat `Model` and
 * `model` as one file. A [relativePath] that cannot be used safely is rejected with
 * [InvalidDownloadUnitException].
 */
public interface DownloadUnit {

    /**
     * Stable, unique identity — survives serialization to an Intent extra / session id / storage key.
     * Unique ignoring case; see the type's documentation for ids that are not plain file names.
     */
    public val id: String

    /** Opaque path handed to the host's URL resolver. This library never parses or composes it. */
    public val apiPath: String

    /**
     * Where the committed resource lives, relative to the platform's resource base directory, used
     * as written once `.`, `..` and empty segments are resolved lexically: `files/x/../a.bin` is
     * `files/a.bin` whether or not `x` exists, and `..` never climbs out through a symbolic link.
     *
     * The shipped storages reject, with [InvalidDownloadUnitException], a path that is empty or
     * normalizes to nothing, contains NUL, leaves the base directory (`../x`), or names something
     * the storage manages itself (`tmp`, `tmp/expect`, `tmp/expect-staged`, `tmp/kmptoolkit-internal`,
     * `tmp/staging-…`, or this unit's own temp files). Any other path under `tmp/` works but is
     * reserved for the storage and logs a warning.
     */
    public val relativePath: String

    /**
     * Extension of the in-progress temp file. Carries no meaning of its own, but it is part of the
     * temp file's name (`tmp/<id>.<ext>`), so every instance of one id must return the same value:
     * instances that differ see different temp files, while the expected-hash record is shared by id.
     * An extension that is not one plain name (it contains `/` or NUL, or is long) is replaced by `tmp`
     * in the hashed name the shipped storages then use.
     */
    public val tempExtension: String get() = "tmp"

    /**
     * How the downloaded bytes are finalized and checked. Drives [isDirectoryResource] and the
     * post-commit integrity check; see [ResourceFormat].
     */
    public val format: ResourceFormat get() = ResourceFormat.Opaque

    /**
     * The SHA-256 every download of this unit must have, or null for no constant expectation.
     * When set, [DownloaderStorage.commitResource] hashes the complete temp file before anything
     * reaches the final path, and a mismatch is downloaded again rather than committed.
     *
     * The one check that catches what completeness cannot: bytes damaged in transit or on a CDN,
     * and a file stitched from two versions when the remote resource changed between an interrupted
     * transfer and its `Range` resume. Hashing reads the whole file once, about a second for a few
     * hundred megabytes on a current phone, and only at commit.
     *
     * Do NOT set this for an object the backend replaces in place: its hash changes with every
     * replacement, so a constant is wrong as soon as the object changes. Take the hash from the
     * backend's response instead and hand it to [DownloaderStorage.beginTempFile] — see
     * [io.github.jamal_wia.kmptoolkit.downloader.spi.ResolvedDownload.expectedSha256]. Both may
     * apply to one transfer; the bytes must then match each of them.
     *
     * Read by the engine before it starts a transfer, and again by the storage at
     * [DownloaderStorage.beginTempFile] and at commit. A getter that throws (`get() =
     * Sha256.parse("…")` with a malformed constant) fails the unit with [DownloadError.Unknown]
     * before any byte is fetched, instead of after the whole download. A stored property
     * (`val sha256 = Sha256.parse("…")`) throws earlier still, when the unit is constructed.
     */
    public val sha256: Sha256? get() = null

    /**
     * True when committing means "extract an archive into a directory" rather than "move one file
     * into place". Derived from [format] — a unit states its shape once, and storage asks this.
     */
    public val isDirectoryResource: Boolean get() = format is ResourceFormat.ZipArchive

    /**
     * Which group this unit's notification and aggregate progress are filed under. A group may span
     * several units (a model plus a companion file it needs), and a unit belongs to exactly one.
     */
    public val group: ResourceGroup
}

/**
 * What the downloaded bytes ARE, as far as finalizing them requires knowing — a closed set of
 * container shapes this library can genuinely handle, not a list of the host's resources.
 *
 * The distinction matters because "is this download usable?" is not always "does the file exist?":
 * a truncated database or a half-written archive exists and is worthless, and discovering that at
 * read time (a crash deep in the reader) instead of at commit time is what this exists to prevent.
 */
public sealed interface ResourceFormat {

    /** Bytes the library moves into place without inspecting. Existence is the only check possible. */
    public data object Opaque : ResourceFormat

    /**
     * A ZIP archive: committing extracts it into [DownloadUnit.relativePath] as a directory (with
     * zip-slip protection) and deletes the archive. [availabilityMarker] is a regular file the extraction
     * is known to produce (a directory of that name, or another capitalization on a case-insensitive volume, does not count) — its presence is what proves a directory resource is complete, since an
     * interrupted extraction also leaves a directory behind. Every entry is extracted, or the commit fails
     * with [ResourceIntegrityException]; an entry that would land outside the directory fails the commit, and so does an
     * archive the file system cannot hold as delivered (a name that is a file and a directory, a name or path that is too
     * long, two entries it stores as one).
     *
     * The archive must end with its end of central directory record (a comment cut short is tolerated; an archive cut
     * anywhere before the record, its central directory included, fails), and entry names are read as UTF-8 whether
     * or not the archive sets the UTF-8 flag: a name that is not valid UTF-8 fails the commit.
     */
    public data class ZipArchive(public val availabilityMarker: String) : ResourceFormat

    /**
     * A SQLite database, verified before it counts as committed: a truncated or corrupt download
     * fails at commit rather than surfacing later as an unreadable file deep inside a reader.
     *
     * The file must be at least 100 bytes long and start with the 16-byte SQLite header (a file without it, an
     * empty or cut body, fails the commit), and it is always opened and its schema read, which alone rejects bytes
     * that are not a database (opening by itself does not: SQLite reads the header lazily). A unit whose
     * schema states its own expected size can ask for more: give [rowCountTable] and
     * [declaredRowCountMetaKey] and the row count of that table must equal the value stored under
     * that key in a `meta(key, value)` table. Which table and which key those are is the host's
     * domain knowledge, which is why they are values here rather than anything the library assumes.
     *
     * [rowCountTable] is a table or view name, used as a name (any characters: a keyword or a name with spaces
     * works); a value that names no table is used as SQL text, as in 3.0.0 — kept for compatibility, do not rely
     * on it. [declaredRowCountMetaKey] is a value bound as a parameter, never escaped by the host.
     */
    public data class SqliteDatabase(
        public val rowCountTable: String? = null,
        public val declaredRowCountMetaKey: String? = null,
    ) : ResourceFormat
}

/**
 * The file whose presence proves this unit's archive finished extracting.
 *
 * Only meaningful for a [ResourceFormat.ZipArchive]; anything else has no marker and is proved
 * present by simply existing. A storage implementation asks the unit rather than carrying a
 * hardcoded file name, which is what lets a host add a second archive-shaped resource without
 * touching either platform's storage.
 */
public fun DownloadUnit.archiveMarker(): String =
    (format as? ResourceFormat.ZipArchive)?.availabilityMarker.orEmpty()

/**
 * A user-facing bundle of [DownloadUnit]s treated as one resource: it is what a notification is
 * titled after, what aggregate progress is scaled across, and what a consumer names when it says
 * "make this available".
 *
 * An interface rather than an enum because the set of groups belongs to the host — the library only
 * needs to compare them ([key]) and enumerate their members ([units]).
 */
public interface ResourceGroup {

    /** Stable identity, used as a notification tag and an action payload. Unique within the host. */
    public val key: String

    /**
     * Every unit that must be present for this group to count as available, in download order.
     * May be empty for a group that exists only to title notifications for units addressed
     * individually (a per-item catalogue, where "download the whole group" is meaningless).
     */
    public val units: List<DownloadUnit>
}
