package io.github.jamal_wia.kmptoolkit.files.spi

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.foldCase

/**
 * The fixed folding rule behind `PathKey.of(path, CaseFolding.Always)`, without any path normalization: NFC,
 * lowercase, uppercase, lowercase, NFC, locale-independent and identical on every host. It folds one bare name, so a
 * case-insensitive file system (such as the one `kmptoolkit-files-testing` simulates) treats names the same way on
 * every host. Internal API: not part of the contract, may change in any release.
 *
 * @since 5.0.0
 */
@ToolkitInternalApi
public fun foldCaseForKey(text: String): String = foldCase(text)
