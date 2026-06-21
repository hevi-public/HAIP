-- The opening post's body: the actual content of a thread, authored alongside the title on the
-- new-thread form. Optional — older threads and the title-only browser/API paths carry ''. Rendered
-- under the title in the thread's OP; distinct from the comment tree, where the room's replies live.
ALTER TABLE thread ADD COLUMN body TEXT NOT NULL DEFAULT '';
