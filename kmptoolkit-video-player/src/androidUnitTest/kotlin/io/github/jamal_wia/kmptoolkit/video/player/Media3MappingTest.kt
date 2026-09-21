package io.github.jamal_wia.kmptoolkit.video.player

import android.content.Context
import android.content.pm.PackageManager
import androidx.media3.common.C
import androidx.media3.common.util.Util
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import androidx.media3.common.VideoSize as Media3VideoSize

/**
 * The pure translations between this module's types and Media3's — source to URI and headers,
 * Media3's picture size to [VideoSize] — and the manifest the module ships.
 */
@RunWith(AndroidJUnit4::class)
class Media3MappingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // --- Source mapping ---

    @Test
    fun `an asset maps to an asset uri Media3 resolves under assets`() {
        assertEquals("asset:///videos/intro.mp4", VideoSource.Asset("videos/intro.mp4").toMediaUri().toString())
        assertEquals("/videos/intro.mp4", VideoSource.Asset("/videos/intro.mp4").toMediaUri().path)
    }

    @Test
    fun `an asset name with reserved characters survives the uri`() {
        val uri = VideoSource.Asset("clips/take #2?.mp4").toMediaUri()

        assertEquals("/clips/take #2?.mp4", uri.path)
        assertNull(uri.fragment)
        assertNull(uri.query)
    }

    @Test
    fun `a file maps to a file uri`() {
        val uri = VideoSource.File("/data/user/0/app/files/clip 1.mp4").toMediaUri()

        assertEquals("file", uri.scheme)
        assertEquals("/data/user/0/app/files/clip 1.mp4", uri.path)
    }

    @Test
    fun `a remote url is used as is and an m3u8 url is recognised as HLS`() {
        val remote = VideoSource.Remote("https://cdn.example.test/course/7/master.m3u8?token=abc")

        assertEquals("https://cdn.example.test/course/7/master.m3u8?token=abc", remote.toMediaUri().toString())
        assertEquals(C.CONTENT_TYPE_HLS, Util.inferContentType(remote.toMediaUri()))
    }

    @Test
    fun `only a remote source carries request headers`() {
        val headers: Map<String, String> = mapOf("Authorization" to "Bearer t0ken")

        assertEquals(headers, VideoSource.Remote("https://example.test/a.mp4", headers).requestHeaders())
        assertEquals(emptyMap(), VideoSource.Remote("https://example.test/a.mp4").requestHeaders())
        assertEquals(emptyMap(), VideoSource.File("/a.mp4").requestHeaders())
        assertEquals(emptyMap(), VideoSource.Asset("a.mp4").requestHeaders())
    }

    @Test
    fun `every source kind builds a Media3 source`() {
        // Building must not touch the network or the file system — that happens on the loader thread.
        defaultMediaSource(context, VideoSource.Asset("a.mp4"))
        defaultMediaSource(context, VideoSource.File("/nowhere/a.mp4"))
        defaultMediaSource(context, VideoSource.Remote("https://example.test/a.mp4", mapOf("X-Key" to "1")))
        defaultMediaSource(context, VideoSource.Remote("https://example.test/live/index.m3u8"))
    }

    // --- Picture size ---

    @Test
    fun `square pixels map straight through`() {
        assertEquals(VideoSize(1920, 1080), Media3VideoSize(1920, 1080).toVideoSizeOrNull())
    }

    @Test
    fun `the pixel aspect ratio widens the displayed picture`() {
        assertEquals(VideoSize(1440, 1080), Media3VideoSize(1080, 1080, 4f / 3f).toVideoSizeOrNull())
    }

    @Test
    fun `a zero dimension means no picture`() {
        assertNull(Media3VideoSize.UNKNOWN.toVideoSizeOrNull())
        assertNull(Media3VideoSize(0, 720).toVideoSizeOrNull())
        assertNull(Media3VideoSize(1280, 0).toVideoSizeOrNull())
    }

    @Test
    fun `a nonsensical pixel aspect ratio is treated as square`() {
        assertEquals(VideoSize(1280, 720), Media3VideoSize(1280, 720, 0f).toVideoSizeOrNull())
        assertEquals(VideoSize(1280, 720), Media3VideoSize(1280, 720, Float.NaN).toVideoSizeOrNull())
    }

    // --- Manifest ---

    @Test
    fun `the library declares no permission beyond what Media3 itself merges`() {
        val declared: List<String> = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toList()
            ?: emptyList()

        // media3-common and media3-exoplayer each ship these in their own manifests; see the module's
        // platform notes for how an app removes them. Anything else here came from this library.
        val fromMedia3: Set<String> = setOf(
            android.Manifest.permission.ACCESS_NETWORK_STATE,
            android.Manifest.permission.WAKE_LOCK,
        )
        val harness: (String) -> Boolean = { permission: String ->
            permission == android.Manifest.permission.REORDER_TASKS ||
                permission.endsWith(".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        }
        assertEquals(emptyList(), declared.filterNot { it in fromMedia3 || harness(it) })
        assertFalse(android.Manifest.permission.INTERNET in declared, "INTERNET is the app's to declare")
    }
}
