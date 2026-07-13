package com.aiforum.tier0

import com.aiforum.shortcut.ScStory
import com.aiforum.shortcut.ScWorkflow
import com.aiforum.shortcut.ScWorkflowState
import com.aiforum.shortcut.ShortcutMapper
import com.aiforum.shortcut.StoryCard
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: the pure mapping from Shortcut API shapes into the forum's view model — story → card, the
 * workflows → state-name map, and resolving a card's state from that map. No IO.
 */
@Tag("tier0")
class ShortcutMapperTest {

    @Test
    fun `a story maps to a card with its ref, type, state id and url, state left unresolved`() {
        val card = ShortcutMapper.toCard(
            ScStory(
                id = 123,
                name = "Fix the login bug",
                storyType = "bug",
                workflowStateId = 500,
                appUrl = "https://app.shortcut.com/acme/story/123",
            ),
        )
        assertEquals(123L, card.publicId)
        assertEquals("sc-123", card.ref)
        assertEquals("bug", card.type)
        assertEquals(500L, card.stateId)
        assertEquals("", card.state) // unresolved until the workflow map is applied
        assertEquals("https://app.shortcut.com/acme/story/123", card.url)
    }

    @Test
    fun `workflows flatten into a single state-id to name map`() {
        val states = ShortcutMapper.stateNames(
            listOf(
                ScWorkflow(states = listOf(ScWorkflowState(500, "In Progress"), ScWorkflowState(501, "Done"))),
                ScWorkflow(states = listOf(ScWorkflowState(600, "Backlog"))),
            ),
        )
        assertEquals(mapOf(500L to "In Progress", 501L to "Done", 600L to "Backlog"), states)
    }

    @Test
    fun `resolveStates fills in the state name from the map`() {
        val resolved = ShortcutMapper.resolveStates(
            listOf(card(stateId = 500), card(stateId = 999)),
            mapOf(500L to "In Progress"),
        )
        assertEquals("In Progress", resolved[0].state)
        assertEquals("", resolved[1].state) // unknown id stays blank, never errors
    }

    @Test
    fun `resolveStates leaves a card with no state id untouched`() {
        val resolved = ShortcutMapper.resolveStates(listOf(card(stateId = null)), mapOf(500L to "In Progress"))
        assertEquals("", resolved[0].state)
        assertNull(resolved[0].stateId)
    }

    private fun card(stateId: Long?) = StoryCard(
        publicId = 1, name = "x", type = "feature", stateId = stateId, state = "", url = "u",
    )
}
