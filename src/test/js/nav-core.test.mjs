import { test } from "node:test";
import assert from "node:assert/strict";
import {
  buildModel,
  preorderNext,
  preorderPrev,
  parentOf,
  firstChildOf,
  nextSibling,
  prevSibling,
  firstNode,
  lastNode,
  resolveDescend,
  search,
  matches,
} from "../../main/resources/static/nav-core.mjs";

/*
 * Sample tree (pre-order = the array order below). Indentation shows parentId.
 *   a            (Sol)   "let's talk routing"
 *     b          (Saul)  "frontend angle"
 *       d        (Paul)  "test the angle"
 *     c          (Mira)  "product angle"
 *   e            (Dana)  "design angle"
 */
const records = [
  { id: "a", parentId: null, author: "Sol", body: "let's talk routing" },
  { id: "b", parentId: "a", author: "Saul", body: "frontend angle" },
  { id: "d", parentId: "b", author: "Paul", body: "test the angle" },
  { id: "c", parentId: "a", author: "Mira", body: "product angle" },
  { id: "e", parentId: null, author: "Dana", body: "design angle" },
];
const model = buildModel(records);

test("buildModel derives depth from the parent chain", () => {
  assert.equal(model.depth.get("a"), 0);
  assert.equal(model.depth.get("b"), 1);
  assert.equal(model.depth.get("d"), 2);
  assert.equal(model.depth.get("e"), 0);
});

test("preorder walks reading order across the whole tree", () => {
  assert.equal(preorderNext(model, "a"), "b");
  assert.equal(preorderNext(model, "b"), "d");
  assert.equal(preorderNext(model, "d"), "c"); // back up and over to the sibling subtree
  assert.equal(preorderNext(model, "c"), "e");
  assert.equal(preorderPrev(model, "c"), "d");
  assert.equal(preorderPrev(model, "a"), null); // at the top
  assert.equal(preorderNext(model, "e"), null); // at the bottom
});

test("preorderNext with no/unknown current lands on the first node", () => {
  assert.equal(preorderNext(model, null), "a");
  assert.equal(preorderNext(model, "nope"), "a");
});

test("parent / first child (h and raw l)", () => {
  assert.equal(parentOf(model, "d"), "b");
  assert.equal(parentOf(model, "a"), null);
  assert.equal(firstChildOf(model, "a"), "b");
  assert.equal(firstChildOf(model, "d"), null); // leaf
});

test("siblings skip the subtree (J / K)", () => {
  assert.equal(nextSibling(model, "b"), "c");
  assert.equal(prevSibling(model, "c"), "b");
  assert.equal(nextSibling(model, "c"), null);
  // top-level nodes are siblings of each other
  assert.equal(nextSibling(model, "a"), "e");
  assert.equal(prevSibling(model, "e"), "a");
  // a lone child has no siblings
  assert.equal(nextSibling(model, "d"), null);
});

test("first / last node (gg / G)", () => {
  assert.equal(firstNode(model), "a");
  assert.equal(lastNode(model), "e"); // last in pre-order, not deepest
});

test("resolveDescend: remembered child wins, else first child", () => {
  const memory = new Map([["a", "c"]]); // we ascended from c
  assert.equal(resolveDescend(model, "a", memory), "c");
  assert.equal(resolveDescend(model, "a", new Map()), "b"); // raw → first child
  assert.equal(resolveDescend(model, "a"), "b"); // no memory arg → first child
  assert.equal(resolveDescend(model, "d", memory), null); // leaf
});

test("resolveDescend ignores stale memory that is no longer a child", () => {
  const memory = new Map([["a", "e"]]); // e is top-level, not a child of a
  assert.equal(resolveDescend(model, "a", memory), "b");
});

test("search forward/backward matches author or body, excludes current, wraps", () => {
  // forward by body
  assert.equal(search(model, "angle", "a", 1), "b");
  assert.equal(search(model, "angle", "b", 1), "d");
  // backward
  assert.equal(search(model, "angle", "d", -1), "b");
  // by author, case-insensitive
  assert.equal(search(model, "dana", "a", 1), "e");
  // wraps around the end
  assert.equal(search(model, "angle", "e", 1), "b");
  // current node excluded so n/N advance even when it matches
  assert.equal(search(model, "frontend", "b", 1), null);
  // no match anywhere
  assert.equal(search(model, "zzz", "a", 1), null);
  // empty query
  assert.equal(search(model, "  ", "a", 1), null);
});

test("search from null start scans from the appropriate end", () => {
  assert.equal(search(model, "angle", null, 1), "b"); // forward from before-start
  assert.equal(search(model, "angle", null, -1), "e"); // backward from after-end
});

test("matches returns all hits in pre-order (stretch results list)", () => {
  assert.deepEqual(matches(model, "angle"), ["b", "d", "c", "e"]);
  assert.deepEqual(matches(model, "routing"), ["a"]);
  assert.deepEqual(matches(model, ""), []);
});
