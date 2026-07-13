package com.aiforum.web

import jakarta.servlet.RequestDispatcher
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.webmvc.error.ErrorController
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseBody
import java.time.Clock
import java.time.Instant

/**
 * Styled error pages (error.kte) instead of Boot's whitelabel fallback — a mistyped URL or stale
 * permalink kept the terminal voice everywhere else, then dumped the reader on a bare white page.
 *
 * Scope mirrors what Boot's BasicErrorController did: browsers (Accept: text/html) get the page,
 * machine clients keep a JSON body of the same shape as before. htmx requests never reach /error —
 * HtmxErrorAdvice converts uncaught htmx failures into toast signals upstream — so this only ever
 * serves full-page navigations and API clients.
 */
@Controller
class ErrorPageController(private val clock: Clock) : ErrorController {

    @RequestMapping("/error", produces = [MediaType.TEXT_HTML_VALUE])
    fun errorHtml(request: HttpServletRequest, model: Model): String {
        val status = statusOf(request)
        model.addAttribute("status", status.value())
        model.addAttribute("reason", reasonFor(status))
        return "error"
    }

    @RequestMapping("/error")
    @ResponseBody
    fun errorJson(request: HttpServletRequest): ResponseEntity<Map<String, Any>> {
        val status = statusOf(request)
        val body = mapOf(
            "timestamp" to Instant.now(clock).toString(),
            "status" to status.value(),
            "error" to status.reasonPhrase,
            "path" to (request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) ?: ""),
        )
        return ResponseEntity.status(status).body(body)
    }

    private fun statusOf(request: HttpServletRequest): HttpStatus {
        val code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) as? Int
        return code?.let { HttpStatus.resolve(it) } ?: HttpStatus.INTERNAL_SERVER_ERROR
    }

    /** Owner-facing headline; the HTTP reason phrase reads too machine-y for the page itself. */
    private fun reasonFor(status: HttpStatus): String = when (status) {
        HttpStatus.NOT_FOUND -> "Nothing here."
        HttpStatus.METHOD_NOT_ALLOWED -> "That verb doesn't work here."
        else -> "Something broke."
    }
}
