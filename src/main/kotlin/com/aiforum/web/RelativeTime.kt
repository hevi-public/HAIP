package com.aiforum.web

import java.time.Duration
import java.time.Instant

/**
 * Compact "time ago" labels for the front-page rail (design "9m", "41m", "1h", "2h"). Pure: the
 * caller passes `now` (from the injected Clock) so it stays deterministic and testable. Coarse on
 * purpose — a forum sidebar wants "3h", not "3h 12m".
 */
object RelativeTime {
    fun ago(then: Instant, now: Instant): String {
        val secs = Duration.between(then, now).seconds.coerceAtLeast(0)
        return when {
            secs < 60 -> "now"
            secs < 3600 -> "${secs / 60}m"
            secs < 86_400 -> "${secs / 3600}h"
            else -> "${secs / 86_400}d"
        }
    }
}
