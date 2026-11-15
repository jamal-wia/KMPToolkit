package io.github.jamal_wia.kmptoolkit.permission

import platform.CoreLocation.CLAuthorizationStatus

/**
 * Asks iOS for full location accuracy until the app's session ends, under [purposeKey] — a key of the
 * `NSLocationTemporaryUsageDescriptionDictionary` entry of `Info.plist`, whose value is the text iOS shows.
 *
 * The key is validated first, before the status is read: a key missing from `Info.plist` throws
 * [IllegalStateException] naming it, in every status, because iOS terminates the app that passes it.
 * Then the current [CommonPermission.Location] status is returned without a prompt unless it is
 * [PermissionStatus.Limited] (the user chose approximate location). From there iOS shows its prompt, and
 * the result is [PermissionStatus.Granted] while full accuracy lasts, or [PermissionStatus.Limited] when the
 * user declined. Nothing is remembered: the next launch, or the next time the app leaves the foreground for
 * long enough, accuracy is reduced again and [PermissionStatus.Limited] is what a read answers.
 *
 * This is the iOS counterpart of the precise-location upgrade Android offers through
 * [CommonPermission.Location]; with [IosPermissionOptions.temporaryFullAccuracyPurposeKey] set,
 * [PermissionController.request] of [CommonPermission.Location] does the same from shared code.
 *
 * Like every status read of this controller on iOS the status here comes from the system alone; whether
 * `NSLocationWhenInUseUsageDescription` is present is not looked at, because nothing can be granted
 * without it and the status then stays [PermissionStatus.NotDetermined].
 *
 * @param purposeKey a key of `NSLocationTemporaryUsageDescriptionDictionary`.
 * @throws IllegalStateException when [purposeKey] is not a key of the app's `Info.plist` dictionary.
 */
public suspend fun requestTemporaryFullAccuracy(purposeKey: String): PermissionStatus =
    requestTemporaryFullAccuracy(SystemIosAuthorizations, purposeKey)

/** [requestTemporaryFullAccuracy] through [port]: the seam the tests drive with a scripted fake. */
internal suspend fun requestTemporaryFullAccuracy(port: IosAuthorizationPort, purposeKey: String): PermissionStatus {
    requireTemporaryPurposeKey(port, purposeKey)
    val before: PermissionStatus = locationStatus(port)
    if (before !is PermissionStatus.Limited) return before
    port.requestTemporaryFullAccuracy(purposeKey)
    // Re-read rather than trusting the completion: it carries an error for a decline, not the new accuracy.
    return locationStatus(port)
}

/** Throws [IllegalStateException] naming [purposeKey] when the port's bundle has no such key. */
internal fun requireTemporaryPurposeKey(port: IosAuthorizationPort, purposeKey: String) {
    check(purposeKey in port.temporaryUsagePurposeKeys()) {
        "\"$purposeKey\" is not a key of NSLocationTemporaryUsageDescriptionDictionary in Info.plist: " +
            "add it there, or iOS terminates the app that asks for temporary full accuracy under it"
    }
}

private fun locationStatus(port: IosAuthorizationPort): PermissionStatus {
    val authorization: CLAuthorizationStatus = port.locationAuthorization()
    return foregroundLocationStatus(authorization, port.isLocationAccuracyReduced())
}
