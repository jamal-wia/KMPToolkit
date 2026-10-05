package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.downloader.spi.ResolvedDownload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    @Test
    fun `toString never shows the query or fragment of a signed link`() {
        val text: String = ResolvedDownload("https://cdn.example/a/b.bin?X-Sig=secret123&exp=9#frag", hash).toString()

        assertEquals(
            "ResolvedDownload(url=https://cdn.example/a/b.bin?…, expectedSha256=Sha256(${hash.hex}))",
            text,
        )
        assertFalse("secret123" in text)
        assertFalse("frag" in text)
    }

    @Test
    fun `toString masks the userinfo of the url`() {
        val text: String = ResolvedDownload("https://user:pass@cdn.example/a/b.bin", hash).toString()

        assertEquals(
            "ResolvedDownload(url=https://***@cdn.example/a/b.bin, expectedSha256=Sha256(${hash.hex}))",
            text,
        )
        assertFalse("pass" in text)
    }

    @Test
    fun `toString masks the userinfo and drops the query together`() {
        val text: String = ResolvedDownload("https://user:pass@cdn.example/a?sig=secret", hash).toString()

        assertEquals(
            "ResolvedDownload(url=https://***@cdn.example/a?…, expectedSha256=Sha256(${hash.hex}))",
            text,
        )
    }

    @Test
    fun `toString marks a cut at a fragment with a hash sign`() {
        val text: String = ResolvedDownload("https://cdn.example/a#frag", hash).toString()

        assertEquals(
            "ResolvedDownload(url=https://cdn.example/a#…, expectedSha256=Sha256(${hash.hex}))",
            text,
        )
        assertFalse("frag" in text)
    }

    @Test
    fun `toString cuts at the query when both a query and a fragment follow`() {
        val text: String = ResolvedDownload("https://cdn.example/a?x=1#frag", hash).toString()

        assertEquals(
            "ResolvedDownload(url=https://cdn.example/a?…, expectedSha256=Sha256(${hash.hex}))",
            text,
        )
    }

    @Test
    fun `an at sign outside the authority is not mistaken for userinfo`() {
        assertEquals(
            "ResolvedDownload(url=https://cdn.example/users/@me, expectedSha256=null)",
            ResolvedDownload("https://cdn.example/users/@me").toString(),
        )
    }
}
