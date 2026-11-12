package io.github.jamal_wia.kmptoolkit.files

import java.io.File
import java.text.Normalizer

internal actual fun normalizeNfc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)

// path-hygiene-ignore: PH1 this is the host detection itself: the JVM's own separator character decides the path syntax
internal actual val hostUsesWindowsPaths: Boolean = File.separatorChar == '\\'
