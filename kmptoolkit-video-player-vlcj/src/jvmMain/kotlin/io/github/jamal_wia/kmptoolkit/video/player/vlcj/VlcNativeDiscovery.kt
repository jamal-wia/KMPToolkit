package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery

/**
 * Process-wide libvlc discovery. VLCJ's discovery mutates process state (`jna.library.path`, the
 * plugin path), so it runs under one lock, and a success is remembered: libvlc cannot be unloaded
 * from a JVM anyway.
 */
internal object VlcNativeDiscovery {

    private var found: Boolean = false

    @Synchronized
    fun discover(): Boolean {
        if (found) return true
        found = try {
            NativeDiscovery().discover()
        } catch (error: LinkageError) {
            // JNA itself failing to load, or libvlc loading but failing to link.
            false
        } catch (error: RuntimeException) {
            false
        }
        return found
    }
}
