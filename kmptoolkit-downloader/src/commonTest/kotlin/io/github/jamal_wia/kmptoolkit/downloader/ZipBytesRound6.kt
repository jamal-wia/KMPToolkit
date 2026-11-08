package io.github.jamal_wia.kmptoolkit.downloader

/** CRC-32 (IEEE), pure Kotlin so a common test can lay out a ZIP byte by byte. */
internal object Crc32Round6 {
    private val table: IntArray = IntArray(256) { index: Int ->
        var c: Int = index
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        c
    }

    fun of(data: ByteArray): Long {
        var c: Int = -1
        for (b: Byte in data) c = table[(c xor b.toInt()) and 0xFF] xor (c ushr 8)
        return c.inv().toLong() and 0xFFFFFFFFL
    }
}

/** How a streamed entry's data descriptor is laid out. */
internal enum class DescriptorRound6 { None, WithSignature, WithoutSignature }

/** One entry for [ZipBytesRound6.build]; every field that a real writer sets can be overridden to build a defect. */
internal class ZipEntryRound6(
    val name: ByteArray,
    val data: ByteArray,
    val deflated: Boolean = false,
    val descriptor: DescriptorRound6 = DescriptorRound6.None,
    val extraFlags: Int = 0,
    val extra: ByteArray = ByteArray(0),
    /** Replaces the compressed bytes; by default a DEFLATED entry holds one raw stored block of [data]. */
    val payloadOverride: ByteArray? = null,
    /** Written into the local header instead of the real value (only meaningful without a descriptor). */
    val headerCrc: Long? = null,
    val headerCompressedSize: Long? = null,
    val headerSize: Long? = null,
    /** Written into the descriptor instead of the real values. */
    val descriptorCrc: Long? = null,
    val descriptorCompressedSize: Long? = null,
    val descriptorSize: Long? = null,
    /** Set bit 3 but write the real sizes into the local header too (a writer that does both). */
    val sizesInHeaderDespiteDescriptor: Boolean = false,
) {
    constructor(name: String, data: String) : this(name.encodeToByteArray(), data.encodeToByteArray())
}

internal class BuiltZipRound6(
    val bytes: ByteArray,
    /** Offset after each entry (its data and descriptor). */
    val entryEnds: List<Int>,
    val centralDirectoryStart: Int,
    val endRecordStart: Int,
)

internal object ZipBytesRound6 {

    /** A raw deflate stream holding [data] in stored blocks (no compression needed, no zlib in common code). */
    fun rawDeflateStored(data: ByteArray): ByteArray {
        val out: Sink = Sink()
        if (data.isEmpty()) {
            out.u8(0x01); out.u16(0); out.u16(0xFFFF)
            return out.toByteArray()
        }
        var offset = 0
        while (offset < data.size) {
            val n: Int = minOf(65535, data.size - offset)
            val last: Boolean = offset + n == data.size
            out.u8(if (last) 0x01 else 0x00)
            out.u16(n)
            out.u16(n.inv() and 0xFFFF)
            out.bytes(data, offset, n)
            offset += n
        }
        return out.toByteArray()
    }

    fun build(
        entries: List<ZipEntryRound6>,
        comment: ByteArray = ByteArray(0),
        withCentralDirectory: Boolean = true,
        trailing: ByteArray = ByteArray(0),
    ): BuiltZipRound6 {
        val out: Sink = Sink()
        val central: Sink = Sink()
        val ends: ArrayList<Int> = ArrayList()
        for (entry: ZipEntryRound6 in entries) {
            val localOffset: Int = out.size
            val payload: ByteArray = entry.payloadOverride
                ?: if (entry.deflated) rawDeflateStored(entry.data) else entry.data
            val crc: Long = Crc32Round6.of(entry.data)
            val hasDescriptor: Boolean = entry.descriptor != DescriptorRound6.None
            val flags: Int = entry.extraFlags or (if (hasDescriptor) 0x08 else 0)
            val method: Int = if (entry.deflated) 8 else 0
            val zeroHeader: Boolean = hasDescriptor && !entry.sizesInHeaderDespiteDescriptor
            out.u32(0x04034B50L)
            out.u16(20)
            out.u16(flags)
            out.u16(method)
            out.u16(0); out.u16(0x21)
            out.u32(if (zeroHeader) 0 else entry.headerCrc ?: crc)
            out.u32(if (zeroHeader) 0 else entry.headerCompressedSize ?: payload.size.toLong())
            out.u32(if (zeroHeader) 0 else entry.headerSize ?: entry.data.size.toLong())
            out.u16(entry.name.size)
            out.u16(entry.extra.size)
            out.bytes(entry.name)
            out.bytes(entry.extra)
            out.bytes(payload)
            when (entry.descriptor) {
                DescriptorRound6.None -> Unit
                DescriptorRound6.WithSignature, DescriptorRound6.WithoutSignature -> {
                    if (entry.descriptor == DescriptorRound6.WithSignature) out.u32(0x08074B50L)
                    out.u32(entry.descriptorCrc ?: crc)
                    out.u32(entry.descriptorCompressedSize ?: payload.size.toLong())
                    out.u32(entry.descriptorSize ?: entry.data.size.toLong())
                }
            }
            ends += out.size

            central.u32(0x02014B50L)
            central.u16(20); central.u16(20)
            central.u16(flags)
            central.u16(method)
            central.u16(0); central.u16(0x21)
            central.u32(crc)
            central.u32(payload.size.toLong())
            central.u32(entry.data.size.toLong())
            central.u16(entry.name.size)
            central.u16(0); central.u16(0)
            central.u16(0); central.u16(0)
            central.u32(0)
            central.u32(localOffset.toLong())
            central.bytes(entry.name)
        }
        val cdStart: Int = out.size
        if (withCentralDirectory) out.bytes(central.toByteArray())
        val endStart: Int = out.size
        if (withCentralDirectory || comment.isNotEmpty() || entries.isEmpty()) {
            out.u32(0x06054B50L)
            out.u16(0); out.u16(0)
            val count: Int = if (withCentralDirectory) entries.size else 0
            out.u16(count); out.u16(count)
            out.u32(if (withCentralDirectory) central.size.toLong() else 0)
            out.u32(cdStart.toLong())
            out.u16(comment.size)
            out.bytes(comment)
        }
        out.bytes(trailing)
        return BuiltZipRound6(out.toByteArray(), ends, cdStart, endStart)
    }

    class Sink {
        private var buffer: ByteArray = ByteArray(512)
        var size: Int = 0
            private set

        private fun ensure(extra: Int) {
            if (size + extra > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + extra))
        }

        fun u8(value: Int) {
            ensure(1); buffer[size++] = value.toByte()
        }

        fun u16(value: Int) {
            u8(value and 0xFF); u8((value ushr 8) and 0xFF)
        }

        fun u32(value: Long) {
            u16((value and 0xFFFF).toInt()); u16(((value ushr 16) and 0xFFFF).toInt())
        }

        fun bytes(data: ByteArray, offset: Int = 0, count: Int = data.size) {
            ensure(count)
            data.copyInto(buffer, size, offset, offset + count)
            size += count
        }

        fun toByteArray(): ByteArray = buffer.copyOf(size)
    }
}
