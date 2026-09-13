package io.github.jamal_wia.kmptoolkit.storage

/*
 * Typed access over the string store.
 *
 * The store itself stays string-valued on every platform — that is what keeps its contract identical
 * across SharedPreferences, NSUserDefaults and a desktop file, and what lets an encrypted store share
 * the same interface. These extensions only fix one encoding for the three types apps store most, so
 * that every caller writes them the same way and reads back what another caller wrote:
 *
 * - `Int` and `Long` as their decimal string (`"42"`, `"-7"`);
 * - `Boolean` as `"true"` or `"false"`, exactly.
 *
 * A stored value that does not parse as the requested type is reported as
 * [StorageError.OperationFailed] for [StorageOperation.GET], with the parse exception as its cause,
 * rather than silently read as absent: it means two callers disagree about a key's type, which is a
 * bug worth seeing. The `...Or` variants collapse that, like an absent key or an unreadable store,
 * into the default you pass — use them where a sensible default exists and a wrong value is not worth
 * handling separately.
 */

/** The `Int` stored under [key], `Success(null)` when absent. A non-integer value is a failure. */
public fun KeyValueStorage.getInt(key: String): StorageResult<Int?> = getParsed(key, String::toInt)

/** The `Long` stored under [key], `Success(null)` when absent. A non-integer value is a failure. */
public fun KeyValueStorage.getLong(key: String): StorageResult<Long?> = getParsed(key, String::toLong)

/**
 * The `Boolean` stored under [key], `Success(null)` when absent. Anything other than exactly `"true"`
 * or `"false"` is a failure.
 */
public fun KeyValueStorage.getBoolean(key: String): StorageResult<Boolean?> =
    getParsed(key) { value ->
        value.toBooleanStrictOrNull() ?: throw IllegalArgumentException("Not a boolean: '$value'")
    }

/** Stores [value] under [key] as its decimal string. */
public fun KeyValueStorage.putInt(key: String, value: Int): StorageResult<Unit> = put(key, value.toString())

/** Stores [value] under [key] as its decimal string. */
public fun KeyValueStorage.putLong(key: String, value: Long): StorageResult<Unit> = put(key, value.toString())

/** Stores [value] under [key] as `"true"` or `"false"`. */
public fun KeyValueStorage.putBoolean(key: String, value: Boolean): StorageResult<Unit> =
    put(key, value.toString())

/** The string stored under [key], or [default] when it is absent or the store could not be read. */
public fun KeyValueStorage.getStringOr(key: String, default: String): String = getStringOrNull(key) ?: default

/** The `Int` stored under [key], or [default] when it is absent, unreadable, or not an integer. */
public fun KeyValueStorage.getIntOr(key: String, default: Int): Int = getInt(key).getOrNull() ?: default

/** The `Long` stored under [key], or [default] when it is absent, unreadable, or not an integer. */
public fun KeyValueStorage.getLongOr(key: String, default: Long): Long = getLong(key).getOrNull() ?: default

/** The `Boolean` stored under [key], or [default] when it is absent, unreadable, or not a boolean. */
public fun KeyValueStorage.getBooleanOr(key: String, default: Boolean): Boolean =
    getBoolean(key).getOrNull() ?: default

private inline fun <T : Any> KeyValueStorage.getParsed(
    key: String,
    parse: (String) -> T,
): StorageResult<T?> = when (val stored: StorageResult<String?> = get(key)) {
    is StorageResult.Failure -> stored
    is StorageResult.Success -> {
        val raw: String? = stored.value
        if (raw == null) {
            StorageResult.Success(null)
        } else {
            try {
                StorageResult.Success(parse(raw))
            } catch (error: IllegalArgumentException) {
                // NumberFormatException is an IllegalArgumentException on every Kotlin target.
                StorageResult.Failure(StorageError.OperationFailed(StorageOperation.GET, key = key, cause = error))
            }
        }
    }
}
