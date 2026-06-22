# Versioned conversation branches (revision-scoped subtrees)

> **Status:** 🔵 deferred — design captured, not built &nbsp;·&nbsp; **Owner:** Hevi
> **Depends on:** the reply content-versioning feature (V14 `comment_revision`, the `‹ 2/3 ›` switcher) — already shipped.
> **Why deferred:** a sizable, cross-cutting change to the core context engine; the shipped versioning is coherent for the common case without it. This doc is the build-ready sketch for when it becomes a priority.

## The gap

Reply content-versioning (regenerate + edit) stores multiple **bodies** per comment and lets the owner step between them with a `‹ 2/3 ›` switcher. But a comment's **children hang off the node**, not off a revision: replies are attached via `comment.parent_id` alone.

So when you switch a parent's revision, its **body changes but its subtree does not**. The replies stay put even though they were written in response to the *previous* text — their "in reply to" quote and their conversational logic now refer to a body that's no longer shown. It's coherent when you regenerate a reply **before** it has children (the common flow); it diverges when you regenerate a node that already sprouted a sub-conversation.

The desired behaviour ("the whole thread changes when I switch"): each revision owns the subtree that grew under **it**. Switching the parent to revision 1 shows revision 1's children; switching to revision 2 shows revision 2's children. This is the ChatGPT edit/regenerate-tree model.

## Why it's not a small follow-on

The UI is the easy part. The cost is that the app reads "the thread" **flatly** in several places that would all have to become *active-revision-path* aware to stay coherent:

| Surface | Today | Under revision-scoped subtrees |
|---|---|---|
| **LLM context assembly** (`ContextAssembler` via `CommentRepository.threadComments` / `ancestorPath`) — the core one | reads the full flat tree / ancestor path | must walk only the **active** revision path, or a regenerated persona is fed replies from versions that aren't shown |
| **Rail / branch index** (`BranchIndexBuilder`, `ReplyTreeAssembler.assemble`) | nests all children by `parent_id` | nests only children matching each ancestor's selected revision |
| **Auto-grow** (`growableLeaves`) + recursive CTEs (`ancestorPath`, `subtree`, `descendantCount`) | parent_id only | revision-scoped |
| **Reply targeting / composer** | parents under the node | parents under the node **at its current revision** |

The first row is the real weight: context assembly is the heart of the product, and "the thread" silently becomes "the thread along the active revision path" everywhere it's read.

## Sketch of the model

1. **Schema** — add `parent_revision_idx INTEGER` to `comment`: which revision of its parent this child was born under. A child created while the parent shows revision *k* records `parent_revision_idx = k`. Top-level nodes (no parent) leave it null.
   - **Backfill:** existing children → the parent's current `revision_index` (which is 0 for everything not yet regenerated, so today's trees are unchanged).
2. **Visible-tree rule** — a child is visible iff `parent_revision_idx == parent.revision_index`. Apply it in `ReplyTreeAssembler` (rendering + rail) and in the read queries that feed context.
3. **Active-path reads** — `threadComments`/`ancestorPath` (and the CTEs) gain a revision-aware variant that only descends into children matching each node's selected revision. This is what keeps the LLM context honest.
4. **Creation** — every place that inserts a child (`startGeneration`, `summonAsync`, `autoGrow`, owner reply, `/more`, note) stamps `parent_revision_idx` from the parent's current `revision_index`.
5. **Switching** — `selectRevision` already swaps the body; with the visible-tree rule in place, the re-rendered subtree (via `replyTree.subtree`) naturally shows that revision's children. The `POST /replies/{id}/revision/{idx}` swap target may need to widen from `closest article` to the node's full subtree container (already the case) — verify the OOB rail refresh covers the now-different child set.

## Decisions to make before building

- **Empty new revisions:** a freshly regenerated revision has **no** children. Is that the intended "fork the conversation" UX, or should children optionally carry forward? (ChatGPT does not carry them forward.) Recommend: do not carry forward — a new take starts a clean branch.
- **Descendant counts in the rail** for non-active revisions — show "(3 hidden on v1)" or just omit? 
- **Deletion semantics** across revisions (already subtree-based; confirm a revision's subtree deletes with it but sibling revisions' subtrees survive).
- **Migration interplay:** this adds a column to `comment`; sequence it after the attachments branch's V13 and this feature's V14 (next free version at build time).

## Testing posture (per the tiered-testing skill)

- **Tier-1:** `parent_revision_idx` round-trip; the revision-aware `ancestorPath`/`threadComments` return only the active path.
- **Tier-2:** regenerating a node with children yields an empty new-revision subtree; switching back restores the original subtree; a persona summoned under revision 2 is fed revision 2's path, not revision 1's.
- **Acceptance:** switch a parent between revisions and assert the visible child set changes (the missing piece today).

## Recommendation

Leave the shipped behaviour as-is for now. Pick this up as a dedicated piece if/when regenerating already-answered nodes becomes a common flow — it's a context-engine change, not a UI tweak, and deserves its own branch and review.
