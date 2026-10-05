package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.downloader.spi.ResolvedDownload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ResolvedDownloadTest {

    private val hash: Sha256 = Sha256.parse("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")

    @Test
    fun `the hash is optional and absent by default`() {
        assertNull(ResolvedDownload("https://cdn.example/a").expectedSha256)
    }

    @Test
    fun `a blank url is rejected`() {
        assertFailsWith<IllegalArgumentException> { ResolvedDownload("") }
        assertFailsWith<IllegalArgumentException> { ResolvedDownload("   ", hash) }
    }

    @Test
    fun `equality covers both the url and the hash`() {
        val a = ResolvedDownload("https://cdn.example/a", hash)

        assertEquals(a, ResolvedDownload("https://cdn.example/a", Sha256.parse(hash.hex.uppercase())))
        assertEquals(a.hashCode(), ResolvedDownload("https://cdn.example/a", hash).hashCode())
        assertNotEquals(a, ResolvedDownload("https://cdn.example/b", hash))
        assertNotEquals(a, ResolvedDownload("https://cdn.example/a"))
    }

    @Test
    fun `toString shows the url and the hash`() {
        assertEquals(
            "ResolvedDownload(url=https://cdn.example/a, expectedSha256=Sha256(${hash.hex}))",
            ResolvedDownload("https://cdn.example/a", hash).toString(),
        )
    }
}
