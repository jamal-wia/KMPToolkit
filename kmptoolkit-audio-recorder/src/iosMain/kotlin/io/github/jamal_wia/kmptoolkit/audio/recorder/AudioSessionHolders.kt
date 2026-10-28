package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.Foundation.NSError
import platform.Foundation.NSLock

/**
 * The two things this module does to `AVAudioSession`, behind a seam: the session is process-wide
 * state that a Kotlin/Native test cannot drive, so [AudioSessionHolders] is tested against a fake.
 */
internal interface AudioSessionControl {

    /** Sets the category and activates the session; throws when the system refuses either. */
    fun activate()

    /** Deactivates the session, letting other apps' audio resume. Never throws. */
    fun deactivate()
}

/** The real `AVAudioSession.sharedInstance()`. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object PlatformAudioSession : AudioSessionControl {

    override fun activate() {
        val session: AVAudioSession = AVAudioSession.sharedInstance()
        // The NSError of both calls is captured and thrown: a category or activation the system
        // refuses (another app holding the session, a restricted device) is a PREPARE failure with
        // its own cause, not a mystery `record()` failure later on.
        memScoped {
            val errorRef = alloc<ObjCObjectVar<NSError?>>()
            val categorySet: Boolean = session.setCategory(
                category = AVAudioSessionCategoryPlayAndRecord,
                mode = AVAudioSessionModeDefault,
                options = 0u,
                error = errorRef.ptr,
            )
            if (!categorySet || errorRef.value != null) {
                error("AVAudioSession could not be configured: ${errorRef.value?.localizedDescription}")
            }
            val activated: Boolean = session.setActive(true, error = errorRef.ptr)
            if (!activated || errorRef.value != null) {
                error("AVAudioSession could not be activated: ${errorRef.value?.localizedDescription}")
            }
        }
    }

    override fun deactivate() {
        // NotifyOthersOnDeactivation: without it a backgrounded music app stays paused until it
        // notices on its own, instead of resuming as soon as the microphone is free.
        AVAudioSession.sharedInstance().setActive(
            active = false,
            withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,
            error = null,
        )
    }
}

/**
 * How many engines in the process currently hold the audio session active.
 *
 * `AVAudioSession` is one per process, but every [AvAudioRecorderEngine] activates it in `prepare`
 * and deactivates it in `release`. Done per engine, one recorder's `release()` — or merely a second
 * recorder's failed `prepare()`, which releases on its way out — deactivated the session under
 * another recorder that was still recording, and iOS stops every running audio object of a session
 * it deactivates. So the session is deactivated only when the last holder lets go.
 *
 * Every [acquire] still activates, not only the first: the system may have deactivated the session
 * meanwhile (an interruption ends a recording without its engine having been released yet), and a
 * recorder preparing after that needs the session active again. Activating an active session with
 * the same category is a no-op. A failed activation does not count as a hold.
 *
 * Guarded by an `NSLock`, not a coroutine `Mutex`: both callers are synchronous — `release` is part
 * of the non-suspending [RecorderEngine] contract and runs under the recorder's own state-machine
 * lock — and the lock is held across the platform call on purpose, so a last release deactivating
 * cannot interleave with another engine's acquire and deactivate the session that engine was just
 * given. Nothing called under it calls back into a recorder, so it cannot take part in a lock cycle.
 */
internal class AudioSessionHolders(private val control: AudioSessionControl) {

    private val lock: NSLock = NSLock()

    // Guarded by `lock`.
    private var holders: Int = 0

    /** How many holds are outstanding, for the module's own tests. */
    internal val count: Int get() = locked { holders }

    /** Activates the session and counts a hold; throws, counting nothing, when activation fails. */
    fun acquire() {
        locked {
            control.activate()
            holders++
        }
    }

    /** Gives back one hold taken by [acquire]; the last one deactivates the session. */
    fun release() {
        locked {
            // Each engine gives back only a hold it took, so this cannot be reached; but release()
            // runs on cleanup paths that must not throw, so an unbalanced call is ignored rather
            // than allowed to fail one.
            if (holders == 0) return@locked
            holders--
            if (holders == 0) control.deactivate()
        }
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    companion object {
        /** The process-wide count every engine the factory creates shares. */
        val Shared: AudioSessionHolders = AudioSessionHolders(PlatformAudioSession)
    }
}
