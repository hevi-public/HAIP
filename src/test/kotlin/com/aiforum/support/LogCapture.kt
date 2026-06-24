package com.aiforum.support

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/**
 * Captures the log output of a single logger so a test can assert on it. Logs are observable behaviour
 * — an operator relies on them — so we pin them like any other contract (per the bdd-tiered-testing
 * skill: "tests double as documentation"). Spring Boot binds SLF4J to Logback, so the logger under test
 * is a [ch.qos.logback.classic.Logger] we can attach a [ListAppender] to.
 *
 * [around] scopes the appender to one block and always detaches it (and restores the logger's level)
 * afterwards, so captures can't leak between tests:
 *
 * ```kotlin
 * LogCapture.around(ShortcutService::class.java) { logs ->
 *     service.stories("is:started", 5)
 *     assertTrue(logs.has(Level.DEBUG, "Shortcut read ok"))
 * }
 * ```
 */
class LogCapture private constructor(private val appender: ListAppender<ILoggingEvent>) {

    /** Every captured event, in order. */
    val events: List<ILoggingEvent> get() = appender.list.toList()

    /** Formatted messages (placeholders interpolated) at [level], or at every level when null. */
    fun messages(level: Level? = null): List<String> =
        events.filter { level == null || it.level == level }.map { it.formattedMessage }

    /** True when some message at [level] contains [fragment]. */
    fun has(level: Level, fragment: String): Boolean =
        events.any { it.level == level && it.formattedMessage.contains(fragment) }

    /** Number of events captured at [level]. */
    fun count(level: Level): Int = events.count { it.level == level }

    companion object {
        /**
         * Attach a capturing appender to the logger named after [forClass], run [block] with the
         * capture, then detach and restore the logger's level — even if [block] throws.
         */
        fun <T> around(forClass: Class<*>, block: (LogCapture) -> T): T {
            val logger = LoggerFactory.getLogger(forClass) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val previousLevel = logger.level
            // Record DEBUG/TRACE too — the default config may sit at INFO and drop them.
            logger.level = Level.TRACE
            logger.addAppender(appender)
            try {
                return block(LogCapture(appender))
            } finally {
                logger.detachAppender(appender)
                logger.level = previousLevel
                appender.stop()
            }
        }
    }
}
