package io.github.jamal_wia.kmptoolkit.settings

/**
 * The outcome of changing a setting: either it was persisted, or it was not and [Failure] says
 * why.
 *
 * Not generic, unlike `StorageResult`, because no operation on [AppSettings] produces a value —
 * the new value is what the caller just passed in, and reading is done through the flows.
 *
 * Not `kotlin.Result` either: a [SettingsError] is not a `Throwable`, and none of its cases are
 * exceptional in the language's sense — a write that could not reach the store is an outcome a
 * settings screen handles, not something to throw at it.
 */
public sealed interface SettingsResult {

    /** The setting was changed and persisted. */
    public data object Success : SettingsResult

    /** Nothing changed; [error] says why. */
    public data class Failure(public val error: SettingsError) : SettingsResult
}

/** The error on failure, `null` on success. */
public fun SettingsResult.errorOrNull(): SettingsError? = when (this) {
    is SettingsResult.Success -> null
    is SettingsResult.Failure -> error
}

/** Whether this is a [SettingsResult.Success]. */
public val SettingsResult.isSuccess: Boolean
    get() = this is SettingsResult.Success
