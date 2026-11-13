package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assume
import org.junit.runner.RunWith

/** Runs [ZipRawNameRound11Contract] against the Android storage. */
@RunWith(AndroidJUnit4::class)
class AndroidZipRawNameRound11Test : ZipRawNameRound11Contract() {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "zip_rawname_round11")

    override fun newStorage(): DownloaderStorage = createDownloaderStorage(context, config)

    override fun writeFile(path: String, bytes: ByteArray) {
        File(path).apply { parentFile!!.mkdirs() }.writeBytes(bytes)
    }

    override fun readFile(path: String): ByteArray? = File(path).takeIf { it.isFile }?.readBytes()

    override fun pathExists(path: String): Boolean = File(path).exists()

    override fun assumeLongRawNamesCanBeResolved() {
        val probe: String = "a/../".repeat(2_000) + "f.txt"
        val resolves: Boolean = runCatching { File(File(context.filesDir, "probe"), probe).canonicalFile }.isSuccess
        Assume.assumeTrue("this JDK refuses a raw path over PATH_MAX in File.canonicalFile (suspect r11-S1)", resolves)
    }

    override fun deleteTestDirectory() {
        File(context.filesDir, config.baseDirectoryName).deleteRecursively()
    }
}
