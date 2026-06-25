package com.aiforum.service

import com.aiforum.dto.ReplyView
import com.aiforum.llm.CancellationToken
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks in-flight async generations (§4): the summon request returns a DRAFTING node immediately
 * while a worker thread runs the LLM, and a later `POST /replies/{id}/cancel` trips that node's shared
 * [CancellationToken]. This registry owns three things per node — the token, a latch that releases when
 * the node settles, and the transient DRAFTING [ReplyView] shown until it does.
 *
 * There is deliberately **no DRAFTING row in the database**: the node is persisted exactly once, when
 * it settles (see [GenerationService.settleOne]/persist). So the COULDNT_SAVE one-shot write fault still
 * lands on the settle write, and retry-by-id still finds a real row. The poll endpoint is DB-first and
 * falls back to [view] only while the row doesn't exist yet.
 *
 * Owning the executor here — rather than exposing an `Executor` bean — sidesteps the ambiguity with
 * Spring Boot's auto-configured `TaskExecutor`.
 */
@Component
class InFlightGenerations {

    private class Holder(
        val threadId: String,
        @Volatile var view: ReplyView,
        val token: CancellationToken,
        val done: CountDownLatch = CountDownLatch(1),
    )

    private val inFlight = ConcurrentHashMap<String, Holder>()

    // Threads with a summon whose ROUTING is still in flight: the async create path (§4) hands the
    // dispatcher's "who replies" LLM call to the worker, so there's a window after the request returns
    // but before any per-persona draft is registered. The thread page polls /threads/{id}/room while
    // this is set, then swaps in the drafts once they appear. Count, not flag, so a thread summoned twice
    // in quick succession only clears once both routings finish. Cleared in [reset] between scenarios.
    private val summoning = ConcurrentHashMap<String, Int>()

    // BOUNDED worker pool (T2.3): each generation worker runs an LLM call AND DB writes against the
    // 5-connection Hikari pool over single-writer SQLite, so the old unbounded newCachedThreadPool could
    // spawn arbitrarily many workers and pile contention onto that one writer. A fixed pool of [POOL_SIZE]
    // caps concurrency; excess submits queue (newFixedThreadPool's unbounded LinkedBlockingQueue) rather
    // than minting new threads. Lazy start is preserved: a fixed pool's core threads start ON DEMAND at
    // the first submit (we never call prestartAllCoreThreads), so the default instance the Tier-2 unit
    // test constructs — which never submits — spins no threads.
    private val threadCount = AtomicLong()
    private val pool = Executors.newFixedThreadPool(POOL_SIZE) { r ->
        Thread(r, "generation-${threadCount.incrementAndGet()}").apply { isDaemon = true }
    }

    /** Mark [id] in flight with its DRAFTING view; returns the token the worker hands to the LlmClient. */
    fun register(id: String, threadId: String, draftView: ReplyView): CancellationToken {
        val token = CancellationToken()
        inFlight[id] = Holder(threadId, draftView, token)
        return token
    }

    /** Run [task] on the generation pool. */
    fun submit(task: () -> Unit) {
        pool.execute(task)
    }

    /** Mark a summon's routing phase as started for [threadId] (the page shows "summoning" until it ends). */
    fun beginSummon(threadId: String) {
        summoning.merge(threadId, 1, Int::plus)
    }

    /** Routing done (drafts registered, or it failed): decrement, removing the key when it hits zero. */
    fun endSummon(threadId: String) {
        summoning.merge(threadId, -1) { current, delta -> (current + delta).takeIf { it > 0 } }
    }

    /** True while a summon's dispatcher routing is still in flight for [threadId] (no drafts registered yet). */
    fun isSummoning(threadId: String): Boolean = summoning.containsKey(threadId)

    /** The transient DRAFTING view while in flight — the poll's fallback before the settle row exists. */
    fun view(id: String): ReplyView? = inFlight[id]?.view

    /**
     * The DRAFTING views still in flight for [threadId]. The async summon's drafts live ONLY here until
     * they settle (there is no DRAFTING DB row), so a plain thread-page load reads this to show the room
     * responding — each surfaced node self-polls /replies/{id} and settles in place. Snapshot of the
     * current entries; a node that settles and is evicted simply drops out of the next load.
     */
    fun viewsFor(threadId: String): List<ReplyView> =
        inFlight.values.filter { it.threadId == threadId }.map { it.view }

    /**
     * Settle [id]: release any cancel waiter, then evict. Called from the worker's `finally`, so it runs
     * on every outcome (posted/failed/cancelled) and the entry never leaks. A waiter that captured the
     * holder before eviction still owns the latch reference, so the countDown reaches it.
     */
    fun markDone(id: String) {
        inFlight.remove(id)?.done?.countDown()
    }

    /**
     * Trip [id]'s token and wait (bounded) for the worker to settle it, so the caller can then read the
     * freshly-persisted row. No-op if the node is unknown or already settled.
     */
    fun cancel(id: String, awaitMillis: Long = CANCEL_AWAIT_MILLIS) {
        val holder = inFlight[id] ?: return
        holder.token.cancel()
        holder.done.await(awaitMillis, TimeUnit.MILLISECONDS)
    }

    /**
     * Cross-scenario seatbelt for the acceptance suite: trip every lingering token, wait briefly for the
     * workers to unwind, then clear — so a `HangUntilCancelled` worker from a prior scenario can never
     * write into the next scenario's freshly-reset DB.
     */
    fun reset() {
        val holders = inFlight.values.toList()
        holders.forEach { it.token.cancel() }
        holders.forEach { it.done.await(RESET_AWAIT_MILLIS, TimeUnit.MILLISECONDS) }
        inFlight.clear()
        summoning.clear()
    }

    @PreDestroy
    fun shutdown() {
        pool.shutdownNow()
    }

    private companion object {
        // Bounded concurrency cap for generation workers (T2.3) — small, since every worker contends for
        // the single SQLite writer behind the 5-connection Hikari pool.
        const val POOL_SIZE = 4
        const val CANCEL_AWAIT_MILLIS = 10_000L
        const val RESET_AWAIT_MILLIS = 2_000L
    }
}
