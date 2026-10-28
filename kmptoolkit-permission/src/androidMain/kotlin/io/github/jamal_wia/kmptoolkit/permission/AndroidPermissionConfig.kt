package io.github.jamal_wia.kmptoolkit.permission

/**
 * Where the Android controller's bookkeeping lives inside the `KeyValueStorage` you hand its factory.
 *
 * The controller has to remember two facts per runtime permission — *have we ever shown the system
 * dialog for it?* and *has the user ever refused it there?* — because Android alone cannot tell
 * "never asked", "asked and dismissed" and "asked and permanently refused" apart: all three answer
 * `shouldShowRequestPermissionRationale() == false` with the permission not granted. Without the
 * first bit, a first-run app sends users to system settings for a permission it has not asked for
 * even once; without the second, a user who merely backed out of the dialog is sent there too.
 *
 * Both facts are written under keys derived from [keyPrefix] and the permission's [Permission.id],
 * and nothing about the key is hardcoded to this library's name alone. A library that pinned its own
 * key namespace would collide the moment two libraries built on it — or two features of one app —
 * shared a store; see `docs/01-architecture.md`.
 *
 * ```kotlin
 * // Default: keys namespaced by the consuming app's own identifier.
 * val controller = createPermissionController(context, storage)
 *
 * // Two independent flows in one app, sharing a store but not their bookkeeping.
 * val onboarding = createPermissionController(
 *     context, storage, config = AndroidPermissionConfig("com.example.onboarding"),
 * )
 * ```
 *
 * Android-only, because only Android needs it: every iOS authorization reports "not determined" as a
 * value of its own, so there is nothing for the iOS controller to remember.
 *
 * @param keyPrefix the namespace every key this module writes begins with. `null` — the default —
 *   resolves at construction time to the consuming app's own identifier (`Context.getPackageName()`)
 *   followed by a fixed suffix, so two apps using this library never read each other's flags and
 *   neither of them has to name anything. Must not be blank when supplied. The resolved layout is
 *   the same as 2.x's `PermissionConfig`, so 2.x flags are read unchanged.
 */
public data class AndroidPermissionConfig(public val keyPrefix: String? = null) {
    init {
        // Validated here rather than reported as a runtime failure: the prefix is a literal a
        // developer writes, so a wrong one is a bug to fix at the call site.
        require(keyPrefix == null || keyPrefix.isNotBlank()) {
            "keyPrefix must be null or a non-blank identifier, was '$keyPrefix'"
        }
    }
}

/**
 * The prefix actually used, given the platform-supplied [applicationId] that stands in for a `null`
 * [AndroidPermissionConfig.keyPrefix].
 *
 * Internal because the exact key layout is an implementation detail: promising it publicly would
 * freeze the shape of every consumer's stored data. It is nonetheless documented in
 * `docs/kmptoolkit-permission/05-platform-notes.md`, since a developer inspecting their own
 * preferences file has to be able to recognize these entries.
 */
internal fun AndroidPermissionConfig.resolveKeyPrefix(applicationId: String): String =
    keyPrefix ?: "$applicationId.kmptoolkit.permission"

/**
 * The key holding the "we have shown the system dialog for this permission at least once" flag.
 *
 * Keyed by [Permission.id] rather than by the platform permission string so that the flag survives an
 * OS-level change of which string a permission maps to — Android has already moved this target once,
 * when notifications became a runtime permission in API 33. The ids of the 2.x entries are their 2.x
 * enum names, so this is the 2.x key exactly.
 */
internal fun askedKey(prefix: String, permission: Permission): String = "$prefix.asked.${permission.id}"

/**
 * The key holding the "the user has refused this permission through the dialog at least once" flag —
 * on Android the same as "the system has asked us to show a rationale for it at least once", which is
 * what separates a refusal from a dialog that was merely dismissed.
 */
internal fun rationaleSeenKey(prefix: String, permission: Permission): String =
    "$prefix.rationale.${permission.id}"
