package io.github.jamal_wia.kmptoolkit.downloader

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256_Final
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update

@OptIn(ExperimentalForeignApi::class)
internal actual fun sha256Digest(bytes: ByteArray): ByteArray = memScoped {
    val context: CC_SHA256_CTX = alloc()
    CC_SHA256_Init(context.ptr)
    // addressOf(0) is not valid for an empty array, and there is nothing to feed in that case.
    if (bytes.isNotEmpty()) {
        bytes.usePinned { pinned -> CC_SHA256_Update(context.ptr, pinned.addressOf(0), bytes.size.convert()) }
    }
    val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
    digest.usePinned { pinned -> CC_SHA256_Final(pinned.addressOf(0), context.ptr) }
    ByteArray(CC_SHA256_DIGEST_LENGTH) { index: Int -> digest[index].toByte() }
}
