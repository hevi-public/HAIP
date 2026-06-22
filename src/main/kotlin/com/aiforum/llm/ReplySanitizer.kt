package com.aiforum.llm

import com.aiforum.dto.ReasoningLeak

/**
 * Pure Tier-0 post-processing of a raw model completion before it becomes the persisted reply body
 * (see the bdd-tiered-testing skill). Some local models (e.g. Gemma via LM Studio) leak their
 * chain-of-thought into the reply: either wrapped in `<think>…</think>` tags, or as bare "thinking"
 * preamble in the open ("Thinking Process:", "The user wants me to act as…", "**Analyze the context:**").
 *
 * Policy (deliberately non-destructive): we NEVER discard or fail a reply for this. We strip tagged
 * reasoning so it never reaches the reader, and we FLAG what we strip ([ReasoningLeak.ACTUAL]) or what a
 * conservative, start-anchored heuristic suspects ([ReasoningLeak.POSSIBLE]). The reply is persisted and
 * rendered as usual, just badged — so a heuristic false positive only over-badges a message, it never
 * loses one. The durable fix lives in the prompt (PromptRenderer steers the model to wrap reasoning in
 * `<think>` and emit only the final message); this is the net that catches what slips through.
 *
 * Holds NO IO — both real parsers ([LlmResponseParser], [OpenAiResponseParser]) call it at the
 * raw-completion → [LlmResponse] boundary, so every branch is unit-testable against canned text.
 */
object ReplySanitizer {

    /** Paired `<think>…</think>` / `<thinking>…</thinking>` block (case-insensitive, spans newlines). */
    private val THINK_BLOCK = Regex("(?is)<(think|thinking)\\b[^>]*>.*?</\\1\\s*>")

    /** A lone opening reasoning tag with no close — a truncated dump; strip from the tag to the end. */
    private val THINK_OPEN_DANGLING = Regex("(?is)<(think|thinking)\\b[^>]*>.*$")

    /**
     * Start-anchored signatures of leaked chain-of-thought, tolerant of leading markdown decoration
     * (`*`, `-`, `>`, `#`, `1.`, `**`). Deliberately conservative and anchored to the start of the reply:
     * a body that merely *mentions* "the user wants" mid-sentence must not trip it. The set is drawn from
     * observed leaks — broaden it as new ones show up rather than loosening the anchor.
     */
    private val REASONING_PREAMBLE = Regex(
        "(?i)^[\\s>*_#-]*(?:\\d+[.)]\\s*)?\\*{0,2}\\s*(?:" +
            "the user wants (?:me|you)|" +
            "thinking process|" +
            "(?:let me|i['’]?m going to|i will|i need to|i should) (?:think|analy[sz]e|break|work|start|first)|" +
            "analy[sz]e the (?:request|context|situation|prompt|task)|" +
            "determine [a-z]+['’]s role|" +
            "here['’]?s my (?:thinking|reasoning|plan|approach)|" +
            "step 1\\b|" +
            "\\(self.correction|" +
            "\\(constraint check" +
            ")",
    )

    /** Result of [stripReasoning]: the cleaned (trimmed) text and whether tagged reasoning was removed. */
    data class StripResult(val text: String, val removedReasoning: Boolean)

    /** Result of [sanitize]: the body to persist and the leak flag to tag it with (null = clean). */
    data class SanitizedReply(val text: String, val leak: ReasoningLeak?)

    /** Remove `<think>`/`<thinking>` blocks (and a dangling open tag), then trim. */
    fun stripReasoning(raw: String): StripResult {
        val noBlocks = THINK_BLOCK.replace(raw, "")
        val cleaned = THINK_OPEN_DANGLING.replace(noBlocks, "").trim()
        val removed = THINK_BLOCK.containsMatchIn(raw) || THINK_OPEN_DANGLING.containsMatchIn(noBlocks)
        return StripResult(cleaned, removed)
    }

    /** True when [text] starts with a recognised chain-of-thought preamble (see [REASONING_PREAMBLE]). */
    fun looksLikeReasoning(text: String): Boolean = REASONING_PREAMBLE.containsMatchIn(text)

    /**
     * Strip tagged reasoning, then classify the leak. Tag-removal wins (it's certain) over the heuristic:
     * if we stripped a `<think>` block it's [ReasoningLeak.ACTUAL] regardless of the remainder; otherwise
     * a heuristic match on the cleaned text is [ReasoningLeak.POSSIBLE]; otherwise clean.
     */
    fun sanitize(raw: String): SanitizedReply {
        val strip = stripReasoning(raw)
        val leak = when {
            strip.removedReasoning -> ReasoningLeak.ACTUAL
            looksLikeReasoning(strip.text) -> ReasoningLeak.POSSIBLE
            else -> null
        }
        return SanitizedReply(strip.text, leak)
    }
}
