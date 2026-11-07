package io.github.jamal_wia.kmptoolkit.location

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The retention rule of the system-services alert: for an app that is not yet authorized
 * CoreLocation may never answer a request, so a request per prompt would be kept for the life of
 * the process. At most one is kept; a finished one makes room for the next.
 */
class OneAlertRequestTest {

    private var begun = 0
    private val finishers: MutableList<() -> Unit> = mutableListOf()

    private val request = OneAlertRequest { finished: (Any) -> Unit ->
        begun++
        val token = Any()
        finishers += { finished(token) }
        token
    }

    @Test
    fun `prompts while one request never answers keep exactly one pending`() {
        repeat(3) { request.start() }

        assertEquals(1, request.pendingCount)
        assertEquals(1, begun)
    }

    @Test
    fun `a finished request makes room for the next prompt`() {
        request.start()
        finishers.single().invoke()
        assertEquals(0, request.pendingCount)

        request.start()

        assertEquals(1, request.pendingCount)
        assertEquals(2, begun)
    }
}
