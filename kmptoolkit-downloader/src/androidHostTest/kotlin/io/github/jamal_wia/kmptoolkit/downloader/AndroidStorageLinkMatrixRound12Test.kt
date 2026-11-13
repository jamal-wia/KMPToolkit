package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files
import org.junit.runner.RunWith

/** Runs [StorageLinkMatrixRound12Contract] against the Android storage. */
@RunWith(AndroidJUnit4::class)
class AndroidStorageLinkMatrixRound12Test : StorageLinkMatrixRound12Contract() {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "link_matrix_round12")

    override fun newStorage(): DownloaderStorage = createDownloaderStorage(context, config)

    override fun writeFile(path: String, bytes: ByteArray) {
        File(path).apply { parentFile!!.mkdirs() }.writeBytes(bytes)
    }

    override fun readFile(path: String): ByteArray? = File(path).takeIf { it.isFile }?.readBytes()

    override fun pathExists(path: String): Boolean = File(path).exists()

    override fun makeLink(link: String, target: String) {
        File(link).parentFile!!.mkdirs()
        Files.createSymbolicLink(File(link).toPath(), File(target).toPath())
    }

    override fun isLink(path: String): Boolean = Files.isSymbolicLink(File(path).toPath())

    override fun deleteTestDirectory() {
        File(context.filesDir, config.baseDirectoryName).deleteRecursively()
    }
}
