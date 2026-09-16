package io.github.jamal_wia.kmptoolkit.permission

/**
 * Where this module's bookkeeping lives inside the `KeyValueStorage` you hand the Android factory.
 *
 * The Android handler has to remember two facts per permission — *have we ever shown the system
 * dialog for it?* and *has the user ever refused it there?* — because Android alone cannot tell
 * "never asked", "asked and dismissed" and "asked and permanently refused" apart: all three answer
 * `shouldShowRequestPermissionRationale() == false` with the permission not granted. Without the
 * first bit, a first-run app sends users to system settings for a permission it has not asked for
 * even once; without the second, a user who merely backed out of the dialog is sent there too.
 *
 * Both facts are written under keys derived from [keyPrefix], and nothing about the key is
 * hardcoded to this library's name alone. A library that pinned its own key namespace would
 * collide the moment two libraries built on it — or two features of one app — shared a store; see
 * `docs/01-architecture.md`.
 *
 * ```kotlin
 * // Default: keys namespaced by the consuming app's own identifier.
 * val handler = createPermissionHandler(context, host, storage)
 *
 * // Two independent flows in one app, sharing a store but not their bookkeeping.
 * val onboarding = createPermissionHandler(
 *     context, host, storage, config = PermissionConfig("com.example.onboarding"),
 * )
 * ```
 *
 * Only the Android handler stores anything. iOS is not configured by this type and its factory
 * does not take one: `UNAuthorizationStatus`, `AVAudioSession.recordPermission` and
 * `AVCaptureDevice.authorizationStatus` each report "not determined" as a distinct value, so there
 * is nothing left for this library to remember.
 *
 * @param keyPrefix the namespace every key this module writes begins with. `null` — the default —
 *   resolves at construction time to the consuming app's own identifier (`Context.getPackageName()`)
 *   followed by a fixed suffix, so two apps using this library never read each other's flags and
 *   neither of them has to name anything. Must not be blank when supplied.
 */
public data class PermissionConfig(public val keyPrefix: String? = null) {
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
 * [PermissionConfig.keyPrefix].
 *
 * Internal because the exact key layout is an implementation detail: promising it publicly would
 * freeze the shape of every consumer's stored data. It is nonetheless documented in
 * `docs/kmptoolkit-permission/05-platform-notes.md`, since a developer inspecting their own
 * preferences file has to be able to recognize these entries.
 */
internal fun PermissionConfig.resolveKeyPrefix(applicationId: String): String =
    keyPrefix ?: "$applicationId.kmptoolkit.permission"

/**
 * The key holding the "we have shown the system dialog for this permission at least once" flag.
 *
 * Keyed by [Permission.name] rather than by the platform permission string so that the flag
 * survives an OS-level change of which string a [Permission] maps to — Android has already moved
 * this target once, when notifications became a runtime permission in API 33.
 */
internal fun askedKey(prefix: String, permission: Permission): String =
    "$prefix.asked.${permission.name}"

/**
 * The key holding the "the system has asked us to show a rationale for this permission at least
 * once" flag — which on Android is the same as "the user has refused it at least once through the
 * dialog", and is what separates a refusal from a dialog that was merely dismissed.
 */
internal fun rationaleSeenKey(prefix: String, permission: Permission): String =
    "$prefix.rationale.${permission.name}"
