package io.github.jamal_wia.kmptoolkit.storageharness

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One case as the Swift host lists it. */
class HarnessCaseInfo(
    val id: String,
    val title: String,
    /** True for a case a person judges: its result is OBSERVED, never PASS. */
    val manual: Boolean,
    val instructions: String,
)

/** The outcome of one case: [verdict] is `PASS`, `FAIL` (a check failed or the case threw) or `OBSERVED` (manual). */
class HarnessResult(val id: String, val verdict: String, val details: String)

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
            val result: HarnessResult = if (case == null) {
                HarnessResult(id, "FAIL", "no case named $id")
            } else {
                val log = CaseLog()
                try {
                    log.run(case)
                    val verdict: String = when {
                        log.failures > 0 -> "FAIL"
                        case.manual -> "OBSERVED"
                        else -> "PASS"
                    }
                    HarnessResult(id, verdict, log.text())
                } catch (thrown: Throwable) {
                    HarnessResult(id, "FAIL", log.text() + "\nthrew $thrown")
                }
            }
            withContext(Dispatchers.Main) { completion(result) }
        }
    }

    private suspend fun CaseLog.run(case: Case) {
        case.run(this)
    }
}
