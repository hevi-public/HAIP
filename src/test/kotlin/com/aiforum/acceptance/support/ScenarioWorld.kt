package com.aiforum.acceptance.support

import io.cucumber.spring.ScenarioScope
import org.springframework.stereotype.Component

/**
 * Per-scenario state holder. @ScenarioScope gives a fresh instance per scenario, so nothing leaks
 * across scenarios (see the cucumber-spring-bdd skill — never store scenario state on step fields).
 */
@Component
@ScenarioScope
class ScenarioWorld {
    var threadId: String? = null
    var lastStatus: Int? = null
    var lastBody: String? = null
    var composerTargetId: String? = null

    /** The raw fragment a /generate POST returned (the htmx-swap payload), before any settle polling —
     *  so a scenario can assert on the swap structure the browser actually receives. */
    var lastFragment: String? = null

    /** alias (e.g. persona name or "sol's reply") -> reply id, for cross-step references. */
    val replyIds = mutableMapOf<String, String>()
    var lastReplyId: String? = null

    /** alias -> integer snapshot (e.g. a branch's descendant count before autonomous growth). */
    val counts = mutableMapOf<String, Int>()

    fun rememberReply(alias: String, id: String) {
        replyIds[alias] = id
        lastReplyId = id
    }
}
