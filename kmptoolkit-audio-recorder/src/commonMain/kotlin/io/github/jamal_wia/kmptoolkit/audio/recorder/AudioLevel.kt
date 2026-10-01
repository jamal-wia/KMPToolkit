package io.github.jamal_wia.kmptoolkit.audio.recorder

/**
 * Maps a loudness in dBFS onto the `0f..1f` scale [AudioRecorder.level] publishes: [floorDbfs] and
 * anything quieter is `0f`, full scale (0 dBFS) and anything louder is `1f`, and the range between
 * is linear in decibels.
 *
 * Linear in decibels rather than in amplitude because that is how loudness is perceived: on an
 * amplitude scale ordinary speech would sit in the bottom few percent of the meter and a quiet voice
 * would not move it at all.
 *
 * Common code, so both platforms produce the same bar for the same voice and the mapping is tested
 * once. `NaN` is treated as silence — a meter that cannot read a value has nothing to show.
 *
 * @param dbfs the measured peak, `<= 0`; [Float.NEGATIVE_INFINITY] for digital silence.
 * @param floorDbfs the value that maps to `0f`; negative, as [AudioRecorderConfig.levelFloorDbfs]
 *   guarantees.
 */
internal fun normalizedLevel(dbfs: Float, floorDbfs: Float): Float = when {
    dbfs.isNaN() || dbfs <= floorDbfs -> 0f
    dbfs >= 0f -> 1f
    else -> (dbfs - floorDbfs) / -floorDbfs
}
