-- Per-persona model pin (§4): a persona MAY pin the model it generates with; blank carries the
-- aiforum.llm.default-model fallback (itself blank => the CLI's own default model). Materialised on the
-- persona row so the choice travels with the persona into every LlmRequest, mirroring how depth_budget
-- (V3) is carried per node rather than derived. DEFAULT '' keeps every pre-existing persona on the
-- fallback, so this migration is a no-op for current rows.
ALTER TABLE persona ADD COLUMN model TEXT NOT NULL DEFAULT '';
