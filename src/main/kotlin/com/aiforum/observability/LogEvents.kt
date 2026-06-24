package com.aiforum.observability

import org.slf4j.spi.LoggingEventBuilder

/**
 * Tag a structured `event` id onto a fluent SLF4J log builder — the stable, machine-readable identity that
 * log tooling (alerting, analysis) keys off, independent of the human message wording (see the
 * bdd-tiered-testing skill, "Logging is IO — assert it").
 *
 * Ids are namespaced per emitter (`gh.*`, `llm.*`) and defined as constants on the emitting class, which
 * doubles as that emitter's event catalogue. The id (and field keys) are the breaking surface for log
 * consumers; the message text is free to change.
 *
 * ```kotlin
 * log.atWarn().setMessage("generation for {} timed out after {}ms")
 *    .addArgument(persona).addArgument(ms)
 *    .event("llm.timeout").addKeyValue("persona", persona).addKeyValue("timeoutMs", ms)
 *    .log()
 * ```
 */
fun LoggingEventBuilder.event(id: String): LoggingEventBuilder = addKeyValue("event", id)
