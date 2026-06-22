package com.aiforum.llm

import com.aiforum.domain.context.TranscriptRenderer

/**
 * Builds the user-facing *task* prompt handed to the model, shared by every [LlmClient] implementation
 * so personas behave identically across providers (the `claude -p` CLI in [ProcessLlmClient] and the
 * OpenAI-compatible HTTP backend in [OpenAiLlmClient]). The persona's character lives in the system
 * prompt ([PromptContext.personaSystemPrompt]); this renders the task — the transcript so far plus an
 * instruction pointing the persona at the exact node it was summoned to answer.
 *
 * Extracted verbatim from ProcessLlmClient so the two clients can never drift: a single render means the
 * CLI's `--system-prompt` + stdin and the HTTP `system`/`user` messages carry the same words.
 */
object PromptRenderer {

    /**
     * Length discipline appended to every render. Personas were defaulting to essay-length replies
     * (a multi-paragraph wall with bullet lists for a one-line point), which makes a threaded forum
     * unreadable. This asks for variety with a *concise* default — short by default, longer only when
     * the substance earns it — without hard-capping, so a genuinely meaty reply can still breathe.
     * It lives in the task prompt (not the stored per-persona system prompt) so it applies to every
     * persona immediately, including ones already seeded into the DB.
     */
    private const val BREVITY =
        "Keep it concise and conversational, the way you'd actually talk in a thread: match the " +
            "length to what you genuinely have to say. Most replies are a sentence or two; reach " +
            "for a short paragraph only when the point really needs it, and avoid long bullet " +
            "lists or essays. Don't restate the question or pad — make your point and stop."

    /**
     * Formatting steer for the renderer (MarkdownRenderer: commonmark + GraalJS highlight.js). This is
     * best-effort quality only — the renderer stays correct if the model ignores it: a fence with no
     * language degrades to a plain block, and raw HTML is escaped, not executed. Declaring the fence
     * language is what lets a block come back syntax-highlighted; markdown tables (not raw HTML) are
     * what render as real tables, since raw HTML in a body is deliberately inert.
     */
    private const val FORMATTING =
        "Write in GitHub-flavoured markdown. When you include code, always put it in a fenced block " +
            "with the language on the opening fence (```kotlin, ```yaml, ```bash, …). Use markdown " +
            "tables, not raw HTML."

    /**
     * Anti-preamble steer. Some local models (e.g. Gemma via LM Studio) dump their chain-of-thought into
     * the reply itself — "Thinking Process:", "The user wants me to act as…", "**Analyze the context:**".
     * This pins the contract hard: emit ONLY the final in-character message, and if the model must reason
     * first, wrap it in <think>…</think> so it's machine-strippable (ReplySanitizer removes those tags and
     * flags the rest). It's the source-side half of the fix; the sanitizer is the net for what leaks past.
     * Lives in the task prompt (not the stored per-persona system prompt) so it applies to every persona
     * immediately, including ones already seeded into the DB.
     */
    private const val NO_PREAMBLE =
        "Output only your final message, in character — no preamble, no narration of your persona or " +
            "instructions, and no visible reasoning, planning, or \"thinking process\". If you need to " +
            "reason first, put that reasoning inside <think>…</think> tags and write only the finished " +
            "reply after them."

    fun renderTask(context: PromptContext, personaName: String): String {
        val transcript = TranscriptRenderer.render(context.comments, context.targetId)
        // Name the persona explicitly and point it at the target message: without this the model sees its
        // own past lines labelled "$name:" amid the transcript and gets meta about who it is ("the framing
        // got flipped"). The system prompt carries the character; this carries the task.
        return if (transcript.isBlank()) {
            "You are opening a new thread in the forum. Post the first message as $personaName, in " +
                "character. $BREVITY $FORMATTING $NO_PREAMBLE"
        } else {
            // Point the persona at the EXACT node it was summoned for (marked "← reply to this"), naming its
            // ref. In whole-thread scope the target is rarely the last transcript line, so "the most recent
            // message" would aim the reply at an unrelated branch — only fall back to that when no target is
            // in scope (e.g. context built without one).
            val targetRef = TranscriptRenderer.refOf(context.comments, context.targetId)
            val task = if (targetRef != null) {
                "Write ${personaName}'s next reply, responding to message [#$targetRef] (marked " +
                    "\"${TranscriptRenderer.TARGET_MARKER.trim()}\" above). "
            } else {
                "Write ${personaName}'s next reply, responding to the most recent message above. "
            }
            "The forum discussion so far. Each line is \"[#ref] author: message\"; indentation shows " +
                "reply depth and \"↳ replying to #n\" marks which message it answers:\n\n$transcript\n\n---\n" +
                task +
                "Reply with the message text only, in character as $personaName. $BREVITY $FORMATTING $NO_PREAMBLE"
        }
    }
}
