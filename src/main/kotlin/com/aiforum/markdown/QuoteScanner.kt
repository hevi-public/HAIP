package com.aiforum.markdown

/** A quote edge inferred from a markdown blockquote (plan_docs/comment-quotes.md §5): comment [srcId]
 *  contains a `> ` blockquote of [text], which uniquely matches the prose of comment [targetId]. */
data class DerivedQuote(val srcId: String, val targetId: String, val text: String)

/**
 * Folds **manual / persona markdown blockquotes** into the quote graph (the toolbar only creates edges for
 * quotes made in the browser; an LLM persona — or a hand-typed `> ` — quotes in markdown, with no edge).
 * Pure text logic, NO DB / DOM (Tier-0; tested in tier0/QuoteScannerTest).
 *
 * The matching trick: a quoted passage appears verbatim in EVERY comment that quoted it (as a `> `
 * block) AND in the original (as prose). To resolve to the original — not the other quoters — we match
 * each blockquote against candidates' **prose** (their body with blockquote lines stripped). A passage
 * with exactly one prose match is linked; zero (external/paraphrased/OP quotes) or several (ambiguous)
 * are left unlinked — best-effort, matching the snapshot-anchoring philosophy (§4).
 */
object QuoteScanner {

    /** Passages shorter than this (after normalisation) are too generic to link safely, so we skip them. */
    const val MIN_LEN = 8

    /** Collapse runs of whitespace to one space and trim — used for tolerant containment matching. */
    fun normalize(s: String): String = s.replace(Regex("\\s+"), " ").trim()

    /** Each contiguous markdown blockquote block in [markdown], as one plain-text passage (the `>`
     *  markers stripped, lines joined). A blank or non-`>` line ends a block. */
    fun blockquotePassages(markdown: String): List<String> {
        val passages = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotBlank()) passages.add(current.toString().trim())
            current.setLength(0)
        }
        for (line in markdown.lines()) {
            val m = Regex("^\\s*>+\\s?(.*)$").matchEntire(line)
            if (m != null) {
                if (current.isNotEmpty()) current.append("\n")
                current.append(m.groupValues[1])
            } else {
                flush()
            }
        }
        flush()
        return passages.filter { it.isNotBlank() }
    }

    /** [markdown] with its blockquote lines removed — a comment's own prose, for matching against. */
    fun prose(markdown: String): String =
        markdown.lineSequence().filterNot { Regex("^\\s*>").containsMatchIn(it) }.joinToString("\n")

    /**
     * Derive quote edges from the blockquotes in [bodies] (id → body). Each blockquote passage is linked
     * to the UNIQUE other comment whose prose contains it. [storedKeys] are the (src, target,
     * normalized-text) triples already recorded as real edges, so a toolbar quote (whose body also carries
     * the inserted blockquote) isn't counted twice. Returns distinct [DerivedQuote]s.
     */
    fun derive(bodies: List<Pair<String, String>>, storedKeys: Set<Triple<String, String, String>>): List<DerivedQuote> {
        val proseById = bodies.associate { (id, body) -> id to normalize(prose(body)) }
        val seen = HashSet<Triple<String, String, String>>()
        val out = mutableListOf<DerivedQuote>()
        for ((id, body) in bodies) {
            for (passage in blockquotePassages(body)) {
                val norm = normalize(passage)
                if (norm.length < MIN_LEN) continue
                val matches = bodies.map { it.first }.filter { it != id && proseById.getValue(it).contains(norm) }
                if (matches.size != 1) continue // 0 = no clear original; >1 = ambiguous — leave unlinked
                val key = Triple(id, matches[0], norm)
                if (key in storedKeys || !seen.add(key)) continue
                out += DerivedQuote(id, matches[0], passage)
            }
        }
        return out
    }
}
