-- Depth budget (§4): the per-branch fuel for bounded autonomous growth. An owner comment or a /more
-- directive GRANTS DepthBudget.DEFAULT_GRANT to its node; each descending reply carries parent-1, so a
-- branch auto-grows ~3–4 levels past the owner's last comment then stalls at 0. Materialised per node
-- (not derived) so per-branch isolation falls out for free: a touched tangent keeps its own budget
-- while ignored siblings stay at 0. DEFAULT 0 keeps pre-existing rows non-growing.
ALTER TABLE comment ADD COLUMN depth_budget INTEGER NOT NULL DEFAULT 0;
