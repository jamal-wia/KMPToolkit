package io.github.jamal_wia.kmptoolkit.biometric

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The contract that keeps this library's words out of a consumer's product: all three strings are
 * required, none of them has a default, and none of them may be blank.
 *
 * The blank cases matter more than they look. A consumer wiring up a localization lookup that
 * misses a key gets an empty string back, and an empty string sails through a `String` parameter
 * into the OS prompt — where it renders as a nameless sheet or, on Android, as a platform default
 * in the *device's* language rather than the app's. Failing at construction puts the stack trace in
 * the caller's code instead.
 */
class BiometricPromptTextTest {

    private fun text(
        title: String = "Unlock",
        subtitle: String = "Confirm it is you",
        cancelLabel: String = "Cancel",
    ): BiometricPromptText = BiometricPromptText(title, subtitle, cancelLabel)

    @Test
    fun `every string is kept exactly as it was given`() {
        val prompt: BiometricPromptText = text(
            title = "فتح القفل",
            subtitle = "أكد هويتك",
            cancelLabel = "إلغاء",
        )

        assertEquals("فتح القفل", prompt.title)
        assertEquals("أكد هويتك", prompt.subtitle)
        assertEquals("إلغاء", prompt.cancelLabel)
    }

    @Test
    fun `an empty title is refused`() {
        assertFailsWith<IllegalArgumentException> { text(title = "") }
    }

    @Test
    fun `an empty subtitle is refused`() {
        assertFailsWith<IllegalArgumentException> { text(subtitle = "") }
    }

    @Test
    fun `an empty cancel label is refused`() {
        assertFailsWith<IllegalArgumentException> { text(cancelLabel = "") }
    }

    @Test
    fun `a whitespace-only string is refused just like an empty one`() {
        // A missing localization often comes back as " " or "\n" rather than "" — both render as
        // nothing, so both are refused.
        assertFailsWith<IllegalArgumentException> { text(title = "   ") }
        assertFailsWith<IllegalArgumentException> { text(subtitle = "\t") }
        assertFailsWith<IllegalArgumentException> { text(cancelLabel = "\n") }
    }

    @Test
    fun `the failure message names the offending parameter`() {
        // Three identical "must not be blank" messages would make the failure useless in a build
        // log, which is where a consumer will read it.
        val failure: IllegalArgumentException =
            assertFailsWith { text(subtitle = "") }

        assertTrue(
            failure.message.orEmpty().contains("subtitle"),
            "message should name the parameter but was: ${failure.message}",
        )
    }

    @Test
    fun `a single non-blank character is enough`() {
        // The rule is "not blank", not "long enough" — this type has no opinion about copy, only
        // about whether there is any.
        val prompt: BiometricPromptText = text(title = "X", subtitle = "Y", cancelLabel = "Z")

        assertEquals("X", prompt.title)
    }

    @Test
    fun `padding around real text is preserved rather than trimmed`() {
        // Trimming would be this library editing the consumer's copy. It refuses blanks; it does
        // not tidy.
        assertEquals(" Unlock ", text(title = " Unlock ").title)
    }

    @Test
    fun `two prompts with the same words are equal`() {
        // Value semantics let a test assert prompt copy with assertEquals rather than field by
        // field; ScriptedBiometricGate's recording depends on it.
        assertEquals(text(), text())
    }

    @Test
    fun `copy re-validates rather than trusting the original`() {
        assertFailsWith<IllegalArgumentException> { text().copy(cancelLabel = "") }
    }
}
