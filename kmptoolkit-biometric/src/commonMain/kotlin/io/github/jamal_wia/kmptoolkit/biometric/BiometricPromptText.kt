package io.github.jamal_wia.kmptoolkit.biometric

/**
 * The words the operating system will put in front of your user.
 *
 * **This library ships no wording, and this type is how it stays that way.** The OS renders these
 * strings verbatim, in a sheet your app cannot restyle, at the moment your user is deciding whether
 * to trust you — that is the last place a library's guess at your tone, your product name, or your
 * user's language belongs. So none of the three has a default: a consumer cannot accidentally ship
 * a string this library invented, because there is no such string to fall back to.
 *
 * Pass strings you have already localized. This type does no formatting, no truncation, and no
 * language selection.
 *
 * ```kotlin
 * gate.authenticate(
 *     BiometricPromptText(
 *         title = strings.unlockTitle,
 *         subtitle = strings.unlockSubtitle,
 *         cancelLabel = strings.cancel,
 *     ),
 * )
 * ```
 *
 * @param title the headline of the prompt. **Android only** — iOS's system prompt has no title
 *   slot of its own and shows the app name there instead; the value is ignored on that platform.
 * @param subtitle the sentence explaining *why* you are asking. It reaches the user on both
 *   platforms — as the prompt's subtitle on Android, as `LAContext.localizedReason` on iOS, which
 *   is the only string iOS renders alongside its own. Write it so that it stands alone, because on
 *   iOS it does.
 * @param cancelLabel the label of the button that dismisses the prompt without authenticating.
 *   **Ignored when this gate's [BiometricGateConfig.policy] is
 *   [BiometricPolicy.BIOMETRIC_OR_DEVICE_CREDENTIAL] on Android**: that prompt owns its own
 *   negative button, and `androidx.biometric` rejects a prompt that sets both. It is still
 *   required, because the same value is used on iOS and by a biometric-only gate on Android, and
 *   because a parameter that is sometimes ignored is a smaller trap than one that is sometimes
 *   missing.
 *
 * @throws IllegalArgumentException if any of the three is blank. A blank string is not a
 *   localization decision this type can make for you — the OS would render an empty or a
 *   platform-default label, differently on each platform — so it is refused at construction, where
 *   the stack trace still points at your code.
 */
public data class BiometricPromptText(
    public val title: String,
    public val subtitle: String,
    public val cancelLabel: String,
) {
    init {
        require(title.isNotBlank()) { "title must not be blank" }
        require(subtitle.isNotBlank()) { "subtitle must not be blank" }
        require(cancelLabel.isNotBlank()) { "cancelLabel must not be blank" }
    }
}
