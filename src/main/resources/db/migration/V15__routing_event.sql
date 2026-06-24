-- Observability for the "Anyone" dispatcher (plan_docs/persona-routing-observability.md). (V15 follows
-- V14 = comment revisions.) Every time PersonaRouter.pick() runs, it records exactly one outcome here, so
-- the silent "fell back to the whole room" failure mode becomes a measurable rate instead of an invisible
-- one. Pure append: nothing reads these rows on the generation path — only the Admin → Statistics page.
--
-- raw_reply is the model's own routing answer, captured ONLY for WIDENED_NO_MATCH (NULL otherwise) so we
-- can eyeball why name-matching missed. Firewall-safe: the routing context already excludes the owner's
-- +1 (§7/§13), so this is the model's text naming personas, never an owner-identity signal.

CREATE TABLE routing_event (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    outcome       TEXT    NOT NULL,   -- RoutingOutcome.name: MATCHED | WIDENED_NO_MATCH | FAILED_GENERATION | SINGLE_PERSONA
    roster_size   INTEGER NOT NULL,   -- how many personas the dispatcher chose from
    picked_count  INTEGER NOT NULL,   -- how many it routed to (= roster_size on any widen/fallback)
    routing_scope TEXT    NOT NULL,   -- ScopeMode.name: WHOLE_THREAD | BRANCH_ONLY
    raw_reply     TEXT,               -- the model's routing answer, for WIDENED_NO_MATCH only
    created_at    TEXT    NOT NULL    -- ISO-8601 UTC; lexicographically ordered for the "last 7 days" window
);

CREATE INDEX idx_routing_event_created_at ON routing_event(created_at);
