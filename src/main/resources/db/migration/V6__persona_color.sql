-- Stable per-persona colour slot (an index into Avatar.PALETTE). Bound to the persona for life:
-- assigned once at insert as the next free slot (MAX+1), so adding — or removing — a persona never
-- recolours the existing ones. DEFAULT -1 marks "unassigned"; the UPDATE backfills any pre-existing
-- rows in insertion (rowid) order, giving the seeded team 0,1,2,… on first boot after the migration.
ALTER TABLE persona ADD COLUMN color_index INTEGER NOT NULL DEFAULT -1;
UPDATE persona SET color_index = (SELECT COUNT(*) FROM persona p2 WHERE p2.rowid < persona.rowid);
