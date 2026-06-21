package com.aiforum.service

import com.aiforum.domain.Comment
import com.aiforum.domain.context.ContextAssembler
import com.aiforum.llm.CancellationToken
import com.aiforum.llm.LlmClient
import com.aiforum.llm.LlmRequest
import com.aiforum.llm.PersonaRef
import com.aiforum.repo.PersonaRepository.Persona
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * The "Anyone" dispatcher. When the owner doesn't pin a persona (the composer's default selection), this
 * asks the model which roster member(s) are best suited to reply to the topic, so the room curates
 * itself instead of the owner having to know who's who.
 *
 * It routes through the SAME single LlmClient seam everything else uses (see the bdd-tiered-testing
 * skill) — no second IO boundary. The model is told to name the participants who should weigh in; we
 * then scan its reply for roster names on word boundaries, so the decision survives the model answering
 * in prose ("I'd let Sol and Paul take this") rather than a strict format. Anything unparseable, an
 * empty pick, or a generation failure falls back to the whole room — "Anyone" must never silently pick
 * no one.
 *
 * ## Known failure mode: name-matching honours the model only when it *names* members
 *
 * The routing decision is the model's, but we recover it by string-matching roster names in free text.
 * So the model's judgement is honoured ONLY insofar as it spells a roster member's name. If it answers
 * "the backend folks should take this" or "ask the Kotlin person" without writing "Sol", nothing matches
 * and we fall back to the WHOLE room — silently widening a decision the model may have meant to narrow.
 * In effect a thin slice of "who decides" is really "did the model phrase it in a way we can parse." The
 * system prompt asks for names-only and the tests cover the prose/unparseable paths, but the coupling is
 * real and the fallback hides it (you can't tell a deliberate "everyone" from a parse miss).
 *
 * Ideas to harden this, cheapest first — none implemented yet, ordered by effort/robustness trade-off:
 *  1. **Make fallbacks observable. (RECOMMENDED FIRST STEP.)** Meter each pick's outcome — clean match
 *     vs. parse-miss-widened-to-all vs. generation failure — so the parse-miss RATE is measurable instead
 *     of invisible. We don't yet know how often this bites; this tells us, and gives the data to judge
 *     whether 2–5 are worth their cost. Surfaced on a new Admin → Statistics page (see
 *     plan_docs/persona-routing-observability.md).
 *  2. **Numbered menu.** Present the roster as a numbered list and ask the model to reply with the
 *     number(s); digits are far less ambiguous than names and don't collide with ordinary prose.
 *  3. **Match descriptors too, not just names.** Fall back to keyword/semantic matching on each persona's
 *     descriptor ("backend" -> Sol) when no name hits. More forgiving, but risks false positives.
 *     SUPERSEDED by the structured **abilities** tags (V9): a bounded, owner-curated tag set is a cleaner
 *     topic-match signal than free-text descriptors, and the **dials** let a fan-out pick deliberately
 *     COMPLEMENTARY personas (a contrarian + an agreeable one). See plan_docs/persona-traits-routing.md.
 *  4. **Reprompt once on a miss** before widening — "Reply with ONLY the exact names" — trading one extra
 *     call for a tighter answer; only then fall back.
 *  5. **Constrained/tool output (the principled fix).** Have the model select from an enum of valid
 *     persona ids via tool-use/structured output, so an invalid pick is impossible by construction. This
 *     needs the LlmClient seam to grow a structured-call shape (today it returns free text only), so it's
 *     the biggest change — but it removes the parse step, and with it this whole failure mode.
 *  6. **Embedding-based routing (different trade-off).** Rank personas by similarity between the question
 *     and their descriptors with no LLM call at all — deterministic and parse-free, but loses the model's
 *     reasoning about the topic.
 */
@Component
class PersonaRouter(private val llm: LlmClient) {

    /** Pick the persona(s) that should reply to [context] from [roster]; never returns empty for a non-empty roster. */
    fun pick(roster: List<Persona>, context: List<Comment>): List<Persona> {
        // A lone persona is the only possible answer — don't spend an LLM call to "choose" it.
        if (roster.size <= 1) return roster
        val reply = runCatching {
            llm.generate(
                LlmRequest(ContextAssembler.assemble(systemPrompt(roster), context), ROUTER, TIMEOUT),
                CancellationToken(),
            ).text
        }.getOrNull() ?: return roster
        return parseChosen(reply, roster).ifEmpty { roster }
    }

    companion object {
        private val TIMEOUT = Duration.ofSeconds(60)
        // The dispatcher is not a forum member — it has no descriptor and posts nothing. Blank model =>
        // the default-model fallback (routing is cheap, no need to pin a heavyweight model).
        private val ROUTER = PersonaRef("dispatcher", "Moderator")

        /** A safety cap so a model that over-eagerly lists everyone can't fan out the whole roster at once. */
        const val MAX_PICKS = 3

        /**
         * Extract the chosen personas from the dispatcher's free-text [reply], ordered by where each name
         * first appears (the prompt asks for most-relevant first) and capped at [MAX_PICKS]. Word-boundary
         * matching so "Sol" matches the name but not "solve"/"solution". Pure — Tier-0 testable.
         */
        fun parseChosen(reply: String, roster: List<Persona>, max: Int = MAX_PICKS): List<Persona> =
            roster.mapNotNull { p ->
                Regex("\\b${Regex.escape(p.name)}\\b", RegexOption.IGNORE_CASE).find(reply)?.let { p to it.range.first }
            }.sortedBy { it.second }.take(max).map { it.first }

        private fun systemPrompt(roster: List<Persona>): String = buildString {
            append("You are the forum's dispatcher. You do NOT answer the question yourself. Given the ")
            append("discussion below, decide which participant(s) from the roster are best suited to reply, ")
            append("based on the topic. Pick the most relevant — usually one or two, at most three. ")
            append("Respond with ONLY their names, comma-separated, most relevant first. Nothing else.\n\n")
            append("Roster:\n")
            roster.forEach { p ->
                append("- ${p.name}")
                if (p.descriptor.isNotBlank()) append(": ${p.descriptor}")
                append("\n")
            }
        }
    }
}
