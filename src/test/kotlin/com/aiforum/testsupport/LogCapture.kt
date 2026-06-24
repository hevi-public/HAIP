package com.aiforum.testsupport

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/**
 * Captures the log events a specific logger emits during a block, so tests can assert on logging as the
 * IO it is — deterministically (see the bdd-tiered-testing skill, "Logging is IO — assert it").
 *
 * It attaches a Logback [ListAppender] to the named logger and reads back each event's LEVEL and
 * `formattedMessage` (placeholders already substituted) — never the ambient layout (timestamp, thread,
 * MDC, level rendering), which is config, not behaviour. With everything below the seam stubbed, what's
 * left is fully deterministic, which is exactly what lets us pin the log line as a contract.
 *
 * Use with `.use { }` so the appender is detached and the logger's level restored afterwards:
 *
 * ```kotlin
 * LogCapture.on(GhCliGitHubClient::class.java).use { logs ->
 *     client.overview()
 *     assertTrue(logs.warns().any { it.startsWith("/github is unavailable:") })
 * }
 * ```
 *
 * Pin the logger to the PRODUCTION class (`Foo::class.java`), and have production code log through
 * `LoggerFactory.getLogger(Foo::class.java)` (not `javaClass`) — otherwise a test subclass logs under a
 * different name and the capture sees nothing.
 */
class LogCapture private constructor(
    private val logger: Logger,
    private val appender: ListAppender<ILoggingEvent>,
    private val previousLevel: Level?,
) : AutoCloseable {

    companion object {
        fun on(clazz: Class<*>): LogCapture = on(clazz.name)

        fun on(loggerName: String): LogCapture {
            val logger = LoggerFactory.getLogger(loggerName) as Logger
            val previousLevel = logger.level
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            // Force delivery down to DEBUG/TRACE regardless of ambient config, so best-effort debug lines
            // are captured too. Restored on close.
            logger.level = Level.TRACE
            return LogCapture(logger, appender, previousLevel)
        }
    }

    /** All captured events, in emission order. */
    val events: List<ILoggingEvent> get() = appender.list.toList()

    /** The fully-substituted messages at [level], in order. */
    fun messages(level: Level): List<String> =
        events.filter { it.level == level }.map { it.formattedMessage }

    fun warns(): List<String> = messages(Level.WARN)
    fun infos(): List<String> = messages(Level.INFO)
    fun debugs(): List<String> = messages(Level.DEBUG)
    fun errors(): List<String> = messages(Level.ERROR)

    // --- structured (key-value) logging: the machine-readable contract tooling keys off ---

    /** The value of structured key [key] on event [e], or null. Keys come from SLF4J `addKeyValue`. */
    fun keyValue(e: ILoggingEvent, key: String): String? =
        e.keyValuePairs?.firstOrNull { it.key == key }?.value?.toString()

    /** Every captured event tagged with our structured `event` id == [eventId], in order. This is how a
     *  test (and, later, a log-analysis tool) selects a line by its stable identity rather than its prose. */
    fun withEvent(eventId: String): List<ILoggingEvent> =
        events.filter { keyValue(it, "event") == eventId }

    override fun close() {
        logger.detachAppender(appender)
        appender.stop()
        logger.level = previousLevel
    }
}
