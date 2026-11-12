package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.io.File
import java.io.FileNotFoundException
import java.net.URI
import java.net.URISyntaxException
import java.net.URL

/**
 * The URI JavaFX Media opens for [source], checked as far as can be without JavaFX: a missing local
 * file (a directory, or one that cannot be read) or classpath resource fails here with
 * [FileNotFoundException], and headers are rejected before
 * any connection is made.
 *
 * JavaFX has no asset directory of its own, so [VideoSource.Asset.path] is a classpath resource path —
 * what `src/main/resources/` or Compose's `jvmMain/resources` produce — resolved through
 * [classLoader]. A resource inside a jar comes back as a `jar:file:…!/…` URL, which JavaFX plays.
 */
internal fun resolveSourceUri(source: VideoSource, classLoader: ClassLoader): String = when (source) {
    is VideoSource.Asset -> {
        require(source.path.isNotBlank()) { "Asset path is blank" }
        val url: URL = classLoader.getResource(source.path.removePrefix("/"))
            ?: throw FileNotFoundException("No classpath resource '${source.path}'")
        // A loader that does not percent-encode (File.toURL(), plugin loaders) hands back a file: URL, or a
        // jar:file: URL, that is not a valid URI, which JavaFX Media rejects; the file path is then taken as
        // written and the file's own URI is encoded. Anything else goes on as is.
        when (url.protocol) {
            "file" -> fileUrlAsUri(url)
            "jar" -> jarUrlAsUri(url)
            else -> url.toExternalForm()
        }
    }

    is VideoSource.File -> {
        require(source.path.isNotBlank()) { "File path is blank" }
        val file = File(source.path)
        if (!file.isFile) throw FileNotFoundException(source.path)
        // JavaFX would take such a file and fail it with a MediaException after several seconds.
        if (!file.canRead()) throw FileNotFoundException("File is not readable: ${source.path}")
        file.toURI().toString()
    }

    is VideoSource.Remote -> {
        if (source.headers.isNotEmpty()) {
            throw JavaFxVideoPlayerException.HeadersNotSupported(source.headers.keys.toSet())
        }
        require(source.url.isNotBlank()) { "Remote URL is blank" }
        source.url
    }
}

private fun fileUrlAsUri(url: URL): String {
    // A name outside the Basic Multilingual Plane: the JDK encoded each surrogate on its own, so the URI is valid
    // but its bytes are not UTF-8 and JavaFX rejects it. The file's own URI is encoded properly.
    val decoded: DecodedUrlPath? = if (url.authority.isNullOrEmpty()) decodeUrlPath(url.path) else null
    if (decoded != null && decoded.repaired) return File(decoded.text).toURI().toString()
    return try {
        url.toURI().toString()
    } catch (_: URISyntaxException) {
        File(url.path).toURI().toString()
    }
}

/**
 * A `jar:file:<jar>!/<entry>` URL as a valid URI: untouched when it already is one (a loader that
 * percent-encodes), otherwise the jar's own encoded URI and the entry encoded as a path. Anything that is not
 * a jar on the local disk goes on as written.
 */
private fun jarUrlAsUri(url: URL): String {
    val written: String = url.toExternalForm()
    val separator: Int = written.indexOf(JAR_SEPARATOR)
    val spec: String = written.removePrefix("jar:")
    val local: Boolean = separator >= 0 && spec.startsWith("file:")
    val jarPath: String = if (local) written.substring("jar:file:".length, separator) else ""
    val entry: String = if (local) written.substring(separator + JAR_SEPARATOR.length) else ""
    // A name outside the Basic Multilingual Plane (in the entry or in the jar's own directory): the JDK encoded each
    // surrogate on its own, which is a valid URI whose bytes JavaFX cannot decode, so both parts are re-encoded.
    val decodedJar: DecodedUrlPath? = if (local) decodeUrlPath(jarPath) else null
    val decodedEntry: DecodedUrlPath? = if (local) decodeUrlPath(entry) else null
    val repaired: Boolean = decodedJar?.repaired == true || decodedEntry?.repaired == true
    if (!repaired) {
        try {
            return url.toURI().toString()
        } catch (_: URISyntaxException) {
            // Repaired below.
        }
    }
    if (!local) return written
    val jarUri: String = File(if (repaired) decodedJar?.text ?: jarPath else jarPath).toURI().toString()
    val entryPath: String = URI(null, null, "/${if (repaired) decodedEntry?.text ?: entry else entry}", null).rawPath.removePrefix("/")
    return "jar:$jarUri$JAR_SEPARATOR$entryPath"
}

private const val JAR_SEPARATOR: String = "!/"
