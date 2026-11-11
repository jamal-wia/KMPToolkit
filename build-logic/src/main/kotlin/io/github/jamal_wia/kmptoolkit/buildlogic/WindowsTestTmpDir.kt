package io.github.jamal_wia.kmptoolkit.buildlogic

import java.io.File
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.withType

/** The Gradle property that turns [shortenWindowsTestTmpDir] on; its value is the folder to use. */
internal const val WINDOWS_TEST_TMP_DIR_PROPERTY: String = "kmptoolkit.windowsTestTmpDir"

/**
 * Gives every test JVM of this module a short `java.io.tmpdir` — on Windows, and only when the Gradle
 * property `kmptoolkit.windowsTestTmpDir` names a folder (for example
 * `-Pkmptoolkit.windowsTestTmpDir=C:\kt`).
 *
 * Why: Robolectric puts each test's working folder under `java.io.tmpdir`, named after the test class
 * and method (up to 120 characters, plus 19 digits). Android's SQLite is native code, and native
 * Windows code cannot open a path longer than 259 characters (MAX_PATH), unlike Java's own file API.
 * Measured on Windows with the default `C:\Users\<user>\AppData\Local\Temp\`: a database at 259
 * characters opens, one at 260 fails with `SQLiteCantOpenDatabaseException`. A short folder such as
 * `C:\kt\` saves about 29 characters, which lets part of the failing paths fit; the rest are skipped
 * by the tests themselves with `TestHost.assumeNativePathFits`, which skips a too-long path only while
 * this property is NOT set (the property is also handed to the test JVM for that check).
 *
 * Off by default: it changes where tests write on a developer's machine, so it waits for the owner's
 * answer (ticket 4.1.0, question Q3). Nothing changes on macOS or Linux, whatever the property says,
 * and nothing changes on Windows without it. The folder is created before each test task starts and
 * is left in place; it is shared by every module and every run, which is safe because the names
 * Robolectric and `Files.createTempDirectory` generate are unique.
 */
internal fun Project.shortenWindowsTestTmpDir() {
    val folder: String = providers.gradleProperty(WINDOWS_TEST_TMP_DIR_PROPERTY).orNull?.trim().orEmpty()
    if (folder.isEmpty()) return
    if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return

    tasks.withType<Test>().configureEach {
        val tmp: File = File(folder)
        systemProperty("java.io.tmpdir", tmp.absolutePath)
        // Tells TestHost.assumeNativePathFits that the short folder is on, so it stops skipping.
        systemProperty(WINDOWS_TEST_TMP_DIR_PROPERTY, tmp.absolutePath)
        doFirst { tmp.mkdirs() }
    }
}
