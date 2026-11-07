package io.github.jamal_wia.kmptoolkit.downloader

/**
 * SHA-256 of [bytes], the raw 32-byte digest. Used only to derive a short, file-name-safe key from
 * a unit id (see [UnitFileLayout]); hashing a file is done by each storage in a streaming loop.
 */
internal expect fun sha256Digest(bytes: ByteArray): ByteArray
