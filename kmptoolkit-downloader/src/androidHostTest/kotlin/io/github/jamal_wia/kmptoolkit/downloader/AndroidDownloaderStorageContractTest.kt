package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
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

    override fun backdate(path: String) {
        check(File(path).setLastModified(System.currentTimeMillis() - 3_600_000L)) { "could not backdate $path" }
    }

    override fun readFile(path: String): ByteArray? = File(path).takeIf { it.isFile }?.readBytes()

    override fun pathExists(path: String): Boolean = File(path).exists()

    override fun deleteTestDirectory() {
        File(context.filesDir, config.baseDirectoryName).deleteRecursively()
    }

    override fun baseDirectory(): String = File(context.filesDir, config.baseDirectoryName).absolutePath

    override fun deleteFile(path: String) {
        File(path).delete()
    }
}
