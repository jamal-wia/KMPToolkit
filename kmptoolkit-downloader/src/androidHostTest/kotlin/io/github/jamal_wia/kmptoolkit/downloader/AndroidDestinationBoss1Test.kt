package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.runner.RunWith

/** Runs [DestinationBoss1Contract] against the Android storage. */
@RunWith(AndroidJUnit4::class)
class AndroidDestinationBoss1Test : DestinationBoss1Contract() {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "destination_boss1")

    override fun newStorage(): DownloaderStorage = createDownloaderStorage(context, config)

    override fun writeFile(path: String, bytes: ByteArray) {
        File(path).apply { parentFile!!.mkdirs() }.writeBytes(bytes)
    }

    override fun deletePath(path: String) {
        File(path).deleteRecursively()
    }

    override fun deleteTestDirectory() {
        File(context.filesDir, config.baseDirectoryName).deleteRecursively()
    }
}
