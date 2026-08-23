package io.github.jamal_wia.kmptoolkit.downloader

/**
 * One downloadable thing — the smallest unit this library fetches, commits and can be asked about.
 * It maps 1:1 to a remote location (via [apiPath], resolved to a real URL by the host's
 * [io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadUrlResolver]) and to one place on disk
 * (via [relativePath]).
 *
 * **The library ships no units.** Which resources exist is the host application's catalogue, not
 * this module's business — the same split [io.github.jamal_wia.kmptoolkit.outbox] draws between
 * owning the queue and not owning any effect. A host declares its own units as `data object`s
 * (fixed assets it ships against — a bundled dataset, a downloadable model file) or `data class`es
 * (a unit named by a server-provided manifest, constructed at runtime); the engine never enumerates
 * them and never branches on which one it holds, so adding a resource is writing one implementation
 * of this interface and nothing else.
 *
 * [id] is the identity that crosses boundaries a typed reference cannot: an Android `Intent` extra,
 * an iOS background-session identifier, a persisted stall-count key. It must be stable across app
 * versions (it is written to disk and read back) and unique within the host's catalogue.
 */
public interface DownloadUnit {

    /** Stable, unique identity — survives serialization to an Intent extra / session id / storage key. */
    public val id: String

    /** Opaque path handed to the host's URL resolver. This library never parses or composes it. */
    public val apiPath: String

    /** Where the committed resource lives, relative to the platform's resource base directory. */
    public val relativePath: String

    /** Extension of the in-progress temp file. Only cosmetic — the temp name is derived from [id]. */
    public val tempExtension: String get() = "tmp"

    /**
     * How the downloaded bytes are finalized and checked. Drives [isDirectoryResource] and the
     * post-commit integrity check; see [ResourceFormat].
     */
    public val format: ResourceFormat get() = ResourceFormat.Opaque

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
     * zip-slip protection) and deletes the archive. [availabilityMarker] is a file the extraction
     * is known to produce — its presence is what proves a directory resource is complete, since an
     * interrupted extraction also leaves a directory behind.
     */
    public data class ZipArchive(val availabilityMarker: String) : ResourceFormat

    /**
     * A SQLite database, verified before it counts as committed: a truncated or corrupt download
     * fails at commit rather than surfacing later as an unreadable file deep inside a reader.
     *
     * The file is always opened, which alone rejects bytes that are not a database. A unit whose
     * schema states its own expected size can ask for more: give [rowCountTable] and
     * [declaredRowCountMetaKey] and the row count of that table must equal the value stored under
     * that key in a `meta(key, value)` table. Which table and which key those are is the host's
     * domain knowledge, which is why they are values here rather than anything the library assumes.
     */
    public data class SqliteDatabase(
        val rowCountTable: String? = null,
        val declaredRowCountMetaKey: String? = null,
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
