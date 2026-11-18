package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

/**
 * Checked-in golden files of format v1, one per kind (plan T1): the bytes below were written once by the v1 writer and
 * must keep opening forever. Each holds the same three entries under the same data key (bytes 1..32):
 * `alpha` = `one`, `beta` = empty, `\u00fcn\u00ef` = a smiling-face emoji, a space and `value`.
 * Kind 1 header: fingerprint 0xA0..0xAF, macOS tag. Kind 2: a 40-byte blob 0x30... . Kind 3: passphrase
 * `p\u00e4ssw\u00f6rd` in NFC, salt 0xC0..0xCF, 600,000 iterations. Kind 4: raw key of 32 bytes 0x55.
 * If a change makes one of these fail to open, the change broke format v1: fix the code, never the golden.
 */
internal object GoldenStoreFiles {
    val DATA_KEY: ByteArray = ByteArray(32) { (it + 1).toByte() }

    val KIND_1_OS_ITEM: ByteArray = hex(
        "4b54535301010011a0a1a2a3a4a5a6a7a8a9aaabacadaeaf01d50ac288b90ede2e311b0237c93ebd8bda74095ee2004c8016",
        "799541000000030005616c7068616bca7c702fb62a27ba1a100e00000013851bc86325ee5f12275dcf052ddc1a15b5237400",
        "0462657461cb1e7ec5d79bbdbd038e19c000000010e1921a1a3e5d4126718793423d50f0ac0005c3bc6ec3af67787310b2f7",
        "e00c11e1dbe50000001a2474c89f121108f4690426627e73b391c264c50ec3cc48189f82",
    )

    val KIND_2_DPAPI: ByteArray = hex(
        "4b5453530102002a0028303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f5051525354555657",
        "7eaf77b1212cf30e4fef9470b6c404bcd25351beb51bae7da96dd36c000000030005616c706861f96fa6a2163d364c17a4c7",
        "7200000013e37057415c4be1d61bb909a979dcf7d6bbedf0000462657461018fc1d122bbd3d09f2504eb0000001008dba792",
        "26cc4f196c066e1b48bdfab70005c3bc6ec3afc52bd745088a8ff006e90b030000001a9faa9a650d03f1f58d4dc9c95c1507",
        "cb3aeacb4bc1804358a9f4",
    )

    val KIND_3_PASSPHRASE: ByteArray = hex(
        "4b54535301030050c0c1c2c3c4c5c6c7c8c9cacbcccdcecf000927c0a3b5c2b7325c757ca8d4388d3d7e1289c1d1fa2e8eae",
        "667321e5a11c7f9ce75f62a699e8761d0ab075e669a413903a7377d517f5b79a771fa2b202f8670c07bdb9c1d09a723a901e",
        "db7e1c75362daf85efe9810d511876bb000000030005616c706861415f2606b4a6cf8fc137f4b3000000133dfa4e25629d49",
        "48fdc4a4dff91690bd034c09000462657461ef8de5ed46d03f2e6d98ef5d0000001003009d981bd44341b44ca30c692a65a1",
        "0005c3bc6ec3aff4a2475ca92a88fe68e1b2410000001a3572efbd04fc028c7a55ed95ff860695732ef0d07350f50d543c",
    )

    val KIND_4_RAW_KEY: ByteArray = hex(
        "4b5453530104003c70dec61aef19998d83adf835133fefc86ee812fd26aebb97460cf2e83f5b78e855bab0207f1a4a14194f",
        "c35e99401f739f2a35b11e7647ad1f48fa85e60625e0d92d1b4508d872cb16c5c51e46d441b9e575ae5a396e3f9100000003",
        "0005616c7068611aa879e3922b1e603e890c77000000132f79082dce81c8fdd1f428be6f2fa06ee20dfe000462657461b1c0",
        "753768cc0d5b97af98dd00000010d5afae02d4fe543b72a8076c84313dd80005c3bc6ec3afa3e7c044d8f2ba34a101c80700",
        "00001a294d42dfb3632ef86a10bb8f236dd517bf558e03875e402ae31f",
    )

    val RAW_KEK: ByteArray = ByteArray(32) { 0x55 }
    val FINGERPRINT: ByteArray = ByteArray(16) { (0xA0 + it).toByte() }
    val DPAPI_BLOB: ByteArray = ByteArray(40) { (0x30 + it).toByte() }
    val SALT: ByteArray = ByteArray(16) { (0xC0 + it).toByte() }
    const val PASSPHRASE_NFC: String = "p\u00e4ssw\u00f6rd"
    const val PASSPHRASE_NFD: String = "pa\u0308sswo\u0308rd"

    val ENTRIES: Map<String, ByteArray> = mapOf(
        "alpha" to "one".toByteArray(),
        "beta" to ByteArray(0),
        "\u00fcn\u00ef" to "\uD83D\uDE00 value".toByteArray(),
    )

    private fun hex(vararg parts: String): ByteArray {
        val text: String = parts.joinToString("")
        return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
