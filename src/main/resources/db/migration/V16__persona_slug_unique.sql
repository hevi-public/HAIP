-- Enforce a UNIQUE persona.slug so profile links never collide. (V16 follows V15 = routing_event.)
-- findBySlug (PersonaRepository) assumes uniqueness (firstOrNull); two same-named personas would
-- otherwise share a slug and shadow each other's profile page. The matching insert-time collision
-- suffixing (slug-2, slug-3, …) lives in PersonaRepository.insert so new rows never trip the index.
--
-- DEDUPLICATE BEFORE INDEXING. A bare CREATE UNIQUE INDEX would fail on any DB that already holds
-- duplicate slugs — and crucially on the empty-slug case: slug was added in V5 with DEFAULT '', so
-- every row that predates V5 carries slug = '' (a built-in duplicate the moment there are ≥2 of them;
-- exactly the two empty-slug rows MigrationPipelineTest seeds). We resolve all collisions first, then
-- add the index, so this migration applies cleanly on a clean DB AND upgrades any messy existing one.

-- 1. Replace empty/NULL slugs with the row's id (id is the PRIMARY KEY, hence already unique), so the
--    pre-V5 rows stop colliding with each other on ''.
UPDATE persona SET slug = id WHERE slug IS NULL OR slug = '';

-- 2. Deterministically suffix any remaining non-empty duplicates: keep the first occurrence by rowid
--    untouched, and append -2, -3, … to the rest (matching insert's runtime suffixing scheme). The
--    window function partitions by slug and numbers within each group in stable rowid order.
UPDATE persona
SET slug = slug || '-' || d.rn
FROM (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY slug ORDER BY rowid) AS rn
    FROM persona
) d
WHERE persona.id = d.id AND d.rn > 1;

-- 3. Slugs are now distinct — enforce it going forward.
CREATE UNIQUE INDEX idx_persona_slug ON persona(slug);
