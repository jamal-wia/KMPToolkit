package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.runner.RunWith

/** Runs [ZipDifferentialRound7Contract] against the Android storage. */
@RunWith(AndroidJUnit4::class)
class AndroidZipDifferentialRound7Test : ZipDifferentialRound7Contract() {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "zip_round7")

    override fun newStorage(): DownloaderStorage = createDownloaderStorage(context, config)

    override fun writeFile(path: String, bytes: ByteArray) {
        File(path).apply { parentFile!!.mkdirs() }.writeBytes(bytes)
    }

    override fun readFile(path: String): ByteArray? = File(path).takeIf { it.isFile }?.readBytes()

    override fun pathExists(path: String): Boolean = File(path).exists()

    override fun listNames(path: String): List<String> = File(path).list()?.toList().orEmpty()

    override fun deleteTestDirectory() {
        File(context.filesDir, config.baseDirectoryName).deleteRecursively()
    }
}
