package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.runner.RunWith

/** Runs [ZipEntryPathLimitRound10Contract] against the Android storage. */
@RunWith(AndroidJUnit4::class)
class AndroidZipEntryPathLimitRound10Test : ZipEntryPathLimitRound10Contract() {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "zip_pathlimit_round10")

    override fun newStorage(): DownloaderStorage = createDownloaderStorage(context, config)

    override fun writeFile(path: String, bytes: ByteArray) {
        File(path).apply { parentFile!!.mkdirs() }.writeBytes(bytes)
    }

    override fun readFile(path: String): ByteArray? = File(path).takeIf { it.isFile }?.readBytes()

    override fun pathExists(path: String): Boolean = File(path).exists()

    override fun deleteTestDirectory() {
        File(context.filesDir, config.baseDirectoryName).deleteRecursively()
    }
}
