package io.github.jamal_wia.kmptoolkit.notification

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * The coalescer's lock and the notifier's post lock are two locks, so the post section "is this frame still
 * wanted? hand it to the platform, complete it" is atomic against cancel and against other post sections, but
 * NOT against another frame's claim and prepare: those only take the coalescer's lock and can run between the
 * liveness check and the completion. Earlier explorers fused those two steps. This one keeps them apart, mixes
 * claims, build failures, refusals, cancel, cancelAll and (in the iOS order) late acknowledgement, refusal and
 * abandonment, over four frames of one id, and requires only the safety direction: once every frame has
 * settled, a frame the coalescer calls redundant must be exactly what the tray may show.
 */
class ProgressCoalescerSplitSectionExplorerAdversarialTest {

    private enum class Order { ANDROID, IOS }

    private enum class Verdict { ACCEPT, REFUSE, ACCEPT_ABANDONED, REFUSE_ABANDONED }

    private class Frame(
        val id: String,
        val percent: Int?,
        val content: String,
        val useClaim: Boolean,
        val buildFails: Boolean,
        val verdict: Verdict,
    ) {
        var claim: ProgressCoalescer.PostClaim? = null
        var ticket: ProgressCoalescer.PostTicket? = null
        var step: Int = 0
        var finished: Boolean = false
        var handedOver: Boolean = false
        var pendingVerdict: Boolean = false
        val bucket: Int? get() = if (percent == null || percent >= 100) null else (percent / 10) * 10
        val progress: NotificationProgress? get() = percent?.let { NotificationProgress.Determinate(it) }
    }

    private fun run(order: Order, seed: Int, ids: List<String> = listOf("d")): String? {
        val random = Random(seed)
        val coalescer = ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO)
        val frames: List<Frame> = List(4) {
            Frame(
                id = ids[random.nextInt(ids.size)],
                percent = listOf(0, 5, 10, 25, 55, 100, null)[random.nextInt(7)],
                content = "c${random.nextInt(2)}",
                useClaim = random.nextInt(3) == 0,
                buildFails = random.nextInt(6) == 0,
                verdict = Verdict.values()[random.nextInt(if (order == Order.IOS) 4 else 2)],
            )
        }
        // possible tray states: null = nothing; Pair(bucket or -1 for terminal, content)
        val tray: MutableMap<String, Set<Pair<Int, String>?>> = ids.associateWith { setOf<Pair<Int, String>?>(null) }.toMutableMap()
        fun descOf(f: Frame): Pair<Int, String> = (f.bucket ?: -1) to f.content
        var openSection: Frame? = null
        var cancels = 0
        val log = StringBuilder()

        fun stepFrame(f: Frame) {
            // Steps: 0 claim (optional), 1 prepare, 2 build (may fail), 3 live check, 4 complete, 5 verdict
            when (f.step) {
                0 -> { if (f.useClaim) f.claim = coalescer.claim(f.id); f.step = 1; log.append("C${frames.indexOf(f)} ") }
                1 -> {
                    f.ticket = coalescer.prepare(f.id, f.progress, f.content, f.claim)
                    log.append("P${frames.indexOf(f)}${if (f.ticket == null) "x" else ""} ")
                    if (f.ticket == null) { f.finished = true; f.claim?.let { coalescer.release(it) } } else f.step = 2
                }
                2 -> {
                    log.append("B${frames.indexOf(f)} ")
                    if (f.buildFails) {
                        coalescer.rollback(f.ticket!!); f.finished = true; f.claim?.let { coalescer.release(it) }
                    } else f.step = 3
                }
                3 -> {
                    log.append("L${frames.indexOf(f)} ")
                    if (!coalescer.isLive(f.ticket!!)) { f.finished = true; f.claim?.let { coalescer.release(it) } }
                    else { openSection = f; f.step = 4 }
                }
                4 -> {
                    log.append("S${frames.indexOf(f)} ")
                    val accepted = f.verdict == Verdict.ACCEPT || f.verdict == Verdict.ACCEPT_ABANDONED
                    val unknown = f.verdict == Verdict.ACCEPT_ABANDONED || f.verdict == Verdict.REFUSE_ABANDONED
                    openSection = null
                    f.handedOver = true
                    if (order == Order.ANDROID) {
                        if (accepted) { coalescer.complete(f.ticket!!, acknowledged = true); tray[f.id] = setOf(descOf(f)) }
                        else coalescer.rollback(f.ticket!!)
                        f.finished = true; f.claim?.let { coalescer.release(it) }
                    } else {
                        coalescer.complete(f.ticket!!)
                        if (unknown) tray[f.id] = tray.getValue(f.id) + descOf(f) else if (accepted) tray[f.id] = setOf(descOf(f))
                        f.pendingVerdict = true
                        f.claim?.let { coalescer.release(it) }
                        f.step = 5
                    }
                }
                5 -> {
                    log.append("V${frames.indexOf(f)} ")
                    when (f.verdict) {
                        Verdict.ACCEPT -> coalescer.acknowledge(f.ticket!!)
                        Verdict.REFUSE -> coalescer.rollback(f.ticket!!)
                        else -> coalescer.abandon(f.ticket!!)
                    }
                    f.pendingVerdict = false; f.finished = true
                }
            }
        }

        var guard = 0
        while (frames.any { !it.finished } && guard++ < 400) {
            val section = openSection
            if (section != null) {
                // only another frame's claim/prepare (steps 0 and 1) or the section's own completion may run
                val candidates = frames.filter { !it.finished && (it === section || it.step <= 1) }
                val pick = candidates[random.nextInt(candidates.size)]
                stepFrame(pick)
                continue
            }
            // a cancel or cancelAll sometimes
            if (cancels < 2 && random.nextInt(9) == 0) {
                cancels++
                if (random.nextBoolean()) {
                    val victim: String = ids[random.nextInt(ids.size)]
                    coalescer.forget(victim)
                    tray[victim] = setOf(null)
                } else {
                    coalescer.clear()
                    ids.forEach { tray[it] = setOf(null) }
                }
                log.append(if (cancels > 0) "X " else "")
                continue
            }
            val candidates = frames.filter { !it.finished }
            stepFrame(candidates[random.nextInt(candidates.size)])
        }
        check(frames.all { it.finished }) { "schedule did not finish" }

        for (id in ids) for (percent in listOf(0, 5, 10, 25, 55)) for (content in listOf("c0", "c1")) {
            if (coalescer.wouldSuppress(id, NotificationProgress.Determinate(percent), content)) {
                val d: Pair<Int, String> = (percent / 10 * 10) to content
                if (tray.getValue(id).any { it != d }) {
                    return "seed $seed order $order id $id: frame ($percent%, $content) is called redundant but the tray may show ${tray[id]}; " +
                        "frames ${frames.map { "${it.id}/${it.percent}/${it.content}/${it.verdict}/fail=${it.buildFails}" }}; steps $log"
                }
            }
        }
        return null
    }

    @Test
    fun `android order, a redundant verdict always matches the tray`() {
        val failures = (0 until 120_000).mapNotNull { run(Order.ANDROID, it) }
        assertTrue(failures.isEmpty(), "${failures.size} of 120000 schedules; first: ${failures.firstOrNull()}")
    }

    @Test
    fun `ios order, a redundant verdict always matches the tray`() {
        val failures = (0 until 120_000).mapNotNull { run(Order.IOS, it) }
        assertTrue(failures.isEmpty(), "${failures.size} of 120000 schedules; first: ${failures.firstOrNull()}")
    }

    @Test
    fun `android order, two ids never leak into each other`() {
        val failures = (0 until 100_000).mapNotNull { run(Order.ANDROID, it, listOf("d", "e")) }
        assertTrue(failures.isEmpty(), "${failures.size} of 100000 schedules; first: ${failures.firstOrNull()}")
    }

    @Test
    fun `ios order, two ids never leak into each other`() {
        val failures = (0 until 100_000).mapNotNull { run(Order.IOS, it, listOf("d", "e")) }
        assertTrue(failures.isEmpty(), "${failures.size} of 100000 schedules; first: ${failures.firstOrNull()}")
    }
}
