package io.github.jamal_wia.kmptoolkit.downloader.testing

import io.github.jamal_wia.kmptoolkit.downloader.DownloadUnit
import io.github.jamal_wia.kmptoolkit.downloader.ResourceGroup
import io.github.jamal_wia.kmptoolkit.downloader.Sha256
import io.github.jamal_wia.kmptoolkit.downloader.TempFileState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/** Test unit of the parity driver; [sha256] set makes the unit's own hash part of the begin contract. */
internal class ParityUnitRound12(override val id: String, override val sha256: Sha256?) : DownloadUnit {
    override val apiPath: String = "test/$id"
    override val relativePath: String = "parity/$id.bin"
    override val group: ResourceGroup = object : ResourceGroup {
        override val key: String = "g"
        override val units: List<DownloadUnit> get() = emptyList()
    }

    override fun toString(): String = "ParityUnit($id)"
}

/** What a storage must do for the driver: the DownloaderStorage calls plus the one thing a downloader port does, writing the bytes. */
internal interface ParitySubjectRound12 {
    fun begin(unit: DownloadUnit, expected: Sha256?)

    /** Puts the whole payload into the temp file (the real storage: bytes at the temp path; the fake: state Partial). */
    fun write(unit: DownloadUnit)
    fun mark(unit: DownloadUnit)
    suspend fun commit(unit: DownloadUnit)
    fun deleteTemp(unit: DownloadUnit)
    fun deleteResource(unit: DownloadUnit)
    fun available(unit: DownloadUnit): Boolean
    fun state(unit: DownloadUnit): TempFileState
}

/**
 * The documented contract of the storage calls over one unit as a state machine (DownloaderStorage KDoc, 04-api-reference):
 * begin replaces any transfer and throws IllegalArgumentException for a unit hash and an expected hash that differ; mark moves
 * Partial to Complete, keeps Complete and throws IllegalStateException with no temp file; commit of a Complete temp file makes the
 * unit available and consumes the temp file, commit with none is a no-op for an available unit and IllegalStateException otherwise;
 * deleteTemp and deleteResource never throw.
 */
internal class ParityModelRound12 {
    var available: Boolean = false
    var temp: TempFileState = TempFileState.None
    var begun: Boolean = false
}

internal class ParityDriverRound12(private val subject: ParitySubjectRound12, private val goodHash: Sha256, private val otherHash: Sha256) {

    private fun outcome(block: () -> Unit): String = try {
        block()
        "ok"
    } catch (e: IllegalStateException) {
        "IllegalStateException"
    } catch (e: IllegalArgumentException) {
        "IllegalArgumentException"
    } catch (e: Throwable) {
        e::class.simpleName ?: "?"
    }

    suspend fun run(seed: Int, steps: Int) {
        val random = Random(seed)
        val units: List<ParityUnitRound12> = listOf(
            ParityUnitRound12("p0", null),
            ParityUnitRound12("p1", null),
            ParityUnitRound12("p2", goodHash),
        )
        val models: Map<String, ParityModelRound12> = units.associate { it.id to ParityModelRound12() }
        val trace: ArrayList<String> = ArrayList()
        repeat(steps) { step: Int ->
            val unit: ParityUnitRound12 = units[random.nextInt(units.size)]
            val model: ParityModelRound12 = models.getValue(unit.id)
            val op: Int = random.nextInt(100)
            val expected: String
            val actual: String
            val name: String
            when {
                op < 20 -> {
                    // begin: with no hash, with the right one, and (only for the unit that has its own hash) with the wrong one
                    val choice: Int = random.nextInt(if (unit.sha256 != null) 3 else 2)
                    val given: Sha256? = when (choice) { 0 -> null; 1 -> goodHash; else -> otherHash }
                    name = "begin(${if (choice == 0) "null" else if (choice == 1) "good" else "other"})"
                    expected = if (unit.sha256 != null && given != null && given != unit.sha256) {
                        "IllegalArgumentException"
                    } else {
                        model.temp = TempFileState.None
                        model.begun = true
                        "ok"
                    }
                    actual = outcome { subject.begin(unit, given) }
                }
                op < 40 -> {
                    name = "write"
                    if (model.begun && model.temp == TempFileState.None) {
                        model.temp = TempFileState.Partial
                        subject.write(unit)
                    }
                    expected = "ok"
                    actual = "ok"
                }
                op < 55 -> {
                    name = "mark"
                    expected = when (model.temp) {
                        TempFileState.Partial -> { model.temp = TempFileState.Complete; "ok" }
                        TempFileState.Complete -> "ok"
                        else -> "IllegalStateException"
                    }
                    actual = outcome { subject.mark(unit) }
                }
                op < 80 -> {
                    name = "commit"
                    expected = if (model.temp == TempFileState.Complete) {
                        model.temp = TempFileState.None
                        model.begun = false
                        model.available = true
                        "ok"
                    } else if (model.available) {
                        "ok"
                    } else {
                        "IllegalStateException"
                    }
                    actual = try {
                        subject.commit(unit)
                        "ok"
                    } catch (e: IllegalStateException) {
                        "IllegalStateException"
                    } catch (e: Throwable) {
                        e::class.simpleName ?: "?"
                    }
                }
                op < 90 -> {
                    name = "deleteTemp"
                    model.temp = TempFileState.None
                    model.begun = false
                    expected = "ok"
                    actual = outcome { subject.deleteTemp(unit) }
                }
                else -> {
                    name = "deleteResource"
                    model.available = false
                    expected = "ok"
                    actual = outcome { subject.deleteResource(unit) }
                }
            }
            trace += "#$step ${unit.id} $name -> $actual"
            val where = "seed $seed step $step ${unit.id} $name; last ops ${trace.takeLast(6)}"
            assertEquals(expected, actual, "$where: outcome")
            for (u: ParityUnitRound12 in units) {
                val m: ParityModelRound12 = models.getValue(u.id)
                assertEquals(m.available, subject.available(u), "$where: available(${u.id})")
                assertEquals(m.temp, subject.state(u), "$where: tempFileState(${u.id})")
            }
        }
    }
}

/**
 * B round 12, Q3: ten thousand seeded operations over [FakeDownloaderStorage] against the documented contract model; the same
 * driver and model run over the real Android storage in kmptoolkit-downloader (AndroidFakeParityRound12Test), so the two
 * agree through the model. Sizes and paths are not compared (documented limits of the fake).
 */
class FakeParityModelRound12Test {

    private class FakeSubject(private val fake: FakeDownloaderStorage) : ParitySubjectRound12 {
        override fun begin(unit: DownloadUnit, expected: Sha256?) = fake.beginTempFile(unit, expected)
        override fun write(unit: DownloadUnit) {
            fake.tempFileStates[unit.id] = TempFileState.Partial
        }
        override fun mark(unit: DownloadUnit) = fake.markTempFileComplete(unit)
        override suspend fun commit(unit: DownloadUnit) = fake.commitResource(unit)
        override fun deleteTemp(unit: DownloadUnit) = fake.deleteTempFile(unit)
        override fun deleteResource(unit: DownloadUnit) = fake.deleteResource(unit)
        override fun available(unit: DownloadUnit): Boolean = fake.isResourceAvailable(unit)
        override fun state(unit: DownloadUnit): TempFileState = fake.tempFileState(unit)
    }

    @Test
    fun `ten thousand seeded operations over the fake follow the contract model`() = runTest {
        val good: Sha256 = Sha256.parse("11".repeat(32))
        val other: Sha256 = Sha256.parse("22".repeat(32))
        for (seed: Int in listOf(12, 13)) {
            ParityDriverRound12(FakeSubject(FakeDownloaderStorage()), good, other).run(seed, 5_000)
        }
    }
}
