package io.github.jamal_wia.kmptoolkit.files

/**
 * How [FileAccess.move] moves.
 *
 * @property existing What to do when something is already at the destination.
 * @property acrossVolumes Whether a regular file may be copied when source and destination are on different volumes.
 * @property createParentDirectories Creates the parent of the destination first.
 * @since 5.0.0
 */
public data class MoveOptions(
    public val existing: ExistingFile = ExistingFile.Replace,
    public val acrossVolumes: Boolean = true,
    public val createParentDirectories: Boolean = false,
)
