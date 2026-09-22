package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

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
    fun discard() {
        temporaryFile?.delete()
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
            val file: File = Paths.get(url.toURI()).toFile()
            return ResolvedMedia(file.absolutePath, emptyList(), isLocal = true, temporaryFile = null)
        }
        val extension: String = resourcePath.substringAfterLast('/').substringAfterLast('.', "")
        val suffix: String = if (extension.isEmpty()) ".tmp" else ".$extension"
        val target: File = Files.createTempFile("kmptoolkit-video-", suffix).toFile()
        target.deleteOnExit()
        try {
            val input: InputStream = url.openStream()
            input.use { Files.copy(it, target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
        return ResolvedMedia(target.absolutePath, emptyList(), isLocal = true, temporaryFile = target)
    }

    private fun resolveRemote(url: String, headers: Map<String, String>): ResolvedMedia {
        require(url.isNotBlank()) { "VideoSource.Remote url is blank" }
        return ResolvedMedia(url, headerOptions(headers), isLocal = false, temporaryFile = null)
    }
}
