package com.aiforum.agui

/**
 * A subscriber to one run's [AguiEvent] stream. The SSE controller implements this to push each event
 * down an `SseEmitter`; tests implement it to record the sequence. [onComplete] fires once after the
 * terminal event so the subscriber can close its transport. Kept in the `agui` package (not `web`) so the
 * service layer's event channel stays free of servlet types.
 */
interface AguiEventListener {
    fun onEvent(event: AguiEvent)
    fun onComplete() {}
}

/** Handle returned by a successful subscribe; [cancel] detaches the listener (e.g. on client disconnect). */
fun interface AguiSubscription {
    fun cancel()
}
