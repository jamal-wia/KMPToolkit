package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import io.github.jamal_wia.kmptoolkit.uploader.spi.KeepResult

/** Whether the KEEP inserted its record (the answer `insertKeep` gave as a Boolean before it returned a [KeepResult]). */
internal val KeepResult.inserted: Boolean
    get() = this is KeepResult.Inserted
