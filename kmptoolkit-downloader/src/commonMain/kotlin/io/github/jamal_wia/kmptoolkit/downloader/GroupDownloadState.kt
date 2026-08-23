package io.github.jamal_wia.kmptoolkit.downloader

/**
 * State of one [ResourceGroup]'s download, observed via `downloadState(group)`. No `group` field:
 * the group is the identity of the flow being collected, not a value worth repeating in every
 * emission — and its absence is what lets two groups download in parallel without their states
 * overwriting each other.
 *
 * Progress is scaled across every unit in the group that actually needs network work, so a
 * two-unit group where one unit is already on disk still reports one continuous 0..1.
 */
public sealed class GroupDownloadState {
    public data object Idle : GroupDownloadState()
    public data class Downloading(val progress: Float) : GroupDownloadState()
    public data object Completed : GroupDownloadState()
    public data class Error(val error: DownloadError) : GroupDownloadState()
}

/**
 * State for a download addressed by a single [DownloadUnit] rather than a whole [ResourceGroup] —
 * for catalogues whose full set isn't fixed at compile time, where two rows in a list can be
 * downloading independently at once and each needs its own progress and cancel button.
 *
 * Like the group state, no `unit` field: the caller already knows which unit it asked for.
 *
 * The first value a collector sees is seeded from disk, so [Available] and [Idle] answer the
 * question a single Idle state cannot: "is this already here from an earlier session, or has nobody
 * ever asked?" — without the caller consulting storage separately.
 */
public sealed class UnitDownloadState {
    /** Not on disk, and nothing has asked to download it this process. */
    public data object Idle : UnitDownloadState()

    /**
     * On disk — from an earlier session, or from a download that finished before this collector
     * subscribed and was since re-confirmed. Nothing was transferred by the current request.
     */
    public data object Available : UnitDownloadState()

    public data class Downloading(val progress: Float) : UnitDownloadState()

    /**
     * Finished downloading in THIS process — the transition a caller may want to celebrate or
     * react to, as opposed to [Available], which is the resting "it is simply here" state.
     */
    public data object Completed : UnitDownloadState()

    public data class Error(val error: DownloadError) : UnitDownloadState()
}
