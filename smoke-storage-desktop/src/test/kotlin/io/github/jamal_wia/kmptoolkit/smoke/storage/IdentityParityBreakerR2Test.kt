package io.github.jamal_wia.kmptoolkit.smoke.storage

import java.io.File
import java.lang.reflect.Method
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Assume.assumeTrue

/**
 * Breaker round 2 of group sto-smoke (STO-14, packaging smoke): the smoke's COPY of the library's key item identity
 * ([MacKeyItem]) must equal the library's own derivation (`StoreIdentity.of(name, directory)`, macOS rules) for every input the
 * library normalises: a symbolic link in the directory path (`/tmp` vs `/private/tmp` on macOS), a directory that does not exist
 * yet (the library derives the identity at the first put, the smoke at cleanup, after the directory exists), `.`/`..` and
 * trailing-separator spellings, an NFD directory name, an NFD store name. The known-answer test of the executor pins the copy
 * to a vector computed from the copy's own algorithm; it stays green if the LIBRARY changes. This test calls the library's code
 * (internal, so through reflection) and fails on the first drift.
 *
 * Mutation by reading: drop the NFC step of `MacKeyItem.ofCanonical`, use `absolutePath` instead of `canonicalFile`, change the
 * suffix, or change `StoreIdentity.of` / `secureStoreId` in the library: a case below turns red.
 * Files only under a directory this test creates under the project's build directory and deletes.
 */
class IdentityParityBreakerR2Test {

    private val root: File = File("build/breaker-r2-identity").absoluteFile

    @BeforeTest
    fun setUp() {
        root.deleteRecursively()
        check(root.mkdirs()) { "could not create $root" }
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun library(name: String, directory: File): Pair<String, String> {
        val identityClass: Class<*> = Class.forName("io.github.jamal_wia.kmptoolkit.storage.internal.desktop.StoreIdentity")
        val companionField = identityClass.getDeclaredField("Companion").apply { isAccessible = true }
        val companion: Any = companionField.get(null)
        val of: Method = companion.javaClass.declaredMethods.single { m: Method ->
            m.name.startsWith("of") && m.parameterTypes.contentEquals(arrayOf(String::class.java, File::class.java))
        }.apply { isAccessible = true }
        val identity: Any = of.invoke(companion, name, directory)
        fun getter(prefix: String): String = identityClass.declaredMethods
            .single { it.name == prefix || it.name.startsWith("$prefix\$") }
            .apply { isAccessible = true }
            .invoke(identity) as String
        return getter("getService") to getter("getMacAccount")
    }

    private fun smoke(name: String, directory: File): Pair<String, String> =
        MacKeyItem.of(name, directory).let { it.service to it.account }

    private fun assertParity(case: String, name: String, directory: File) {
        assertEquals(library(name, directory), smoke(name, directory), "case=$case name=$name directory=$directory")
    }

    private fun macOnly() = assumeTrue("macOS rules (NFC, /private links)", System.getProperty("os.name").orEmpty().startsWith("Mac"))

    @Test
    fun `plain existing directory`() {
        macOnly()
        val dir: File = File(root, "secure").apply { mkdirs() }
        assertParity("existing", "kmptoolkit.smoke.storage.secure.0123456789ab", dir)
    }

    @Test
    fun `a symbolic link in the directory path resolves like the library (tmp vs private tmp)`() {
        macOnly()
        val real: File = File(root, "real").apply { mkdirs() }
        val link: File = File(root, "link")
        Files.createSymbolicLink(link.toPath(), real.toPath())
        val viaLink: File = File(link, "secure").apply { mkdirs() }
        assertParity("link", "n", viaLink)
        assertEquals(smoke("n", viaLink), smoke("n", File(real, "secure")), "link and target are one identity")
    }

    @Test
    fun `library at creation (directory missing) equals smoke at cleanup (directory exists)`() {
        macOnly()
        val real: File = File(root, "real").apply { mkdirs() }
        val link: File = File(root, "link")
        Files.createSymbolicLink(link.toPath(), real.toPath())
        val dir: File = File(link, "secure")
        val atCreation: Pair<String, String> = library("n", dir)
        check(dir.mkdirs())
        assertEquals(atCreation, smoke("n", dir), "identity derived before and after the directory exists")
    }

    @Test
    fun `dot, dot-dot and trailing separator spellings`() {
        macOnly()
        val dir: File = File(root, "secure").apply { mkdirs() }
        assertParity("dot", "n", File(dir.path + File.separator + "."))
        assertParity("dotdot", "n", File(dir, ".." + File.separator + "secure"))
        assertParity("trailing", "n", File(dir.path + File.separator))
    }

    @Test
    fun `an NFD directory name`() {
        macOnly()
        val dir: File = File(root, "café").apply { mkdirs() }
        assertParity("nfd-directory", "n", dir)
    }

    @Test
    fun `an NFD store name`() {
        macOnly()
        val dir: File = File(root, "secure").apply { mkdirs() }
        assertParity("nfd-name", "café", dir)
    }
}
