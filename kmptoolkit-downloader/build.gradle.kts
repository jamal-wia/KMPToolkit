plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Downloader")
    pomDescription.set(
        "A resumable background-download engine for large assets your app can't ship inside " +
            "the binary: a bundled dataset, a downloadable model file, a media archive fetched " +
            "from a server manifest. The transfer outlives the process — resumed after " +
            "backgrounding, process death and restart — and a resource is verified before it " +
            "counts as committed, so a consumer that gets pathOf(unit) back can open the file " +
            "without defensive checks. Storage is shipped for you (Android and iOS); the " +
            "platform transfer itself is a port you implement (BackgroundResourceDownloader), so " +
            "the module has no HTTP client and no opinion about how bytes move. Pick this module " +
            "if a resource must survive being backgrounded mid-download and must never be read " +
            "half-written."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.downloader"
}

kotlin {
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        // Deliberately thin, like :kmptoolkit-outbox — coroutines, nothing else, in commonMain.
        // No DI framework (the host wires the engine; an OS-created entry point reaches it through
        // DownloaderRegistry), no HTTP client (the host resolves download URLs through the
        // DownloadUrlResolver port; the bytes themselves move over whatever the host's
        // BackgroundResourceDownloader implementation uses), no user-facing strings anywhere (the
        // DownloadNotifier port carries only typed progress and typed DownloadError).
        commonMain.dependencies {
            // api, not implementation: Downloader.downloadState / unitDownloadStateFlow return
            // Flow/StateFlow and createDownloader takes a DownloadDispatchers — both public
            // signatures, so a consumer needs coroutines-core on its compile classpath.
            api(libs.kotlinx.coroutines.core)
        }
        iosMain.dependencies {
            // The bundled SQLite driver, not the platform's own: Kotlin/Native has no SQLite
            // binding of its own, and this is what verifies a ResourceFormat.SqliteDatabase unit
            // before it is committed. Android instead uses android.database.sqlite, already on
            // every Android classpath.
            implementation(libs.androidx.sqlite.bundled)
        }
        // No dependency on :kmptoolkit-downloader-testing — that module depends on this one, so
        // the reverse edge would be a project cycle (same reasoning as :kmptoolkit-outbox). The
        // engine tests here build their own small, local fakes instead.
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
