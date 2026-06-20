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
        @Volatile var view: ReplyView,
        val token: CancellationToken,
        val done: CountDownLatch = CountDownLatch(1),
    )

    private val inFlight = ConcurrentHashMap<String, Holder>()

    // corePoolSize 0 → no threads exist until the first submit, so the default instance the Tier-2
    // unit test constructs (which never submits) spins nothing.
    private val threadCount = AtomicLong()
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "generation-${threadCount.incrementAndGet()}").apply { isDaemon = true }
    }

    /** Mark [id] in flight with its DRAFTING view; returns the token the worker hands to the LlmClient. */
    fun register(id: String, draftView: ReplyView): CancellationToken {
        val token = CancellationToken()
        inFlight[id] = Holder(draftView, token)
        return token
    }

    /** Run [task] on the generation pool. */
    fun submit(task: () -> Unit) {
        pool.execute(task)
    }

    /** The transient DRAFTING view while in flight — the poll's fallback before the settle row exists. */
    fun view(id: String): ReplyView? = inFlight[id]?.view

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
    }

    @PreDestroy
    fun shutdown() {
        pool.shutdownNow()
    }

    private companion object {
        const val CANCEL_AWAIT_MILLIS = 10_000L
        const val RESET_AWAIT_MILLIS = 2_000L
    }
}
