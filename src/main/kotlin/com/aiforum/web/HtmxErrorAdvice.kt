package com.aiforum.web

import com.aiforum.llm.LlmException
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler

/**
 * Honest failure UX (T1.4): turn an uncaught exception on an **htmx** request into a non-blocking,
 * out-of-band TOAST signal — instead of Boot's Whitelabel error PAGE, which htmx would otherwise swap
 * whole into the request's target (e.g. the compose `<textarea>`) and corrupt the view.
 *
 * Toast-only, NO body swap — the design rests on three facts verified against the vendored htmx 2.0.6
 * source (dist/htmx.js in the webjar):
 *   1. Default `responseHandling` maps `[45]..` to `{ swap: false, error: true }`, so on a non-2xx the
 *      response body is FETCHED THEN DISCARDED — htmx swaps nothing into the target. Returning the real
 *      error status therefore guarantees nothing lands in the compose field.
 *   2. `HX-Trigger` is processed at the TOP of `handleAjaxResponse`, before the swap/error branches, so
 *      it dispatches its event regardless of the status code. We use it to fire `app:error`.
 *   3. `removeRequestIndicators` runs in `xhr.onload` for every completed response (incl. non-2xx), so
 *      htmx itself re-enables the `hx-disabled-elt` control and clears the spinner — no stuck control,
 *      nothing for the client to re-enable.
 *
 * So for an htmx request we respond with the **mapped non-2xx status** + an `HX-Trigger: app:error`
 * header carrying only the numeric status (ASCII-only — HTTP header values are ISO-8859-1, and the
 * owner-facing copy has non-Latin1 punctuation Tomcat would strip) + an **empty body**. The client
 * (htmx-error.js) listens for `app:error` and raises a sticky, dismissible toast worded from the status.
 *
 * Scope: only requests carrying the `HX-Request` header (htmx sets it on every call) are intercepted. A
 * non-htmx request (a full page navigation, an API client) RETHROWS, so Boot's default handling — the
 * Whitelabel page / mapped error view AT its real error status — is unchanged. We keep this narrow: the
 * app's own generation/vision paths already convert IO-seam failures into graceful FAILED states (see
 * GenerationService); this advice is the safety net for the genuinely-uncaught ones — e.g. the persona
 * prompt-compose preview (POST /personas/compose), whose synchronous LLM call is unguarded by design.
 *
 * The `@ExceptionHandler(Exception::class)` catch is intentionally a CATCH-ALL, not narrowed to
 * `LlmException`: the whole point is that an htmx request ALWAYS gets feedback. A non-LLM uncaught
 * exception (a framework 4xx/5xx, a bug) on an htmx request therefore surfaces as a generic 500 toast by
 * design — narrowing the catch would re-introduce the silent stuck-spinner failure for those cases. Only
 * the [statusFor] mapping distinguishes LLM failures (502/503/504); everything else maps to 500.
 */
@ControllerAdvice
class HtmxErrorAdvice {

    private val log = LoggerFactory.getLogger(HtmxErrorAdvice::class.java)

    @ExceptionHandler(Exception::class)
    fun handle(ex: Exception, request: HttpServletRequest): ResponseEntity<Void> {
        // Not an htmx call → let Spring Boot's default error handling take over (Whitelabel / error view)
        // at its real status. Rethrowing preserves the existing non-htmx behaviour exactly. We read the
        // header off the request (a supported @ExceptionHandler arg) rather than via @RequestHeader —
        // argument resolution for @RequestHeader isn't wired in exception handlers.
        if (request.getHeader(HX_REQUEST_HEADER) == null) throw ex

        val status = statusFor(ex)
        log.warn("htmx request failed ({}): returning {} + HX-Trigger app:error (toast-only, no swap)", ex.javaClass.simpleName, status.value(), ex)

        // Mapped non-2xx status + empty body: htmx discards a non-2xx body (no swap into the target), so
        // nothing lands in the compose field. The failure rides the HX-Trigger header, which htmx fires
        // before the swap/error branches; the client toasts off the app:error event. Status-only payload
        // keeps the header ASCII (ISO-8859-1), and the client words the toast from the status.
        return ResponseEntity.status(status)
            .header(HX_TRIGGER_HEADER, errorTriggerJson(status.value()))
            .build()
    }

    /** Map an exception to the HTTP status the htmx error response carries (and the toast words off). */
    private fun statusFor(ex: Exception): HttpStatus = when (ex) {
        is LlmException.RateLimited -> HttpStatus.SERVICE_UNAVAILABLE   // 503
        is LlmException.Timeout -> HttpStatus.GATEWAY_TIMEOUT           // 504
        is LlmException -> HttpStatus.BAD_GATEWAY                       // 502
        else -> HttpStatus.INTERNAL_SERVER_ERROR                       // 500
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
