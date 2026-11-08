package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.URISyntaxException
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * What VLC is asked to open for one [VideoSource].
 *
 * @property mrl the media resource locator handed to libvlc — a local path or a URL.
 * @property options per-media libvlc options (`:http-user-agent=...`).
 * @property isLocal whether the whole source is on this machine, which makes the buffered position
 *   the duration.
 * @property temporaryFile a file extracted for this media alone, deleted when the media is unloaded.
 */
internal class ResolvedMedia(
    val mrl: String,
    val options: List<String>,
    val isLocal: Boolean,
    val temporaryFile: File?,
) {
    /** Deletes the temporary copy, if there is one, and forgets it. Idempotent. */
    fun discard() {
        temporaryFile?.let(AssetCopies::release)
    }
}

/**
 * The temporary copies of jar-packed assets that are alive right now. Each is deleted by its own
 * [ResolvedMedia.discard]; the one shutdown hook only sweeps what a consumer never discarded.
 *
 * Not `File.deleteOnExit()`: that adds the path to the JDK's static `DeleteOnExitHook` set, which
 * has no removal, so an app looping over a packaged playlist would grow it by one entry per load
 * for the life of the process. Here the set is bounded by the number of live sessions.
 */
internal object AssetCopies {

    // A concurrent set rather than a Mutex-guarded one: it is touched from the teardown thread, from
    // Dispatchers.IO and from a JVM shutdown hook, and neither the blocking teardown nor the hook can
    // suspend, so no coroutine synchronization tool fits (D-21).
    private val live: MutableSet<File> = ConcurrentHashMap.newKeySet()

    // A synchronized lazy installs the hook exactly once from whichever thread creates the first
    // copy; those callers are plain blocking threads and Runtime.addShutdownHook is not suspending,
    // so a Mutex cannot be used here (D-21).
    private val hook: Lazy<Unit> = lazy {
        Runtime.getRuntime().addShutdownHook(Thread(::sweep, "kmptoolkit-vlcj-asset-cleanup"))
    }

    /** How many copies have been created and not yet released. */
    val liveCount: Int get() = live.size

    /** Creates an empty temporary file and registers it; [release] deletes it. */
    fun create(suffix: String): File {
        hook.value
        val file: File = Files.createTempFile("kmptoolkit-video-", suffix).toFile()
        live += file
        return file
    }

    /**
     * Deletes [file] and forgets it once it is gone. A copy that cannot be deleted yet (on Windows a
     * file the native player still holds open) stays registered, so the shutdown hook retries it,
     * as `deleteOnExit` used to ([release] itself is reached once per copy, through `discard()`).
     * Idempotent.
     */
    fun release(file: File) {
        if (file.delete() || !file.exists()) live -= file
    }

    /** What the shutdown hook runs: one more delete attempt for every copy still registered. */
    fun sweep() {
        live.forEach { it.delete() }
    }
}

/**
 * Maps a [VideoSource] onto something libvlc can open, validating everything that can be
 * validated before VLC is involved so those failures carry a precise type.
 */
internal object VlcMediaResolver {

    /** Remote headers VLC 3 can send, mapped to the per-media option that sends each. */
    private val SUPPORTED_HEADERS: Map<String, String> = mapOf(
        "user-agent" to "http-user-agent",
        "referer" to "http-referrer",
    )

    /**
     * Resolves [source]. Blocking: an asset packed in a jar is copied to a temporary file.
     *
     * @throws FileNotFoundException for a missing file or asset.
     * @throws IllegalArgumentException for a blank URL or a header VLC cannot send.
     */
    fun resolve(source: VideoSource, classLoader: ClassLoader): ResolvedMedia = when (source) {
        is VideoSource.File -> resolveFile(source.path)
        is VideoSource.Asset -> resolveAsset(source.path, classLoader)
        is VideoSource.Remote -> resolveRemote(source.url, source.headers)
    }

    /** The libvlc option for each header, in [headers]' order. */
    fun headerOptions(headers: Map<String, String>): List<String> {
        val unsupported: List<String> = headers.keys.filter { it.lowercase() !in SUPPORTED_HEADERS }
        require(unsupported.isEmpty()) {
            "VLC cannot send the HTTP header(s) $unsupported; only User-Agent and Referer are " +
                "supported. Put credentials in the URL (a signed URL, or user:password@host for Basic)."
        }
        return headers.map { (name: String, value: String) ->
            require('\n' !in value && '\r' !in value) { "HTTP header '$name' contains a line break" }
            ":${SUPPORTED_HEADERS.getValue(name.lowercase())}=$value"
        }
    }

    private fun resolveFile(path: String): ResolvedMedia {
        if (path.isBlank()) throw FileNotFoundException("VideoSource.File path is blank")
        val file = File(path)
        if (!file.isFile) throw FileNotFoundException("No such file: $path")
        if (!file.canRead()) throw FileNotFoundException("File is not readable: $path")
        return ResolvedMedia(file.absolutePath, emptyList(), isLocal = true, temporaryFile = null)
    }

    /**
     * An asset is a classpath resource. Run from a build directory it is a plain file, which VLC
     * opens in place; packed in a jar, VLC cannot read it, so it is copied to a temporary file that
     * lives exactly as long as the media.
     */
    private fun resolveAsset(path: String, classLoader: ClassLoader): ResolvedMedia {
        val resourcePath: String = path.trimStart('/')
        if (resourcePath.isBlank()) throw FileNotFoundException("VideoSource.Asset path is blank")
        val url: URL = classLoader.getResource(resourcePath)
            ?: throw FileNotFoundException("No classpath resource: $resourcePath")
        if (url.protocol == "file") {
            // A loader that does not percent-encode (File.toURL(), plugin loaders) hands back a URL that
            // is not a valid URI; its path is then the file path as written. An authority URL
            // (file://host/...) keeps going through the URI.
            val file: File = try {
                Paths.get(url.toURI()).toFile()
            } catch (_: URISyntaxException) {
                File(url.path)
            }
            return ResolvedMedia(file.absolutePath, emptyList(), isLocal = true, temporaryFile = null)
        }
        val extension: String = resourcePath.substringAfterLast('/').substringAfterLast('.', "")
        val suffix: String = if (extension.isEmpty()) ".tmp" else ".$extension"
        val target: File = AssetCopies.create(suffix)
        try {
            val input: InputStream = url.openStream()
            input.use { Files.copy(it, target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } catch (error: Throwable) {
            AssetCopies.release(target)
            throw error
        }
        return ResolvedMedia(target.absolutePath, emptyList(), isLocal = true, temporaryFile = target)
    }

    private fun resolveRemote(url: String, headers: Map<String, String>): ResolvedMedia {
        require(url.isNotBlank()) { "VideoSource.Remote url is blank" }
        return ResolvedMedia(url, headerOptions(headers), isLocal = false, temporaryFile = null)
    }
}
