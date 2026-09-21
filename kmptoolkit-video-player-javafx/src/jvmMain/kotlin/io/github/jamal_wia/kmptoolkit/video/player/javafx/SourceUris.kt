package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.io.File
import java.io.FileNotFoundException

/**
 * The URI JavaFX Media opens for [source], checked as far as can be without JavaFX: a missing local
 * file or classpath resource fails here with [FileNotFoundException], and headers are rejected before
 * any connection is made.
 *
 * JavaFX has no asset directory of its own, so [VideoSource.Asset.path] is a classpath resource path —
 * what `src/main/resources/` or Compose's `jvmMain/resources` produce — resolved through
 * [classLoader]. A resource inside a jar comes back as a `jar:file:…!/…` URL, which JavaFX plays.
 */
internal fun resolveSourceUri(source: VideoSource, classLoader: ClassLoader): String = when (source) {
    is VideoSource.Asset -> {
        require(source.path.isNotBlank()) { "Asset path is blank" }
        val url: java.net.URL = classLoader.getResource(source.path.removePrefix("/"))
            ?: throw FileNotFoundException("No classpath resource '${source.path}'")
        url.toExternalForm()
    }

    is VideoSource.File -> {
        require(source.path.isNotBlank()) { "File path is blank" }
        val file = File(source.path)
        if (!file.isFile) throw FileNotFoundException(source.path)
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
