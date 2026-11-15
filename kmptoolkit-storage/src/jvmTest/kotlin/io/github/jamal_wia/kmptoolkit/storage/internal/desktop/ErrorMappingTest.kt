package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.DesktopKeyStore
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.UnsupportedReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * T5: every row of the error-mapping tables of plan 4.5 (macOS), 4.6 (Windows) and 4.7 (Linux) as a pure function of
 * raw codes. Group `sto-desktop`, phase B (6d). The real binding-load path (no `secret_password_search_sync`) is in
 * `DesktopSecureStorageT5SelectorTest`'s sibling cases through the selector, not here.
 */
class ErrorMappingTest {

    private val op: StorageOperation = StorageOperation.GET
    private val key = "k"

    private fun failed(verdict: Verdict<*>): StorageError = assertIs<Verdict.Failed>(verdict).error

    // ---- 4.5 macOS ----------------------------------------------------------------------------------------------

    @Test
    fun `macOS copy - an interactive not-found is definitive, a non-interactive one is unconfirmed (Unavailable for a file with entries) until the Mac verification has run`() {
        assertEquals(Verdict.NotFound, ErrorMapping.macCopy(-25300, interactive = true, op, key))
        val unconfirmed: Verdict.NotFoundUnconfirmed = assertIs(ErrorMapping.macCopy(-25300, interactive = false, op, key))
        assertIs<StorageError.Unavailable>(unconfirmed.error)
        assertFalse(ErrorMapping.MAC_NON_INTERACTIVE_NOT_FOUND_IS_DEFINITIVE, "the conservative rule is the shipped one")
        // The other possible outcome of the verification: UIFail never reports -25300 for a locked or foreign item.
        assertEquals(
            Verdict.NotFound,
            ErrorMapping.macCopy(-25300, interactive = false, op, key, nonInteractiveNotFoundIsDefinitive = true),
        )
    }

    @Test
    fun `macOS status rows`() {
        val table: Map<Int, (StorageError) -> Boolean> = mapOf(
            -25299 to { e -> e is StorageError.Unavailable },
            -25308 to { e -> e is StorageError.Unavailable },
            -25291 to { e -> e is StorageError.Unavailable },
            -128 to { e -> e is StorageError.AccessDenied && e.platformCode == -128 },
            -25293 to { e -> e is StorageError.AccessDenied && e.platformCode == -25293 },
            -25307 to { e -> e is StorageError.Unsupported && e.reason == UnsupportedReason.NO_KEY_STORE },
            -25294 to { e -> e is StorageError.Unsupported && e.reason == UnsupportedReason.NO_KEY_STORE },
            -34018 to { e -> e is StorageError.Unsupported && e.reason == UnsupportedReason.MISSING_ENTITLEMENT },
            -1 to { e -> e is StorageError.OperationFailed && e.platformCode == -1 && e.operation == op && e.key == key },
            -50 to { e -> e is StorageError.OperationFailed && e.platformCode == -50 },
        )
        for ((status: Int, check: (StorageError) -> Boolean) in table) {
            assertTrue(check(failed(ErrorMapping.macCopy(status, interactive = true, op, key))), "copy status $status")
            assertTrue(check(failed(ErrorMapping.macAdd(status, op, key))), "add status $status")
            assertTrue(check(failed(ErrorMapping.macDelete(status, op, key))), "delete status $status")
        }
    }

    @Test
    fun `macOS add and delete - success is Ok, delete of nothing is fine, a refused delete is AccessDenied`() {
        assertIs<Verdict.Ok<Unit>>(ErrorMapping.macAdd(0, op, key))
        assertIs<Verdict.Ok<Unit>>(ErrorMapping.macDelete(0, op, key))
        assertIs<Verdict.Ok<Unit>>(ErrorMapping.macDelete(-25300, op, key), "nothing to delete is fine")
        assertIs<StorageError.AccessDenied>(failed(ErrorMapping.macDelete(-25293, op, key)))
        assertIs<StorageError.AccessDenied>(failed(ErrorMapping.macDelete(-128, op, key)))
        // -25300 from an add is not a table row: the add failed.
        assertIs<StorageError.OperationFailed>(failed(ErrorMapping.macAdd(-25300, op, key)))
    }

    @Test
    fun `macOS probe rows`() {
        assertEquals(DesktopKeyStore.MACOS_FILE_KEYCHAIN, assertIs<Verdict.Ok<DesktopKeyStore>>(ErrorMapping.macProbe(0, true)).value)
        assertEquals(DesktopKeyStore.MACOS_FILE_KEYCHAIN, assertIs<Verdict.Ok<DesktopKeyStore>>(ErrorMapping.macProbe(-25300, false)).value)
        assertEquals(
            UnsupportedReason.NO_KEY_STORE,
            assertIs<StorageError.Unsupported>(failed(ErrorMapping.macProbe(-25307, true))).reason,
        )
        assertEquals(DesktopKeyStore.MACOS_FILE_KEYCHAIN, assertIs<Verdict.Ok<DesktopKeyStore>>(ErrorMapping.macProbe(-25308, allowUserInteraction = true)).value)
        assertIs<StorageError.Unavailable>(failed(ErrorMapping.macProbe(-25308, allowUserInteraction = false)))
    }

    // ---- 4.6 Windows --------------------------------------------------------------------------------------------

    @Test
    fun `Windows unprotect failure - only a working round trip makes it the store's own Undecryptable`() {
        // 0x8009000B NTE_BAD_KEY_STATE and 13 ERROR_INVALID_DATA are both "the blob is not ours" when DPAPI works.
        for (code: Int in listOf(0x8009000B.toInt(), 13, 5)) {
            val error: StorageError = ErrorMapping.windowsUnprotectFailure(code, ErrorMapping.RoundTrip.Worked)
            assertEquals(null, assertIs<StorageError.Undecryptable>(error, "unprotect status $code").key)
        }
        assertEquals(
            UnsupportedReason.NO_USER_SESSION,
            assertIs<StorageError.Unsupported>(ErrorMapping.windowsUnprotectFailure(13, ErrorMapping.RoundTrip.Failed(1312))).reason,
        )
        assertIs<StorageError.Unavailable>(ErrorMapping.windowsUnprotectFailure(13, ErrorMapping.RoundTrip.Failed(5)))
    }

    @Test
    fun `Windows protect failure and probe rows`() {
        assertEquals(UnsupportedReason.NO_USER_SESSION, assertIs<StorageError.Unsupported>(ErrorMapping.windowsProtectFailure(1312, op, null)).reason)
        val other: StorageError.OperationFailed = assertIs(ErrorMapping.windowsProtectFailure(5, StorageOperation.PUT, "k"))
        assertEquals(5, other.platformCode)
        assertEquals(StorageOperation.PUT, other.operation)
        assertEquals(DesktopKeyStore.WINDOWS_DPAPI, assertIs<Verdict.Ok<DesktopKeyStore>>(ErrorMapping.windowsProbe(ErrorMapping.RoundTrip.Worked)).value)
        assertEquals(UnsupportedReason.NO_USER_SESSION, assertIs<StorageError.Unsupported>(failed(ErrorMapping.windowsProbe(ErrorMapping.RoundTrip.Failed(1312)))).reason)
        assertIs<StorageError.Unavailable>(failed(ErrorMapping.windowsProbe(ErrorMapping.RoundTrip.Failed(5))))
    }

    // ---- 4.7 Linux ----------------------------------------------------------------------------------------------

    @Test
    fun `Linux status rows`() {
        fun map(status: RawStatus): StorageError = ErrorMapping.linuxStatus(status, op, key)
        assertEquals(UnsupportedReason.NO_KEY_STORE, assertIs<StorageError.Unsupported>(map(RawStatus.SYMBOL_MISSING)).reason, "libsecret older than 0.19, never NATIVE_ACCESS_UNAVAILABLE")
        assertEquals(UnsupportedReason.NO_KEY_STORE, assertIs<StorageError.Unsupported>(map(RawStatus(2, "g-dbus-error-quark", ErrorMapping.DBUS_SERVICE_UNKNOWN))).reason)
        assertEquals(UnsupportedReason.NO_KEY_STORE, assertIs<StorageError.Unsupported>(map(RawStatus(9, "g-dbus-error-quark", ErrorMapping.DBUS_ACCESS_DENIED))).reason)
        for (name: String in listOf(ErrorMapping.DBUS_SPAWN_CHILD_EXITED, ErrorMapping.DBUS_NO_REPLY, ErrorMapping.SECRET_IS_LOCKED)) {
            assertIs<StorageError.Unavailable>(map(RawStatus(1, "g-dbus-error-quark", name)), name)
        }
        assertIs<StorageError.Unavailable>(map(RawStatus(ErrorMapping.G_IO_ERROR_TIMED_OUT, ErrorMapping.G_IO_ERROR_DOMAIN)), "G_IO_ERROR_TIMED_OUT")
        assertEquals(46, assertIs<StorageError.AccessDenied>(map(RawStatus(46, ErrorMapping.G_IO_ERROR_DOMAIN))).platformCode, "a dismissed prompt")
        assertIs<StorageError.AccessDenied>(map(RawStatus(ErrorMapping.G_IO_ERROR_CANCELLED, ErrorMapping.G_IO_ERROR_DOMAIN)))
        val other: StorageError.OperationFailed = assertIs(map(RawStatus(77, "some-domain")))
        assertEquals(77, other.platformCode)
        // The same code in another domain is not G_IO_ERROR_TIMED_OUT.
        assertIs<StorageError.OperationFailed>(map(RawStatus(ErrorMapping.G_IO_ERROR_TIMED_OUT, "other-quark")))
    }

    @Test
    fun `Linux locked match and locked collection never read as not found`() {
        assertIs<StorageError.AccessDenied>(ErrorMapping.linuxLockedMatch(allowUi = true))
        assertIs<StorageError.Unavailable>(ErrorMapping.linuxLockedMatch(allowUi = false))
        assertIs<StorageError.Unavailable>(ErrorMapping.linuxCollectionLockedWithoutUi())
    }

    @Test
    fun `Linux probe rows and the session bus check`() {
        assertEquals(DesktopKeyStore.LINUX_SECRET_SERVICE, assertIs<Verdict.Ok<DesktopKeyStore>>(ErrorMapping.linuxProbe(locked = false, portal = false, allowUserInteraction = false)).value)
        assertEquals(DesktopKeyStore.LINUX_SECRET_PORTAL, assertIs<Verdict.Ok<DesktopKeyStore>>(ErrorMapping.linuxProbe(locked = false, portal = true, allowUserInteraction = false)).value)
        assertEquals(DesktopKeyStore.LINUX_SECRET_SERVICE, assertIs<Verdict.Ok<DesktopKeyStore>>(ErrorMapping.linuxProbe(locked = true, portal = false, allowUserInteraction = true)).value)
        assertIs<StorageError.Unavailable>(failed(ErrorMapping.linuxProbe(locked = true, portal = false, allowUserInteraction = false)))

        assertTrue(ErrorMapping.linuxHasSessionBus("unix:path=/run/user/1000/bus", null) { false })
        assertTrue(ErrorMapping.linuxHasSessionBus(null, "/run/user/1000") { it == "/run/user/1000/bus" })
        assertTrue(ErrorMapping.linuxHasSessionBus("", "/run/user/1000/") { it == "/run/user/1000/bus" })
        assertFalse(ErrorMapping.linuxHasSessionBus(null, "/run/user/1000") { false })
        assertFalse(ErrorMapping.linuxHasSessionBus(null, null) { true })
        assertFalse(ErrorMapping.linuxHasSessionBus("  ", "") { true })
    }

    // ---- the executor's own outcomes ------------------------------------------------------------------------------

    @Test
    fun `executor outcomes - time limit is Unavailable, an unavailable binding is NATIVE_ACCESS_UNAVAILABLE, a throw fails the operation`() {
        assertIs<StorageError.Unavailable>(ErrorMapping.nativeOutcome(NativeOutcome.TimedOut, op, key))
        for (error: Throwable in listOf(IllegalCallerException(), UnsatisfiedLinkError(), NoSuchMethodError(), SecurityException())) {
            val classified: NativeOutcome<Nothing> = NativeExecutor.classify(error)
            val mapped: StorageError.Unsupported = assertIs(ErrorMapping.nativeOutcome(classified, op, key), error::class.java.simpleName)
            assertEquals(UnsupportedReason.NATIVE_ACCESS_UNAVAILABLE, mapped.reason)
        }
        val threw: StorageError.OperationFailed = assertIs(ErrorMapping.nativeOutcome(NativeOutcome.Threw(IllegalStateException("x")), StorageOperation.PUT, "k"))
        assertEquals(StorageOperation.PUT, threw.operation)
        assertEquals("k", threw.key)
    }
}
