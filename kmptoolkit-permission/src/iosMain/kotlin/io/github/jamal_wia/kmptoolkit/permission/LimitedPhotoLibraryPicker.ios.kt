package io.github.jamal_wia.kmptoolkit.permission

import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Photos.PHAccessLevelReadWrite
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHPhotoLibrary
import platform.PhotosUI.presentLimitedLibraryPickerFromViewController
import platform.UIKit.UIViewController

/**
 * Lets the user change which photos a [PermissionStatus.Limited] [CommonPermission.PhotoLibrary] grant
 * covers: presents the system's limited-library picker from [from], and suspends until it is dismissed.
 *
 * iOS shows the access dialog only once, so a request from a limited library returns at once; this is
 * the iOS way to widen or narrow the selection. Android needs nothing like it — a request from a
 * limited grant shows the dialog again there — which is why this is not part of [PermissionController].
 *
 * @return `false`, showing nothing, when the photo library is not limited; `true` once the picker
 *   has been dismissed.
 */
public suspend fun presentLimitedPhotoLibraryPicker(from: UIViewController): Boolean {
    if (PhotoLibraryAuthorization.current(PHAccessLevelReadWrite) != PHAuthorizationStatusLimited) return false
    withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            PHPhotoLibrary.sharedPhotoLibrary().presentLimitedLibraryPickerFromViewController(from) { _: List<*>? ->
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }
    return true
}
