package com.aiforum.persona

/**
 * Abilities are open-vocabulary keyword tags ("kotlin", "backend", "history") the owner types as a
 * comma-separated list. Unlike the fixed [Dials], the vocabulary is unbounded — they describe what a
 * persona knows, and later give the router a structured match signal against a topic.
 */
object Abilities {
    /** Split a comma-separated field into trimmed, non-blank, de-duplicated tags in input order. */
    fun parse(raw: String): List<String> =
        raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
