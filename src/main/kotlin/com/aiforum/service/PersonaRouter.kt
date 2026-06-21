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
