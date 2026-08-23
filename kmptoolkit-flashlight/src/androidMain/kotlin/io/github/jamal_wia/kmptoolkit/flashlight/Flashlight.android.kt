package io.github.jamal_wia.kmptoolkit.flashlight

import android.content.Context

/**
 * Creates the Android [Flashlight], backed by [android.hardware.camera2.CameraManager]'s torch
 * mode.
 *
 * Call this once — in your `Application`, or wherever you assemble dependencies — and pass the
 * resulting [Flashlight] into shared code. The instance holds only the framework `CameraManager`
 * obtained from [context]; nothing needs releasing, and it does not keep a strong reference to an
 * `Activity` (the application context is used, so passing an `Activity` here is harmless).
 *
 * No manifest entry is required — see `docs/kmptoolkit-flashlight/05-platform-notes.md`.
 *
 * @param context any `Context`; its application context is what gets retained.
 */
public fun createFlashlight(context: Context): Flashlight = AndroidFlashlight(context)
