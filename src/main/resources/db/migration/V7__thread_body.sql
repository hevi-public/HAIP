-- The opening post gets a body: the owner's question. The post (OP) is the root of the comment tree —
-- it's already a navigable node (id=threadId, data-nav-item="post" in thread.kte; see the keyboard-nav
-- doc, which anticipated "posts get a body later"). This gives that root node the body it was missing,
-- so the opening question is no longer dropped "on the way in".
-- Nullable: title-only quick-creates (the browser form) and pre-V7 rows have no body. Root-ness stays
-- structural (parentId IS NULL), never keyed off this column.
ALTER TABLE thread ADD COLUMN body TEXT;
