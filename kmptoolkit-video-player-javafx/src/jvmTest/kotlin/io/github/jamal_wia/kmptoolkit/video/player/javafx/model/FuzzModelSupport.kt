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
 * Why one key of a [KnownRed] ledger is red: the fragile window the hazard monitor names (`W-PLAY`), the call it names as the
 * offending one (`SET_RATE`), the defect class of the advisor ruling (`A` or `C` of reviews/fxport-j13a-opus.md) and the task
 * that fixes it (`D-pi`).
 */
internal data class KnownRedEntry(val window: String, val trigger: String, val defectClass: String, val task: String) {

    /** The text the monitor in FAIL_FAST mode puts first in a failure: `fragile window violated, W-PLAY: SET_RATE ...`. */
    fun failFastText(): String = "fragile window violated, $window: $trigger"

    /** `W-PLAY: SET_RATE (class A, task D-pi)`, for a message. */
    fun recorded(): String = "$window: $trigger (class $defectClass, task $task)"
}

/**
 * THE LEDGER of keys (seeds, scripts) that are red on the current production and are classified as a product defect by an
 * advisor ruling, recorded instead of hidden or ignored (decision D-95; the J11b rule: an expected-violation mechanism never
 * accepts a hit as permanent). A fuzzer or an exhaustive test leaves exactly [keys] out of its green run, and one test that
 * runs (never ignored) calls [check], which replays every key and fails when
 * - a key is green now ("remove it from KNOWN_RED in the commit that fixed it"), or
 * - a key fails in another way than recorded (another window, another trigger, or not a window violation at all),
 * so the ledger can only shrink on purpose. An entry joins it only with an advisor class and leaves it by turning green.
 * It is empty when the task named in its entries merges.
 *
 * @param K the key of one run: a seed for the fuzzers, the script id for the exhaustive interleaving tests.
 * @param kind what a key is called in a message (`seed`, `script`).
 */
internal class KnownRed<K : Any>(entries: Map<K, KnownRedEntry>, private val kind: String = "seed") {

    private val entries: Map<K, KnownRedEntry> = entries.toMap()

    /** Every key on the ledger. */
    val keys: Set<K> get() = entries.keys

    /** Whether [key] is on the ledger (a green run leaves it out). */
    operator fun contains(key: K): Boolean = key in entries

    /** The entry of [key], or null. */
    operator fun get(key: K): KnownRedEntry? = entries[key]

    /**
     * Runs [run] for every key of the ledger and fails, naming every problem at once, unless each one failed exactly as
     * recorded. [run] returns the failure text of the key's run, or null when it was green; [name] opens the message.
     * [matches] decides whether a failure text is the recorded violation; the default reads the monitor's FAIL_FAST text.
     */
    fun check(
        name: String,
        run: (K) -> String?,
        matches: (KnownRedEntry, String) -> Boolean = { entry: KnownRedEntry, text: String -> text.contains(entry.failFastText()) },
    ) {
        val problems: MutableList<String> = mutableListOf()
        for ((key: K, entry: KnownRedEntry) in entries) {
            val failure: String? = run(key)
            when {
                failure == null -> problems += "$kind $key is green now: remove it from KNOWN_RED in the commit that fixed it"
                !matches(entry, failure) ->
                    problems += "$kind $key fails differently than recorded (${entry.recorded()}): ${failure.lines().take(MAX_LINES_SHOWN).joinToString("\n")}"
            }
        }
        if (problems.isNotEmpty()) {
            throw AssertionError("$name: ${problems.size} of ${entries.size} KNOWN_RED entries no longer hold\n" + problems.joinToString("\n---\n"))
        }
    }

    private companion object {
        const val MAX_LINES_SHOWN: Int = 12
    }
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
