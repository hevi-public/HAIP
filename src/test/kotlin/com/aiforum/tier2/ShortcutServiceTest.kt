package com.aiforum.tier2

import ch.qos.logback.classic.Level
import com.aiforum.shortcut.ShortcutClient
import com.aiforum.shortcut.ShortcutProperties
import com.aiforum.shortcut.ShortcutService
import com.aiforum.shortcut.ShortcutStatus
import com.aiforum.shortcut.StoryCard
import com.aiforum.shortcut.StorySource
import com.aiforum.support.LogCapture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider

/**
 * Tier-2: the [ShortcutService] facade running real query-resolution / caching / degradation logic over
 * a faked [ShortcutClient] (the single IO seam). It pins both the user-visible outcome (DISABLED /
 * OK / ERROR + which query ran) AND the observability logs — logs are behaviour an operator depends on,
 * so they're asserted via [LogCapture] rather than left to rot.
 */
@Tag("tier2")
class ShortcutServiceTest {

    // --- the IO seam, faked: records what it was asked and can be told to fail or go inactive ---------
    private class FakeClient(
        private val cards: List<StoryCard> = emptyList(),
        private val states: Map<Long, String> = emptyMap(),
        private val active: Boolean = true,
        private val failSearch: Boolean = false,
        private val failWorkflows: Boolean = false,
    ) : ShortcutClient {
        var searchCalls = 0
        var workflowCalls = 0
        var lastQuery: String? = null
        var lastPageSize = 0

        override fun searchStories(query: String, pageSize: Int): List<StoryCard> {
            searchCalls++
            lastQuery = query
            lastPageSize = pageSize
            if (failSearch) throw RuntimeException("search boom")
            return cards
        }

        override fun workflowStates(): Map<Long, String> {
            workflowCalls++
            if (failWorkflows) throw RuntimeException("workflow boom")
            return states
        }

        override fun isActive(): Boolean = active
    }

    /** An [ObjectProvider] that hands back [client] (or behaves as "no bean" when null). */
    private fun provider(client: ShortcutClient?): ObjectProvider<ShortcutClient> =
        object : ObjectProvider<ShortcutClient> {
            override fun getObject(vararg args: Any?): ShortcutClient =
                client ?: error("no ShortcutClient bean")
            override fun getObject(): ShortcutClient = client ?: error("no ShortcutClient bean")
            override fun getIfAvailable(): ShortcutClient? = client
            override fun getIfUnique(): ShortcutClient? = client
        }

    private fun props(
        enabled: Boolean = true,
        defaultQuery: String = "is:started",
        recentQuery: String = "updated:-2w..*",
        ownerMentionName: String = "",
        boxLimit: Int = 5,
        pageLimit: Int = 25,
    ) = ShortcutProperties(
        enabled = enabled,
        defaultQuery = defaultQuery,
        recentQuery = recentQuery,
        ownerMentionName = ownerMentionName,
        boxLimit = boxLimit,
        pageLimit = pageLimit,
    )

    private fun service(props: ShortcutProperties, client: ShortcutClient?) =
        ShortcutService(props, provider(client))

    private fun card(id: Long, stateId: Long?) =
        StoryCard(publicId = id, name = "Story $id", type = "feature", stateId = stateId, state = "", url = "u$id")

    // --- DISABLED: the surface goes dark, the seam is never touched ----------------------------------

    @Test
    fun `disabled when the integration is off — no client call, logs a skip`() {
        val client = FakeClient()
        val svc = service(props(enabled = false), client)

        LogCapture.around(ShortcutService::class.java) { logs ->
            val result = svc.stories("is:started", 5)
            assertEquals(ShortcutStatus.DISABLED, result.status)
            assertTrue(logs.has(Level.DEBUG, "integration disabled"), "expected a skip debug line")
        }
        assertEquals(0, client.searchCalls, "a disabled integration must never call Shortcut")
    }

    @Test
    fun `disabled when no client bean is present`() {
        val result = service(props(enabled = true), client = null).stories("is:started", 5)
        assertEquals(ShortcutStatus.DISABLED, result.status)
    }

    @Test
    fun `disabled when the client reports itself inactive`() {
        val client = FakeClient(active = false)
        val result = service(props(), client).stories("is:started", 5)
        assertEquals(ShortcutStatus.DISABLED, result.status)
        assertEquals(0, client.searchCalls)
    }

    // --- OK: stories come back, states resolve, a debug records the read ------------------------------

    @Test
    fun `ok read resolves workflow-state names and logs a debug with the count`() {
        val client = FakeClient(
            cards = listOf(card(101, stateId = 500), card(102, stateId = 999)),
            states = mapOf(500L to "In Progress"),
        )
        val svc = service(props(), client)

        LogCapture.around(ShortcutService::class.java) { logs ->
            val result = svc.stories("is:started", 5)

            assertEquals(ShortcutStatus.OK, result.status)
            assertEquals("In Progress", result.stories[0].state, "known state id resolves to its name")
            assertEquals("", result.stories[1].state, "an unknown state id stays blank, not an error")
            assertTrue(logs.has(Level.DEBUG, "Shortcut read ok"), "a successful read should log a debug")
            assertTrue(logs.messages(Level.DEBUG).any { it.contains("stories=2") }, "debug carries the count")
            assertEquals(0, logs.count(Level.WARN), "a clean read logs no warnings")
        }
    }

    @Test
    fun `a blank query falls back to the configured default query`() {
        val client = FakeClient()
        service(props(defaultQuery = "owner:me"), client).stories("   ", 5)
        assertEquals("owner:me", client.lastQuery)
    }

    // --- ERROR: a failed call degrades quietly and warns with the query ------------------------------

    @Test
    fun `a read failure degrades to ERROR and warns with the offending query`() {
        val client = FakeClient(failSearch = true)
        val svc = service(props(), client)

        LogCapture.around(ShortcutService::class.java) { logs ->
            val result = svc.stories("type:bug", 5)

            assertEquals(ShortcutStatus.ERROR, result.status)
            assertEquals("type:bug", result.query, "ERROR still reports which query was attempted")
            assertFalse(result.message.isNullOrBlank(), "ERROR carries a user-facing note")
            assertTrue(logs.has(Level.WARN, "Shortcut read failed"), "a read failure must warn")
            assertTrue(logs.messages(Level.WARN).any { it.contains("type:bug") }, "the warn names the query")
        }
    }

    @Test
    fun `a workflow-map failure is swallowed — stories still OK, with a warn`() {
        val client = FakeClient(cards = listOf(card(1, stateId = 500)), failWorkflows = true)
        val svc = service(props(), client)

        LogCapture.around(ShortcutService::class.java) { logs ->
            val result = svc.stories("is:started", 5)

            assertEquals(ShortcutStatus.OK, result.status, "a missing state map degrades, it doesn't fail the read")
            assertEquals("", result.stories[0].state, "states stay blank when the map can't be loaded")
            assertTrue(logs.has(Level.WARN, "workflow fetch failed"))
        }
    }

    // --- Caching & source resolution -----------------------------------------------------------------

    @Test
    fun `the workflow-state map is fetched once and cached across reads`() {
        val client = FakeClient(cards = listOf(card(1, 500)), states = mapOf(500L to "Done"))
        val svc = service(props(), client)

        svc.stories("a", 5)
        svc.stories("b", 5)

        assertEquals(2, client.searchCalls, "every read still searches")
        assertEquals(1, client.workflowCalls, "but the state map is cached after the first success")

        svc.evictCaches()
        svc.stories("c", 5)
        assertEquals(2, client.workflowCalls, "evictCaches forces a refetch")
    }

    @Test
    fun `boxStories runs the default query at the box limit`() {
        val client = FakeClient()
        service(props(defaultQuery = "is:started", boxLimit = 3), client).boxStories()
        assertEquals("is:started", client.lastQuery)
        assertEquals(3, client.lastPageSize)
    }

    @Test
    fun `pageStories RECENT source runs the recent query`() {
        val client = FakeClient()
        service(props(recentQuery = "updated:-1w..*"), client).pageStories(StorySource.RECENT, q = null)
        assertEquals("updated:-1w..*", client.lastQuery)
    }

    @Test
    fun `pageStories OWNER source needs a configured mention name, else a guiding ERROR`() {
        val client = FakeClient()
        val result = service(props(ownerMentionName = ""), client).pageStories(StorySource.OWNER, q = null)

        assertEquals(ShortcutStatus.ERROR, result.status)
        assertTrue(result.message!!.contains("owner-mention-name"), "the error tells the operator what to set")
        assertEquals(0, client.searchCalls, "no point calling Shortcut when the source is misconfigured")
    }

    @Test
    fun `pageStories OWNER source builds an owner query when configured`() {
        val client = FakeClient()
        service(props(ownerMentionName = "jane"), client).pageStories(StorySource.OWNER, q = null)
        assertEquals("owner:jane", client.lastQuery)
    }
}
