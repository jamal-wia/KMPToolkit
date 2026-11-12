package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.files.ExistingFile
import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.MoveOptions
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import io.github.jamal_wia.kmptoolkit.files.isSuccess

private val files: FileAccess by lazy { createFileAccess() }

internal actual fun renameNoReplace(from: String, to: String): Boolean =
    files.move(from, to, MoveOptions(existing = ExistingFile.Fail, acrossVolumes = false)).isSuccess
