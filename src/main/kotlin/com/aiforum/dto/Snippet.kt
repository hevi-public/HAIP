package com.aiforum.dto

/** Shared one-line text preview: collapse whitespace and truncate with an ellipsis if over [max]. */
object Snippet {
    fun oneLine(body: String, max: Int): String {
        val s = body.replace(Regex("\\s+"), " ").trim()
        return if (s.length <= max) s else s.take(max).trimEnd() + "…"
    }
}
