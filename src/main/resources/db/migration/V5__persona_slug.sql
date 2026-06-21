-- URL-safe slug for persona profile links. Derived from the name at insert time: lower-cased and
-- spaces → hyphens. Enables multi-word persona names (e.g. "Ada Lovelace" → "ada-lovelace") without
-- URL-encoding noise. DEFAULT '' keeps pre-existing dev/prod rows; Flyway backfills them on first boot.
ALTER TABLE persona ADD COLUMN slug TEXT NOT NULL DEFAULT '';
