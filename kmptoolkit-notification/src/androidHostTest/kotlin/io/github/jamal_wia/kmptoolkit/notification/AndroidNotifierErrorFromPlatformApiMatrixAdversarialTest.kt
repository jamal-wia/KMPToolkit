package io.github.jamal_wia.kmptoolkit.notification

import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config

/**
 * Round 15: the Error-from-the-platform tests at the API levels where the post path differs: 24 (no
 * channels, no permission), 29 and 33 (the runtime permission is asked from here).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 29, 33], shadows = [AndroidNotifierErrorFromPlatformAdversarialTest.ErrorShadowNotificationManager::class])
class AndroidNotifierErrorFromPlatformApiMatrixAdversarialTest : AndroidNotifierErrorFromPlatformAdversarialTest()
