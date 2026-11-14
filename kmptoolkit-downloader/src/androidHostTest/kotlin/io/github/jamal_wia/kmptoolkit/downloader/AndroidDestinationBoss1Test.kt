package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.testsupport.TestFs
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

    private val restorers: MutableList<AutoCloseable> = mutableListOf()

    override fun makeReadOnly(path: String) {
        restorers.add(TestFs.makeUnwritable(File(path)))
    }

    override fun restoreWritable() {
        // A restorer is bound to the path it restricted, and the commit moves that directory into the trash: give the
        // write bit back wherever the directory went, then let each restorer finish (its path may be gone).
        File(context.filesDir, config.baseDirectoryName).walkTopDown()
            .filter { file: File -> file.isDirectory }
            // path-hygiene-ignore: PH2 undoes TestFs.makeUnwritable on a directory the storage moved away from the restricted path
            .forEach { dir: File -> dir.setWritable(true) }
        restorers.forEach { restorer: AutoCloseable -> runCatching { restorer.close() } }
        restorers.clear()
    }
}
