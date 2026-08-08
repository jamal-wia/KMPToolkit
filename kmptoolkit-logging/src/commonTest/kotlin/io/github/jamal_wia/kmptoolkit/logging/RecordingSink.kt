package io.github.jamal_wia.kmptoolkit.logging

/** One event as a sink saw it, so a test can assert on the whole tuple at once. */
internal data class LogEvent(
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwable: Throwable?,
)

/**
 * Test sink that records everything it is given and, if [failWith] is set, throws it afterwards —
 * so a test can assert both that a broken sink still *saw* the event and that its failure was
 * contained.
 */
internal class RecordingSink(
    private val name: String = "sink",
    private val failWith: Throwable? = null,
    private val order: MutableList<String>? = null,
) : LogSink {

    val events: MutableList<LogEvent> = mutableListOf()

    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        events += LogEvent(level, tag, message, throwable)
        order?.add(name)
        failWith?.let { throw it }
    }
}
