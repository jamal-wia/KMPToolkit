package io.github.jamal_wia.kmptoolkit.permission

/**
 * A runtime permission this module knows how to check, request, and reason about on **both**
 * platforms.
 *
 * The catalog is closed on purpose, and it is short on purpose. Every entry here maps to exactly
 * one Android permission string and one iOS authorization API whose "granted / denied / not yet
 * asked" shape matches [PermissionStatus] without distortion, and every mapping is exercised by a
 * test. A larger enum would be easy to write and impossible to stand behind: the value of this
 * module is not the mapping table, it is the denial bookkeeping and the state machine built on top
 * of it, and both give wrong answers for a permission whose platform semantics do not fit.
 *
 * Two permissions were deliberately left out rather than added as scaffolding, and both are
 * examples of that mismatch — see `docs/kmptoolkit-permission/05-platform-notes.md`:
 *
 * - **Location.** iOS grants it through `CLLocationManager`'s *delegate*, asynchronously and
 *   possibly much later than the call that asked; there is also a "while in use" / "always" pair
 *   and a provisional grant that [PermissionStatus] has no room for. Android additionally requires
 *   fine and coarse to be requested in one dialog for the Precise/Approximate toggle to render.
 * - **Photo library.** iOS has a third granted-ish state (`Limited` — the user picked specific
 *   photos) that is neither granted nor denied, and Android's string depends on the API level and
 *   splits per media type.
 *
 * If you need one of those, call the platform API in platform code. Ask for it to be added here
 * only alongside a contract that can express it.
 */
public enum class Permission {

    /**
     * Post notifications.
     *
     * - Android: `POST_NOTIFICATIONS`, which is a runtime permission only from API 33 (Tiramisu).
     *   Below that it is reported as granted, because there is no runtime grant to obtain — see
     *   `docs/kmptoolkit-permission/05-platform-notes.md` for why "granted" there does not mean
     *   the user has notifications switched on.
     * - iOS: `UNUserNotificationCenter`, alert + badge + sound.
     */
    NOTIFICATIONS,

    /**
     * Capture audio from the microphone.
     *
     * - Android: `RECORD_AUDIO`.
     * - iOS: `AVAudioSession`'s record permission.
     */
    MICROPHONE,

    /**
     * Capture video from the camera.
     *
     * - Android: `CAMERA`.
     * - iOS: `AVCaptureDevice` authorization for `AVMediaTypeVideo`.
     */
    CAMERA,
}
