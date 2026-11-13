package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/**
 * What the two seeded fuzzers of the model tier ([SequenceFuzzModelTest], [TransitionFuzzModelTest]) share: the seed
 * budget, the failure report and the prepare of a shared player on the virtual host. Nothing here changes a shared
 * helper of the tier; the functions are new and private to the fuzzers.
 */
internal object FuzzModelSupport {

    /** The seeds every fuzzer runs: 2000 of them, from 1 (the seeds of the real-media classes start at 1 as well). */
    const val SEED_COUNT: Int = 2_000

    /** The most virtual milliseconds one host may add to an `after` delay, the jitter of a `PauseTransition` (design B1). */
    const val JITTER_MAX_MS: Double = 17.0

    /**
     * The seeds to run. By default `1..2000`; `-Dkmptoolkit.test.seed=N` (Gradle: `-Pkmptoolkit.test.seed=N`) replays that
     * one seed alone, which is how a failing seed of a report is repeated; `-Dkmptoolkit.test.fuzz.seeds=K` runs the
     * first K seeds instead of 2000.
     */
    fun seeds(): List<Int> {
        System.getProperty("kmptoolkit.test.seed")?.toIntOrNull()?.let { return listOf(it) }
        val count: Int = System.getProperty("kmptoolkit.test.fuzz.seeds")?.toIntOrNull() ?: SEED_COUNT
        return (1..count).toList()
    }

    /**
     * Fails with every failing seed and the first [shown] messages. [failures] pairs a seed with the message of its
     * failure; the seed is also in the message, so a log line is enough to repeat the run.
     */
    fun assertNoFailures(name: String, total: Int, failures: List<Pair<Int, String>>, shown: Int = 5) {
        if (failures.isEmpty()) return
        val seedList: String = failures.take(MAX_SEEDS_LISTED).joinToString(", ") { it.first.toString() }
        val more: String = if (failures.size > MAX_SEEDS_LISTED) ", ..." else ""
        throw AssertionError(
            "$name: ${failures.size} of $total seeds failed (seeds $seedList$more); repeat one with " +
                "-Pkmptoolkit.test.seed=<seed>\n" + failures.take(shown).joinToString("\n---\n") { it.second },
        )
    }

    /** The head of a failure for a report: the message's first [lines] lines, then the log tail it carries, trimmed. */
    fun describe(seed: Int, failure: Throwable, lines: Int = 45): String {
        val all: List<String> = (failure.message ?: failure::class.java.name).lines()
        // The first lines (the verdict and the log tail) and the sequence line the fuzzer appends, which can come after a long tail.
        val text: String = (all.take(lines) + all.drop(lines).filter { it.startsWith("sequence:") }).joinToString("\n")
        val suppressed: String = failure.suppressed.firstOrNull()?.let { "\n  (also: ${it.message?.lineSequence()?.firstOrNull()})" }.orEmpty()
        return "seed $seed: $text$suppressed"
    }

    private const val MAX_SEEDS_LISTED: Int = 60
}

/**
 * `player.prepare` driven the way [FxModelScope.load] drives an engine load: the IO hop is real, the FX queue is drained
 * here. The wait is real time only as a hang bound.
 */
internal fun FxModelScope.fuzzPrepare(player: VideoPlayer, source: VideoSource) {
    // timing-hygiene-ignore: TH2 bound the real-time hang bound of a prepare whose IO hop is real
    val deadline: Long = System.nanoTime() + FUZZ_PREPARE_BOUND_MS * FUZZ_NANOS_PER_MILLI
    runBlocking {
        val preparing = async { player.prepare(source) }
        while (!preparing.isCompleted) {
            host.runUntilIdle()
            yield()
            // timing-hygiene-ignore: TH2 bound the real-time hang bound of a prepare whose IO hop is real
            if (!preparing.isCompleted && System.nanoTime() - deadline > 0L) {
                preparing.cancel()
                throw AssertionError("prepare did not finish within $FUZZ_PREPARE_BOUND_MS ms of real time ($host)")
            }
            Thread.onSpinWait()
        }
        preparing.await()
    }
}

private const val FUZZ_PREPARE_BOUND_MS: Long = 10_000L
private const val FUZZ_NANOS_PER_MILLI: Long = 1_000_000L
