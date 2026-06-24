package com.aiforum.shortcut

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * The slim subsets of the Shortcut REST payloads we actually read, plus the pure mapping into the view
 * model. Jackson ignores unknown fields by default (Spring Boot sets FAIL_ON_UNKNOWN_PROPERTIES=false),
 * so these only declare what the forum displays — the API can grow without breaking us.
 *
 * Mapping lives in [ShortcutMapper] (pure, Tier-0 tested) so the HTTP client stays a thin transport.
 */

/** `GET /search/stories` envelope. */
data class ScSearchStories(
    val data: List<ScStory> = emptyList(),
    val total: Int = 0,
    val next: String? = null,
)

/** A story object (the fields the forum shows). */
data class ScStory(
    val id: Long = 0,
    val name: String = "",
    @param:JsonProperty("story_type") val storyType: String = "",
    @param:JsonProperty("workflow_state_id") val workflowStateId: Long? = null,
    @param:JsonProperty("app_url") val appUrl: String = "",
)

/** A workflow, of which we only need its states (for id → name resolution). */
data class ScWorkflow(
    val states: List<ScWorkflowState> = emptyList(),
)

/** A workflow state — id and its human name. */
data class ScWorkflowState(
    val id: Long = 0,
    val name: String = "",
    val type: String = "",
)

/** Pure transforms from API shapes to [StoryCard]s. No IO; Tier-0 territory. */
object ShortcutMapper {

    /** One API story → a card with an as-yet-unresolved state ([StoryCard.state] blank). */
    fun toCard(story: ScStory): StoryCard = StoryCard(
        publicId = story.id,
        name = story.name,
        type = story.storyType,
        stateId = story.workflowStateId,
        state = "",
        url = story.appUrl,
    )

    /** Fill in each card's workflow-state name from the id → name map (leaves it blank when unknown). */
    fun resolveStates(cards: List<StoryCard>, states: Map<Long, String>): List<StoryCard> =
        cards.map { card ->
            if (card.state.isBlank() && card.stateId != null) {
                card.copy(state = states[card.stateId] ?: "")
            } else {
                card
            }
        }

    /** Flatten a workflows response into a single state-id → state-name map. */
    fun stateNames(workflows: List<ScWorkflow>): Map<Long, String> =
        workflows.flatMap { it.states }.associate { it.id to it.name }
}
