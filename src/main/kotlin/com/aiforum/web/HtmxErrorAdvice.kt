package com.aiforum.web

import com.aiforum.llm.LlmException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler

/**
 * Honest failure UX (T1.4): turn an uncaught exception on an **htmx** request into a small error
 * FRAGMENT that htmx actually swaps into the target — instead of Boot's Whitelabel error PAGE, which
 * htmx would otherwise swap whole into the fragment slot and corrupt the view.
 *
 * The status code is the load-bearing subtlety, verified against the vendored htmx 2.0.6 source
 * (dist/htmx.js in the webjar): its default `responseHandling` maps `[45]..` to
 * `{ swap: false, error: true }`, so a fragment returned at a 4xx/5xx is FETCHED THEN DISCARDED — never
 * swapped — and only `htmx:responseError` fires. To make the fragment render in the DOM we therefore
 * return **HTTP 200** and carry the real failure to the client out-of-band via an **`HX-Trigger`**
 * response header (an `app:error` event): htmx processes HX-Trigger at the top of `handleAjaxResponse`,
 * BEFORE the swap/error branches, so it lands regardless of the 200. The client (htmx-error.js) listens
 * for `app:error` and raises a non-blocking toast; the fragment's `data-error-status` records the mapped
 * status for anyone reading the DOM.
 *
 * Scope: only requests carrying the `HX-Request` header (htmx sets it on every call) are intercepted. A
 * non-htmx request (a full page navigation, an API client) RETHROWS, so Boot's default handling — the
 * Whitelabel page / mapped error view AT its real error status — is unchanged. We keep this narrow: the
 * app's own generation/vision paths already convert IO-seam failures into graceful FAILED states (see
 * GenerationService); this advice is the safety net for the genuinely-uncaught ones — e.g. the persona
 * prompt-compose preview (POST /personas/compose), whose synchronous LLM call is unguarded by design.
 *
 * The stable `data-error-fragment` hook on the fragment — not the copy — is what the acceptance suite
 * asserts on, per the jte-spring-kotlin data-* convention.
 */
@ControllerAdvice
class HtmxErrorAdvice {

    private val log = LoggerFactory.getLogger(HtmxErrorAdvice::class.java)

    @ExceptionHandler(Exception::class)
    fun handle(
        ex: Exception,
        request: HttpServletRequest,
        response: HttpServletResponse,
        model: Model,
    ): String {
        // Not an htmx call → let Spring Boot's default error handling take over (Whitelabel / error view)
        // at its real status. Rethrowing preserves the existing non-htmx behaviour exactly. We read the
        // header off the request (a supported @ExceptionHandler arg) rather than via @RequestHeader —
        // argument resolution for @RequestHeader isn't wired in exception handlers.
        if (request.getHeader(HX_REQUEST_HEADER) == null) throw ex

        val (status, message) = noticeFor(ex)
        log.warn("htmx request failed ({}): returning 200 error fragment + HX-Trigger (mapped status {})", ex.javaClass.simpleName, status.value(), ex)

        // The failure signal rides an HX-Trigger header so the client can toast it. htmx fires this event
        // (on the triggering element, bubbling) before any swap/error branch, so it lands despite the 200.
        // The payload carries ONLY the numeric status — HTTP header values must be ISO-8859-1, and the
        // owner-facing copy contains non-Latin1 punctuation (em dashes), which Tomcat strips as invalid.
        // The client words the toast from the status itself (htmx-error-core.noticeFor), and the fragment
        // body — rendered via JTE in the UTF-8 response body, not a header — still shows the full message.
        response.setHeader(HX_TRIGGER_HEADER, errorTriggerJson(status.value()))

        model.addAttribute("status", status.value())
        model.addAttribute("message", message)
        // Returned at HTTP 200 (the default for a view-name return) so htmx's default responseHandling
        // SWAPS this fragment into the target. The JTE ViewResolver resolves the name → errorNotice.kte.
        return "fragments/errorNotice"
    }

    /** Map an exception to the HTTP status + owner-facing copy the error signal/fragment carry. */
    private fun noticeFor(ex: Exception): Pair<HttpStatus, String> = when (ex) {
        is LlmException.RateLimited ->
            HttpStatus.SERVICE_UNAVAILABLE to "The model is rate-limited right now — try again in a moment."
        is LlmException.Timeout ->
            HttpStatus.GATEWAY_TIMEOUT to "The model took too long to respond — please try again."
        is LlmException ->
            HttpStatus.BAD_GATEWAY to "The model couldn't complete that request — please try again."
        else ->
            HttpStatus.INTERNAL_SERVER_ERROR to "Something went wrong on the server — please try again."
    }

    /**
     * The HX-Trigger payload: `{"app:error":{"status":<code>}}`. htmx parses this JSON and dispatches an
     * `app:error` CustomEvent whose `detail` is the inner object; the client reads `detail.status` and
     * words the toast from it. ASCII-only by construction (a status int + fixed keys), so it survives the
     * ISO-8859-1 HTTP-header constraint. Hand-built to avoid pulling a serializer in for one fixed shape.
     */
    private fun errorTriggerJson(status: Int): String =
        """{"$ERROR_EVENT":{"status":$status}}"""

    companion object {
        const val HX_REQUEST_HEADER = "HX-Request"
        const val HX_TRIGGER_HEADER = "HX-Trigger"
        /** The custom htmx event the client toasts off. */
        const val ERROR_EVENT = "app:error"
    }
}
