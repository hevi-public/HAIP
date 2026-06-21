-- Persona personality (§6 extended). Two structured authoring inputs alongside the descriptor:
--   abilities — open-vocabulary keyword tags (e.g. "kotlin", "backend"), stored as a JSON array.
--   dials     — fixed-schema 0–10 personality axes (agreeableness/verbosity/rigor/warmth),
--               stored as a JSON object of name→value.
-- Both feed an LLM that COMPOSES the system_prompt at create/edit time; we persist the raw inputs so
-- an edit can hand the model the previous values + previous prompt and ask it to adjust, not regen.
-- SQLite can't add a column non-trivially, so plain ADD COLUMN with JSON-literal defaults; existing
-- rows (and seeded personas, which skip the composer) read back an empty list / empty map.
ALTER TABLE persona ADD COLUMN abilities TEXT NOT NULL DEFAULT '[]';
ALTER TABLE persona ADD COLUMN dials     TEXT NOT NULL DEFAULT '{}';
