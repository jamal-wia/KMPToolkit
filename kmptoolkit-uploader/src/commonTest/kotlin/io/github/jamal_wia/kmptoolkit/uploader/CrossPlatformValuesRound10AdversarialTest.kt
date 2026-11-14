package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Round 10 (cross-platform differential): scripted values that Android (JVM) and iOS (Kotlin/Native) must compute
 * the same, written as one literal transcript. The test compares the transcript of the platform it runs on with the
 * committed one, so it is green on both platforms only when both give the same observable values.
 */
class CrossPlatformValuesRound10AdversarialTest {

    private fun fnv(values: List<Long>): Long {
        var hash: Long = -3750763034362895579L
        for (value in values) {
            for (shift in 0 until 8) {
                hash = hash xor ((value ushr (shift * 8)) and 0xFF)
                hash *= 1099511628211L
            }
        }
        return hash
    }

    private class Boom(message: String?) : Exception(message)

    private object Singleton : RuntimeException("object")

    private class Holder(val x: Int) : IllegalStateException("holder")

    @Suppress("unused")
    private class `Quoted Name`(message: String) : Exception(message)

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun `backoff curve over a grid of bases ceilings jitters and attempt counts is identical on both platforms`() {
        val bases: List<Long> = listOf(1L, 7L, 1000L, 86_400_000L, Long.MAX_VALUE / 3)
        val ceilings: List<Long> = listOf(1L, 1000L, 86_400_000L, Long.MAX_VALUE)
        val jitters: List<Double> = listOf(0.0, 0.2, 1.0)
        val attempts: List<Int> = listOf(Int.MIN_VALUE, -1, 0, 1, 2, 3, 10, 21, 22, 40, 62, 63, 64, 65, 1000, Int.MAX_VALUE)
        val lines: MutableList<String> = mutableListOf()
        for (base in bases) for (ceiling in ceilings) {
            if (ceiling < base) continue
            val values: MutableList<Long> = mutableListOf()
            for (jitter in jitters) {
                val policy = ExponentialBackoffRetryPolicy(base, ceiling, jitter, random = Random(1234))
                for (a in attempts) values += policy.backoffMillis(a)
            }
            lines += "backoff base=$base max=$ceiling ${fnv(values)}"
        }
        assertEquals(BACKOFF_GOLDEN.trim(), lines.joinToString("\n"), "transcript:\n" + lines.joinToString("\n"))
    }

    @Test
    fun `failure texts of exceptions of every shape read the same on both platforms`() {
        val anonymous: Throwable = object : IllegalArgumentException("anon") {}
        class Local(message: String) : Exception(message)
        val longMessage: String = "x".repeat(5000)
        val cases: List<Pair<String, Throwable?>> = listOf(
            "null" to null,
            "boom" to Boom("boom"),
            "boom-null" to Boom(null),
            "singleton" to Singleton,
            "holder" to Holder(1),
            "quoted" to `Quoted Name`("q"),
            "local" to Local("l"),
            "anonymous" to anonymous,
            "ise" to IllegalStateException("ise"),
            "unicode" to Boom("é😀\u0000"),
            "long" to Boom(longMessage),
            "pair-split" to Boom("x".repeat(4095) + "😀"),
        )
        val lines: List<String> = cases.map { (label, t) ->
            val text: String? = describeFailure(t)
            "$label -> ${text?.length}:${text?.take(40)?.map { it.code.toString(16) }?.joinToString(",") { it }?.hashCode()}:${text?.take(24)?.map { if (it.code < 0x20) "<${it.code}>" else it.toString() }?.joinToString("")}"
        }
        assertEquals(FAILURE_GOLDEN.trim(), lines.joinToString("\n"), "transcript:\n" + lines.joinToString("\n"))
    }

    @Test
    fun `header names of exotic alphabets lowercase and merge the same on both platforms`() {
        val names: List<String> = listOf(
            "Content-Type", "X-İstanbul", "ΣΊΣΥΦΟΣ", "ǅ", "ẞ", "K", "Å", "I", "İ",
            "Å", "Å", "ǈ", "ABCΣ",
        )
        val lines: MutableList<String> = mutableListOf()
        for (name in names) {
            val response = UploadResponse(200, mapOf(name to "v1", name.lowercase() to "v2"), ByteArray(0), false)
            lines += "${name.map { it.code.toString(16) }.joinToString(".")} -> " +
                response.headers.keys.joinToString("|") { key -> key.map { it.code.toString(16) }.joinToString(".") } +
                " values=" + response.headers.values.joinToString("|") + " lookup=" + response.header(name)
        }
        assertEquals(HEADER_GOLDEN.trim(), lines.joinToString("\n"), "transcript:\n" + lines.joinToString("\n"))
    }

    @Test
    fun `journal bytes of a fixed answer are identical on both platforms`() {
        val responses: List<UploadResponse> = listOf(
            UploadResponse(200, emptyMap(), ByteArray(0), false),
            UploadResponse(413, mapOf("Content-Type" to "text/plain", "X-A" to "café 😀"), byteArrayOf(0, 1, 2, -1, -128), true),
            UploadResponse(599, mapOf("é" to "日本語"), ByteArray(300) { it.toByte() }, false),
        )
        val lines: List<String> = responses.flatMap { response ->
            listOf<Int?>(null, 0, 7, Int.MAX_VALUE).map { stamp ->
                val encoded: ByteArray = OutcomeCodec.encode(response, 1_700_000_000_123L, stamp)
                "${response.statusCode}/$stamp -> ${encoded.size}:${hex(encoded).hashCode()}"
            }
        }
        assertEquals(CODEC_GOLDEN.trim(), lines.joinToString("\n"), "transcript:\n" + lines.joinToString("\n"))
    }

    private companion object {
        val BACKOFF_GOLDEN: String = """
backoff base=1 max=1 -1727175877671600347
backoff base=1 max=1000 8869084008120681364
backoff base=1 max=86400000 -9114965505299379724
backoff base=1 max=9223372036854775807 -3909220557380729966
backoff base=7 max=1000 -411786725838944639
backoff base=7 max=86400000 4268723325299447798
backoff base=7 max=9223372036854775807 -4840405480946838810
backoff base=1000 max=1000 1482704569430898925
backoff base=1000 max=86400000 1877203198752109255
backoff base=1000 max=9223372036854775807 -321326622974173635
backoff base=86400000 max=86400000 -3532248822918675444
backoff base=86400000 max=9223372036854775807 -2407327923661729611
backoff base=3074457345618258602 max=9223372036854775807 6035808199526501783
"""
        val FAILURE_GOLDEN: String = """
null -> null:null:null
boom -> 10:372468222:Boom: boom
boom-null -> 10:144927611:Boom: null
singleton -> 17:1646802019:Singleton: object
holder -> 14:609870672:Holder: holder
quoted -> 14:-1265184117:Quoted Name: q
local -> 8:1150894299:Local: l
anonymous -> 15:-617400514:Throwable: anon
ise -> 26:1918939787:IllegalStateException: i
unicode -> 10:-2138513324:Boom: é😀<0>
long -> 4119:-986357972:Boom: xxxxxxxxxxxxxxxxxx
pair-split -> 4117:-986357972:Boom: xxxxxxxxxxxxxxxxxx
"""
        val HEADER_GOLDEN: String = """
43.6f.6e.74.65.6e.74.2d.54.79.70.65 -> 63.6f.6e.74.65.6e.74.2d.74.79.70.65 values=v1, v2 lookup=v1, v2
58.2d.130.73.74.61.6e.62.75.6c -> 78.2d.69.307.73.74.61.6e.62.75.6c values=v1, v2 lookup=v1, v2
3a3.38a.3a3.3a5.3a6.39f.3a3 -> 3c3.3af.3c3.3c5.3c6.3bf.3c2 values=v1, v2 lookup=v1, v2
1c5 -> 1c6 values=v1, v2 lookup=v1, v2
1e9e -> df values=v1, v2 lookup=v1, v2
212a -> 6b values=v1, v2 lookup=v1, v2
212b -> e5 values=v1, v2 lookup=v1, v2
49 -> 69 values=v1, v2 lookup=v1, v2
49.307 -> 69.307 values=v1, v2 lookup=v1, v2
c5 -> e5 values=v1, v2 lookup=v1, v2
41.30a -> 61.30a values=v1, v2 lookup=v1, v2
1c8 -> 1c9 values=v1, v2 lookup=v1, v2
41.42.43.3a3 -> 61.62.63.3c2 values=v1, v2 lookup=v1, v2
"""
        val CODEC_GOLDEN: String = """
200/null -> 25:1267729089
200/0 -> 29:160950146
200/7 -> 29:-1455385655
200/2147483647 -> 29:235986897
413/null -> 81:-436046757
413/0 -> 85:929555484
413/7 -> 85:-977498525
413/2147483647 -> 85:1470982763
599/null -> 344:-1794485439
599/0 -> 348:-247478974
599/7 -> 348:-547379383
599/2147483647 -> 348:-807873199
"""
    }
}
