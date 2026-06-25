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
 * FRAGMENT instead of Boot's Whitelabel error PAGE. htmx swaps whatever a request returns into the
 * fragment's target, so a whole `<html>` error page would corrupt the view (and the spinner would
 * spin forever because the swap that re-enables the triggering control never arrives). The matching
 * client listener (htmx-error.js) re-enables the control and shows a notice even when there's no body.
 *
 * Scope: this only intercepts requests carrying the `HX-Request` header (htmx sets it on every call).
 * A non-htmx request (a full page navigation, an API client) RETHROWS, so Boot's default handling —
 * the Whitelabel page / mapped error view — is unchanged. We deliberately keep this narrow: the app's
 * own generation/vision paths already convert IO-seam failures into graceful FAILED states (see
 * GenerationService); this advice is the safety net for the genuinely-uncaught ones — e.g. the persona
 * prompt-compose preview (POST /personas/compose), whose synchronous LLM call is unguarded by design.
 *
 * The fragment is rendered with a real HTTP status (so the client's `htmx:responseError` fires and the
 * acceptance assertions can read it); the stable `data-error-fragment` hook on the fragment — not the
 * copy — is what the acceptance suite asserts on, per the jte-spring-kotlin data-* convention.
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
        // Not an htmx call → let Spring Boot's default error handling take over (Whitelabel / error view).
        // Rethrowing (rather than returning a view) preserves the existing non-htmx behaviour exactly.
        // We read the header off the request (a supported @ExceptionHandler arg) rather than via
        // @RequestHeader — argument resolution for @RequestHeader isn't wired in exception handlers.
        if (request.getHeader(HX_REQUEST_HEADER) == null) throw ex

        val (status, message) = noticeFor(ex)
        log.warn("htmx request failed ({}): returning error fragment with status {}", ex.javaClass.simpleName, status.value(), ex)
        // Set the status on the response directly: returning a bare view name would render 200, which
        // would hide the failure from the client (no `htmx:responseError`, so the stuck control never
        // re-enables). The JTE ViewResolver still resolves the returned name → fragments/errorNotice.kte.
        response.status = status.value()
        model.addAttribute("status", status.value())
        model.addAttribute("message", message)
        return "fragments/errorNotice"
    }

    /** Map an exception to the HTTP status + owner-facing copy the error fragment shows. */
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

    companion object {
        const val HX_REQUEST_HEADER = "HX-Request"
    }
}
