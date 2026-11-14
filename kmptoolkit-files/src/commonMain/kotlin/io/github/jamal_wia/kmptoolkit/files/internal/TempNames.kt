package io.github.jamal_wia.kmptoolkit.files.internal

import io.github.jamal_wia.kmptoolkit.files.MAX_TEMP_DIGITS
import io.github.jamal_wia.kmptoolkit.files.TEMP_SUFFIX
import kotlin.random.Random

/** `<prefix><random non-negative Long in decimal>.tmp`; at most [MAX_TEMP_DIGITS] digits. */
internal fun newTempName(prefix: String): String = prefix + Random.nextLong(0L, Long.MAX_VALUE) + TEMP_SUFFIX
