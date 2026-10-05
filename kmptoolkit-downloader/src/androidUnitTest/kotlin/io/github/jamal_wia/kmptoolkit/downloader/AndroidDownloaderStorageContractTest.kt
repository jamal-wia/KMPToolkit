package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.junit.runner.RunWith

/** Runs [DownloaderStorageContractTest] against the Android storage. */
@RunWith(AndroidJUnit4::class)
class AndroidDownloaderStorageContractTest : DownloaderStorageContractTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "contract_test")

    override fun newStorage(): DownloaderStorage = createDownloaderStorage(context, config)

    override fun writeFile(path: String, bytes: ByteArray) {
        File(path).apply { parentFile!!.mkdirs() }.writeBytes(bytes)
    }

    override fun readFile(path: String): ByteArray? = File(path).takeIf { it.isFile }?.readBytes()

    override fun pathExists(path: String): Boolean = File(path).exists()

    override fun deleteTestDirectory() {
        File(context.filesDir, config.baseDirectoryName).deleteRecursively()
    }

    @Test
    fun `begin fails without renaming a record when the old temp file cannot be deleted`() {
        val unit = ContractUnit("stuck")
        val storage: DownloaderStorage = newStorage()
        // A non-empty directory at the partial path cannot be removed by File.delete().
        val partial = File(storage.getTempFilePath(unit))
        File(partial, "child").apply { parentFile!!.mkdirs() }.writeText("x")
        val record = File(partial.parentFile, "expect/${unit.id}")

        assertFailsWith<IllegalStateException> { storage.beginTempFile(unit, null) }

        assertFalse(record.exists(), "the new record must not be paired with the surviving old file")
        assertFalse(File(partial.parentFile, "expect-staged/${unit.id}").exists())
    }

    private class ContractUnit(override val id: String) : DownloadUnit {
        override val apiPath: String = "/resources/$id"
        override val relativePath: String = "resources/$id.bin"
        override val group: ResourceGroup = object : ResourceGroup {
            override val key: String = id
            override val units: List<DownloadUnit> get() = listOf(this@ContractUnit)
        }
    }
}
