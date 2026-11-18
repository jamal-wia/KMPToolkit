package io.github.jamal_wia.kmptoolkit.storage.jna

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawResult
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawStatus
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.SecretStatus
import java.lang.reflect.Method
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Assume

/**
 * Breaker round 1 of group sto-jna (ticket item STO-8, plan 3.5, 3.6, 4.5-4.7): tests written to break the JNA binding of
 * the desktop key-store seam. Derived from the seam contract (`NativeKeyStoreBinding.kt` KDoc: every raw call frees every
 * OS-allocated output on every path and reports an OS failure as a raw status), plan 3.5 / 4.7 (lookup through libsecret's
 * backend selection, the portal reported only when libsecret selected it) and the libsecret, CoreFoundation and libc ABIs.
 *
 * - The real-library symbol tests bind every method the binding declares against the real system library of this host
 *   (JNA binds by method name, and a name that does not exist only fails when it is first called). The Linux one runs where
 *   libsecret loads, and is required under `-Pkmptoolkit.secretstore=linux`.
 * - The variadic test drives JNA's `vararg Pointer?` path (the shape of every `secret_password_*` call) through libc on the
 *   host ABI, with more arguments than argument registers: on Apple Silicon every variadic argument goes on the stack, the
 *   classic defect of a hand-laid variadic call.
 */
class JnaBindingBreakerR1Test {

    private val attributes: Map<String, String> = linkedMapOf("service" to "svc.kmptoolkit.securestorage", "directory" to "0123456789abcdef0123456789abcdef")
    private val secret: ByteArray = "c2VjcmV0LWJhc2U2NA==".toByteArray()

    // ---- Linux: the real libsecret ------------------------------------------------------------------------------------

    @Test
    fun `every libsecret, glib, gobject and gio function the binding declares exists in the real libraries`() {
        val requiredHere: Boolean = System.getProperty("kmptoolkit.secretstore") == "linux"
        val secretLibrary: NativeLibrary? = if (Platform.isLinux()) loadOrNull(LinuxPlatform.LIBSECRET) else null
        if (secretLibrary == null) {
            check(!requiredHere) { "kmptoolkit.secretstore=linux: libsecret-1.so.0 must load on this host" }
            Assume.assumeTrue("needs a Linux host with libsecret-1.so.0", false)
            return
        }
        val missing: List<String> = listOf(
            secretLibrary to SecretApi::class.java,
            checkNotNull(loadOrNull(LinuxPlatform.GLIB)) to GLibApi::class.java,
            checkNotNull(loadOrNull(LinuxPlatform.GOBJECT)) to GObjectApi::class.java,
            checkNotNull(loadOrNull(LinuxPlatform.GIO)) to GioApi::class.java,
        ).flatMap { (library: NativeLibrary, api: Class<*>) -> missingFunctions(library, api) }
        assertEquals(
            emptyList(),
            missing,
            "a declared function the real library does not export can never succeed: JNA throws UnsatisfiedLinkError at the call " +
                "(backendIsPortal() then answers false on every machine, so the Flatpak portal is never reported, plan 4.7)",
        )
    }

    // ---- macOS and Windows: the same check, as the control that the symbol check itself works -------------------------

    @Test
    fun `every CoreFoundation and Security function the binding declares exists on a real Mac`() {
        Assume.assumeTrue("needs a macOS host", Platform.isMac())
        val missing: List<String> =
            missingFunctions(NativeLibrary.getInstance(MacPlatform.CORE_FOUNDATION_PATH), CoreFoundationApi::class.java) +
                missingFunctions(NativeLibrary.getInstance(MacPlatform.SECURITY_PATH), SecurityApi::class.java)
        assertEquals(emptyList(), missing)
    }

    @Test
    fun `every crypt32 and kernel32 function the binding declares exists on real Windows`() {
        Assume.assumeTrue("needs a Windows host", Platform.isWindows())
        val missing: List<String> =
            missingFunctions(NativeLibrary.getInstance("crypt32"), Crypt32Api::class.java) +
                missingFunctions(NativeLibrary.getInstance("kernel32"), Kernel32Api::class.java)
        assertEquals(emptyList(), missing)
    }

    // ---- Linux: store must not report success when libsecret did not store -----------------------------------------

    @Test
    fun `a store that libsecret answers FALSE without a GError is not reported as a success`() {
        val fake = FakeLinuxLibraries()
        val refusing: SecretApi = object : SecretApi by fake.secret {
            override fun secret_password_store_sync(
                schema: Pointer,
                collection: Pointer,
                label: Pointer,
                password: Pointer,
                cancellable: Pointer?,
                error: PointerByReference,
                vararg attributes: Pointer?,
            ): Boolean {
                // libsecret's secret_password_storev_sync returns FALSE with the error untouched when its attribute
                // validation fails ("Warnings raised already"): nothing was stored.
                fake.secret.secret_password_store_sync(schema, collection, label, password, cancellable, error, *attributes)
                return false
            }
        }
        val calls = JnaSecretServiceCalls(lazyOf(LinuxPlatform.Ready(LinuxLibraries(fake.glib, fake.gobject, fake.gio, refusing))), RecordingScratch())

        val status: SecretStatus = calls.store("label", attributes, secret)

        assertEquals(1, fake.calls.count { it.kind == "store" }, "control: the store call reached libsecret")
        assertFalse(
            status.isSuccess,
            "store() answered SUCCESS for a secret libsecret did not store; above the seam the new data key then exists only " +
                "in memory and every value written with it is lost at the next start (status=$status)",
        )
        assertEquals("", fake.leaks)
    }

    // ---- macOS: OS-allocated results are released on the failure paths too ------------------------------------------

    @Test
    fun `a read that answers a failure status together with a result object still releases that object`() {
        val fake = FakeMacLibraries()
        val created: MutableList<CfObject.Data> = mutableListOf()
        val security: SecurityApi = object : SecurityApi by fake.securityApi {
            override fun SecItemCopyMatching(query: Pointer, result: PointerByReference?): Int {
                val data = CfObject.Data()
                data.buffer.write(0L, ByteArray(32) { 7 }, 0, 32)
                data.length = 32L
                created.add(data)
                result?.value = fake.objects.create(data)
                return -25308
            }
        }
        val scratch = RecordingScratch()
        val calls = JnaMacKeychainCalls(lazyOf(MacPlatform(fake.coreFoundation, security, fake.symbols)), scratch)

        assertEquals(RawStatus(-25308), assertIs<RawResult.Failure>(calls.copyGenericPassword("svc", "acct", allowUi = false)).status)
        assertEquals(-25308, calls.probe(allowUi = false))

        assertEquals(2, created.size, "control: both calls received a result object")
        assertEquals(0, fake.live, "a result object handed out with a failure status is still the caller's to release")
        scratch.assertAllFreedAndWiped("failure paths")
    }

    @Test
    fun `a thousand mixed keychain calls with failures interleaved leave no CoreFoundation object alive (seed 5201)`() {
        val seed = 5201
        val random = kotlin.random.Random(seed)
        val fake = FakeMacLibraries()
        val scratch = RecordingScratch()
        val calls = JnaMacKeychainCalls(lazyOf(fake.platform), scratch)
        repeat(1_000) { index: Int ->
            val status: Int = listOf(0, -25300, -25308, -128, -25293, -34018)[random.nextInt(6)]
            fake.copyScript = { status to if (status == 0) ByteArray(random.nextInt(0, 64)) { 1 } else null }
            fake.addStatus = status
            fake.deleteStatus = status
            when (random.nextInt(4)) {
                0 -> calls.copyGenericPassword("svc-$index", "acct", random.nextBoolean())
                1 -> calls.addGenericPassword("svc-$index", "acct", "label", ByteArray(random.nextInt(0, 64)) { 3 }, random.nextBoolean())
                2 -> calls.deleteGenericPassword("svc-$index", "acct", random.nextBoolean())
                else -> calls.probe(random.nextBoolean())
            }
            assertEquals(0, fake.live, "seed=$seed step=$index: a CoreFoundation object outlived its call")
        }
        assertTrue(fake.objects.created.get() > 1_000, "seed=$seed control: the calls created objects")
        assertTrue(fake.releasedData.all { it.wipedAtRelease }, "seed=$seed: every released CFData was zeroed first")
        scratch.assertAllFreedAndWiped("seed=$seed")
    }

    // ---- The variadic ABI: JNA's vararg Pointer? path, with arguments spilling to the stack --------------------------

    /** libc's `sprintf`, declared in exactly the style of [SecretApi]'s variadic functions: fixed pointers, then `vararg Pointer?`. */
    internal interface VariadicLibC : Library {
        fun sprintf(buffer: Pointer, format: Pointer, vararg arguments: Pointer?): Int
    }

    @Test
    fun `a vararg Pointer call reaches a real variadic C function intact with more arguments than registers`() {
        val libc: VariadicLibC = Native.load(Platform.C_LIBRARY_NAME, VariadicLibC::class.java)
        val words: List<String> = (1..10).map { "w$it-ünï" }
        val blocks: MutableList<Memory> = mutableListOf()
        fun native(text: String): Memory {
            val bytes: ByteArray = text.toByteArray(Charsets.UTF_8)
            return Memory(bytes.size + 1L).also { it.clear(); it.write(0L, bytes, 0, bytes.size); blocks.add(it) }
        }
        try {
            val format: Memory = native(words.joinToString("|") { "%s" })
            val buffer = Memory(1024L).also { it.clear() }
            blocks.add(buffer)
            // 2 fixed + 10 strings + the NULL terminator a libsecret call ends with: 13 arguments, past every ABI's registers.
            val arguments: Array<Pointer?> = (words.map { native(it) } + listOf<Pointer?>(null)).toTypedArray()
            val written: Int = libc.sprintf(buffer, format, *arguments)
            val expected: String = words.joinToString("|")
            assertEquals(expected, buffer.getString(0L, "UTF-8"), "a variadic argument was passed in the wrong place on ${Platform.ARCH}")
            assertEquals(expected.toByteArray(Charsets.UTF_8).size, written)
        } finally {
            for (block: Memory in blocks) block.close()
        }
    }

    // ---- Shrinking: what JNA reads by reflection must survive ProGuard / R8 ------------------------------------------

    @Test
    fun `the shipped ProGuard rules keep the Exceptions attribute JNA reads to capture GetLastError`() {
        // Control: the DPAPI interface asks JNA for GetLastError through its throws clause, which lives only in the
        // class file's Exceptions attribute; JNA's NativeLibrary.getFunction(name, method) reads method.exceptionTypes.
        val declaresLastError: List<String> = Crypt32Api::class.java.declaredMethods
            .filter { method: Method -> method.exceptionTypes.contains(com.sun.jna.LastErrorException::class.java) }
            .map(Method::getName).sorted()
        assertEquals(listOf("CryptProtectData", "CryptUnprotectData"), declaresLastError, "control: the throws clause is what selects GetLastError capture")

        val rules: List<String> = checkNotNull(javaClass.classLoader.getResourceAsStream("META-INF/proguard/kmptoolkit-storage-jna.pro")) {
            "META-INF/proguard/kmptoolkit-storage-jna.pro is not on the classpath"
        }.bufferedReader().use { it.readText() }.lines().map { it.trim() }.filterNot { it.startsWith("#") || it.isEmpty() }
        val keptAttributes: List<String> = rules.filter { it.startsWith("-keepattributes") }
            .flatMap { it.removePrefix("-keepattributes").split(',').map(String::trim) }
        assertTrue(
            keptAttributes.any { it == "Exceptions" || it == "*" },
            "ProGuard and R8 drop the Exceptions attribute unless it is kept: a shrunk app then never captures GetLastError, " +
                "every DPAPI failure reads as ERROR_GEN_FAILURE (31) and 1312 (NO_USER_SESSION, plan 4.6) is never seen; kept: $keptAttributes",
        )
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private fun loadOrNull(name: String): NativeLibrary? = try {
        NativeLibrary.getInstance(name)
    } catch (_: UnsatisfiedLinkError) {
        null
    }

    /** The C names [api] declares that [library] does not export. */
    private fun missingFunctions(library: NativeLibrary, api: Class<*>): List<String> =
        api.declaredMethods.map(Method::getName).distinct().sorted().filter { name: String ->
            try {
                library.getFunction(name)
                false
            } catch (_: UnsatisfiedLinkError) {
                true
            }
        }.map { "${library.name}: $it" }
}
