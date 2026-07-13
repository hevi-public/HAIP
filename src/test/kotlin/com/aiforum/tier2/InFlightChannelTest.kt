package com.aiforum.tier2

import com.aiforum.agui.AguiEvent
import com.aiforum.agui.AguiEventListener
import com.aiforum.domain.Comment
import com.aiforum.dto.GenerationState
import com.aiforum.dto.ReplyView
import com.aiforum.service.InFlightGenerations
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-2: the per-run AG-UI event channel on [InFlightGenerations] — buffer/replay, live fan-out, terminal
 * completion, and the "unknown/evicted → null so the caller falls back to the poll" contract that keeps
 * the SSE layer purely additive. No Spring, no LLM: drive the registry directly.
 */
@Tag("tier2")
class InFlightChannelTest {

    private class Recorder : AguiEventListener {
        val events = mutableListOf<AguiEvent>()
        var completed = false
        override fun onEvent(event: AguiEvent) { events.add(event) }
        override fun onComplete() { completed = true }
    }

    private fun draftView(id: String): ReplyView =
        Comment(id, "t", null, "p", "", GenerationState.DRAFTING, null, 0).toReplyView()

    private fun register(reg: InFlightGenerations, id: String) = reg.register(id, "t", draftView(id))

    @Test
    fun `a subscriber replays the buffer then receives live events through the terminal one`() {
        val reg = InFlightGenerations()
        register(reg, "n")
        reg.publish("n", AguiEvent.RunStarted("n"))
        reg.publish("n", AguiEvent.TextDelta("n", "a"))

        val rec = Recorder()
        val sub = reg.subscribe("n", rec)
        assertNotNull(sub) // an in-flight, non-terminal run yields a live subscription
        assertEquals(listOf(AguiEvent.RunStarted("n"), AguiEvent.TextDelta("n", "a")), rec.events)

        reg.publish("n", AguiEvent.TextDelta("n", "b"))
        reg.publish("n", AguiEvent.RunFinished("n"))
        assertEquals(
            listOf(
                AguiEvent.RunStarted("n"),
                AguiEvent.TextDelta("n", "a"),
                AguiEvent.TextDelta("n", "b"),
                AguiEvent.RunFinished("n"),
            ),
            rec.events,
        )
        assertTrue(rec.completed)
    }

    @Test
    fun `subscribing after the run finished (not yet evicted) replays and completes at once`() {
        val reg = InFlightGenerations()
        register(reg, "n")
        reg.publish("n", AguiEvent.RunStarted("n"))
        reg.publish("n", AguiEvent.RunError("n", "boom"))

        val rec = Recorder()
        reg.subscribe("n", rec)
        assertEquals(listOf(AguiEvent.RunStarted("n"), AguiEvent.RunError("n", "boom")), rec.events)
        assertTrue(rec.completed)
    }

    @Test
    fun `subscribing to an unknown run returns null and delivers nothing`() {
        val reg = InFlightGenerations()
        val rec = Recorder()
        assertNull(reg.subscribe("missing", rec))
        assertTrue(rec.events.isEmpty())
    }

    @Test
    fun `subscribing after eviction returns null so the caller polls the settled row`() {
        val reg = InFlightGenerations()
        register(reg, "n")
        reg.publish("n", AguiEvent.RunStarted("n"))
        reg.markDone("n")
        assertNull(reg.subscribe("n", Recorder()))
    }

    @Test
    fun `cancel detaches the subscriber from further events`() {
        val reg = InFlightGenerations()
        register(reg, "n")
        val rec = Recorder()
        val sub = reg.subscribe("n", rec)
        reg.publish("n", AguiEvent.RunStarted("n"))
        sub!!.cancel()
        reg.publish("n", AguiEvent.TextDelta("n", "after-cancel"))
        assertEquals(listOf(AguiEvent.RunStarted("n")), rec.events)
    }

    @Test
    fun `publishing to an unknown run is a no-op`() {
        val reg = InFlightGenerations()
        reg.publish("missing", AguiEvent.RunStarted("missing")) // must not throw
    }
}
