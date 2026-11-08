package io.github.jamal_wia.kmptoolkit.video.player.compose

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The shared churn suite, on Android through Robolectric. */
@RunWith(RobolectricTestRunner::class)
class VideoPlayerChurnAdversarialAndroidTest : VideoPlayerChurnAdversarialTests()

/** The shared odd-picture-size suite, on Android through Robolectric. */
@RunWith(RobolectricTestRunner::class)
class OddPictureSizeAdversarialAndroidTest : OddPictureSizeAdversarialTests()
