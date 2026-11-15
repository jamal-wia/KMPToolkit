package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * A macOS-shaped fake keychain whose items are files in [directory], so several real processes share one "OS key store" in the
 * forked tests (an in-memory fake lives and dies with one process). Not a model of atomicity: it relies, exactly like the real
 * keychain calls do, on the caller serializing creation, which is what the store's locks are for. Used only by test children.
 */
internal class DirectoryKeychainBinding(private val directory: File) : NativeKeyStoreBinding {

    private fun itemFile(service: String, account: String): File {
        val name: String = MessageDigest.getInstance("SHA-256").digest("$service\u0000$account".toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, "item-$name")
    }

    private val mac: MacKeychainCalls = object : MacKeychainCalls {
        override fun copyGenericPassword(service: String, account: String, allowUi: Boolean): RawResult<ByteArray?> {
            val file: File = itemFile(service, account)
            return if (file.isFile) RawResult.Success(file.readBytes()) else RawResult.Failure(RawStatus(-25300))
        }

        override fun addGenericPassword(service: String, account: String, label: String, secret: ByteArray, allowUi: Boolean): Int {
            val file: File = itemFile(service, account)
            if (file.exists()) return -25299
            val temp: File = File(directory, file.name + ".part")
            Files.write(temp.toPath(), secret, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return 0
        }

        override fun deleteGenericPassword(service: String, account: String, allowUi: Boolean): Int =
            if (Files.deleteIfExists(itemFile(service, account).toPath())) 0 else -25300

        override fun probe(allowUi: Boolean): Int = -25300
    }

    override fun macOs(): MacKeychainCalls = mac

    override fun windows(): DpapiCalls = error("not used")

    override fun linux(): SecretServiceCalls = error("not used")

    /** How many items the keychain holds. */
    fun itemCount(): Int = directory.list { _, name -> name.startsWith("item-") && !name.endsWith(".part") }?.size ?: 0
}
