package io.github.jamal_wia.kmptoolkit.permission

import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController

/** [FakePermissionController] as a [FidelityWorld]. */
class FakeWorld(private val settleWith: suspend () -> Unit) : FidelityWorld {
    val fake: FakePermissionController = FakePermissionController()
    override val controller: PermissionController get() = fake

    override fun willAnswer(permission: Permission, grant: Boolean) {
        fake.scriptRequest(permission, if (grant) PermissionStatus.Granted else PermissionStatus.PermanentlyDenied)
    }

    override fun hold(on: Boolean) {
        fake.holdRequests = on
    }

    override fun answerHeld() {
        fake.holdRequests = false
    }

    override fun settingsOpenable(can: Boolean) {
        fake.settingsAvailable = can
    }

    override val settingsOpened: Int get() = fake.openedSettings.size

    override fun grantInSettings(permission: Permission) {
        fake.setStatus(permission, PermissionStatus.Granted)
    }

    override fun returnToApp() = Unit

    override suspend fun settle() = settleWith()
}
