-- Reasoning-leak flag: when a model leaks its chain-of-thought into a reply, we strip what we can,
-- persist the reply as usual, and tag it (ReplySanitizer). ACTUAL = stripped <think> tags (certain);
-- POSSIBLE = a heuristic flagged untagged "thinking" preamble (uncertain). NULL (the default for
-- existing rows, and any clean reply) = no leak. Stored as TEXT to mirror the other enum columns.
ALTER TABLE comment ADD COLUMN reasoning_leak TEXT;
