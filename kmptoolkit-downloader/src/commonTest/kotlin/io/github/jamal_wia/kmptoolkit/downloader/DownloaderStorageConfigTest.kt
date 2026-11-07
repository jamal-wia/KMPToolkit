package io.github.jamal_wia.kmptoolkit.downloader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** [DownloaderStorageConfig.baseDirectoryName] must be one plain folder name below the platform root. */
class DownloaderStorageConfigTest {

    @Test
    fun `dot and dot-dot are rejected`() {
        assertFailsWith<IllegalArgumentException> { DownloaderStorageConfig(baseDirectoryName = "..") }
        assertFailsWith<IllegalArgumentException> { DownloaderStorageConfig(baseDirectoryName = ".") }
    }

    @Test
    fun `blank names and separators are still rejected`() {
        for (name: String in listOf("", " ", "a/b", "a\\b", "/", "a\u0000b")) {
            assertFailsWith<IllegalArgumentException>(name) { DownloaderStorageConfig(baseDirectoryName = name) }
        }
    }

    @Test
    fun `ordinary names that merely contain dots are accepted`() {
        for (name: String in listOf("...", ".hidden", "a..b", "res.v2", "kmptoolkit_downloader")) {
            assertEquals(name, DownloaderStorageConfig(baseDirectoryName = name).baseDirectoryName)
        }
    }
}
