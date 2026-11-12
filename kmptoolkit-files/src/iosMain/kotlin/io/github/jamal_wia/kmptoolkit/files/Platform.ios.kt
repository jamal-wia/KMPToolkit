package io.github.jamal_wia.kmptoolkit.files

import platform.Foundation.NSString
import platform.Foundation.precomposedStringWithCanonicalMapping

@Suppress("CAST_NEVER_SUCCEEDS") // Kotlin/Native bridges String to NSString
internal actual fun normalizeNfc(text: String): String =
    (text as NSString).precomposedStringWithCanonicalMapping

internal actual val hostUsesWindowsPaths: Boolean = false
