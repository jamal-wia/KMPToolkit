package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeBackend
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativePlayer
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeSource
import java.util.concurrent.CopyOnWriteArrayList

/** The opaque handle [ScriptedBackend.probe] returns: the probed [uri]. */
internal class ScriptedNativeSource(val uri: String) : NativeSource {
    override fun toString(): String = "ScriptedNativeSource($uri)"
}

/**
 * The [NativeBackend] of the model tier: `probe` answers at once, `create` builds a [ScriptedNativePlayer] on the
 * model's [host]. Pass it to the engine as `JavaFxVideoEngine(runtime = {}, backend = { backend })` once the
 * engine takes a backend (J3).
 *
 * Every player gets its own [StatusScript.copy] of [script], taken when it is created, so changing [script]
 * later affects only the players created after it; a player's own script is `player.script`.
 *
 * THREADS: `probe` is called on an IO thread by `FxPlayback.open`, and `create` on the FX thread, so the records
 * of both are thread-safe lists. Only [probeFailure] is written by the test and read by `probe`, and is volatile.
 *
 * @param host the model's FX thread and clock.
 * @param clip the source every player pretends to play.
 * @param script the default behaviour of every player this backend creates.
 */
internal class ScriptedBackend(
    override val host: VirtualFxHost,
    val clip: ClipModel = ClipModel(),
    val script: StatusScript = StatusScript(),
) : NativeBackend {

    private val probedUris: MutableList<String> = CopyOnWriteArrayList()
    private val created: MutableList<ScriptedNativePlayer> = CopyOnWriteArrayList()

    /** When not null, `probe` throws it, as `Media(uri)` throws for a source it cannot open; nothing is recorded. */
    @Volatile
    var probeFailure: Throwable? = null

    /** The uris `probe` accepted, in order. */
    val probed: List<String> get() = probedUris.toList()

    /** Every player `create` built, in order. */
    val players: List<ScriptedNativePlayer> get() = created.toList()

    /** The newest player; fails when none was created yet. */
    val last: ScriptedNativePlayer
        get() = checkNotNull(created.lastOrNull()) { "no player was created yet ($host)" }

    override fun probe(uri: String): NativeSource {
        probeFailure?.let { throw it }
        probedUris += uri
        return ScriptedNativeSource(uri)
    }

    override fun create(source: NativeSource): NativePlayer {
        check(source is ScriptedNativeSource) { "A scripted backend builds players only of its own sources, was $source" }
        val player = ScriptedNativePlayer(host, clip, script.copy())
        created += player
        return player
    }
}
