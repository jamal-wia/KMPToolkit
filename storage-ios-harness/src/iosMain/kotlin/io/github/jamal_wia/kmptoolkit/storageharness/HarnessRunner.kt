package io.github.jamal_wia.kmptoolkit.storageharness

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** One case as the Swift host lists it. */
class HarnessCaseInfo(
    val id: String,
    val title: String,
    /** True for a case a person judges: its result is OBSERVED, never PASS. */
    val manual: Boolean,
    val instructions: String,
)

/**
 * The outcome of one case: [verdict] is `PASS`, `FAIL` (a check failed or the case threw), `FAIL (timed out)` (an
 * automatic case that did not finish within [AUTOMATIC_CASE_LIMIT]), `SKIPPED` (the device lacks what the case needs, for
 * example a passcode: neither a pass nor a failure of the library, the reason is in [details]) or `OBSERVED` (manual).
 */
class HarnessResult(val id: String, val verdict: String, val details: String)

/**
 * The hang bound of an automatic case. A case that shows a system sheet or blocks in a Security call would otherwise
 * leave the console log without its `HARNESS DONE` line and nobody would know why. Manual cases are not bounded: a person
 * answers their sheets.
 */
internal val AUTOMATIC_CASE_LIMIT: Duration = 120.seconds

/**
 * The entry point of the iOS host app (`ios-host`): lists the cases and runs one at a time against the real Keychain.
 * The host calls [run] from its button; the completion arrives on the main thread. Nothing here is library API.
 */
class HarnessRunner {

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun cases(): List<HarnessCaseInfo> = allCases.map { HarnessCaseInfo(it.id, it.title, it.manual, it.instructions) }

    fun run(id: String, completion: (HarnessResult) -> Unit) {
        val case: Case? = allCases.firstOrNull { it.id == id }
        scope.launch {
            val result: HarnessResult = if (case == null) HarnessResult(id, "FAIL", "no case named $id") else execute(case)
            withContext(Dispatchers.Main) { completion(result) }
        }
    }

    /**
     * Runs [case] in a child of its own and, for an automatic case, waits for it with a bound. The wait is on the
     * child (`await` is cancellable), not on the case's body: a body stuck in a blocking Security call cannot be
     * interrupted, so a timeout around the body itself would not fire until that call returned. When the bound is
     * reached the child is cancelled (best effort) and the case is reported `FAIL (timed out)`.
     */
    private suspend fun execute(case: Case): HarnessResult {
        val log = CaseLog()
        val body: Deferred<HarnessResult> = scope.async { runCase(case, log) }
        if (case.manual) return body.await()
        val finished: HarnessResult? = withTimeoutOrNull(AUTOMATIC_CASE_LIMIT) { body.await() }
        if (finished != null) return finished
        body.cancel()
        return HarnessResult(
            case.id,
            "FAIL (timed out)",
            log.text() + "\nFAIL (timed out): the case did not finish within $AUTOMATIC_CASE_LIMIT " +
                "(a system sheet is waiting, or a Security call is blocked); the lines above are what it wrote before that",
        )
    }

    private suspend fun runCase(case: Case, log: CaseLog): HarnessResult = try {
        case.run(log)
        val skipped: String? = log.skipReason
        val verdict: String = when {
            log.failures > 0 -> "FAIL"
            skipped != null -> "SKIPPED"
            case.manual -> "OBSERVED"
            else -> "PASS"
        }
        HarnessResult(case.id, verdict, log.text())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (thrown: Throwable) {
        HarnessResult(case.id, "FAIL", log.text() + "\nthrew $thrown")
    }
}
