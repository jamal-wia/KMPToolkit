package io.github.jamal_wia.kmptoolkit.video.player.compose

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The shared controls suite, on Android through Robolectric. */
@RunWith(RobolectricTestRunner::class)
class VideoControlsAndroidTest : VideoControlsUiTests()

/** The shared player suite, on Android through Robolectric. */
@RunWith(RobolectricTestRunner::class)
class VideoPlayerAndroidTest : VideoPlayerUiTests()
