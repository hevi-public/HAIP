Feature: Per-branch context scoping
  The product differentiator (§5): a reply can be generated with branch-only scope (the root → parent
  ancestor path, via recursive CTE) or whole-thread scope. Branch-only excludes siblings. Asserted by
  spying on the exact PromptContext handed to the model.

  Background:
    Given a thread "Scaling SQLite" exists
    And a persona "sol" exists
    # tree:  R ─┬─ A ── A1
    #          └─ B
    And a root comment "R" by "owner"
    And a reply "A" under "R" by "vex"
    And a reply "B" under "R" by "pike"
    And a reply "A1" under "A" by "sol"

  Scenario: Branch-only scope sends just the ancestor path, not siblings
    Given the LLM will respond with "scoped reply"
    When the owner replies under "A1" with branch-only scope
    Then the model context includes node "A1"
    And the model context includes node "A"
    And the model context includes node "R"
    And the model context excludes node "B"

  Scenario: Whole-thread scope sees the whole tree, including siblings
    Given the LLM will respond with "broad reply"
    When the owner replies under "A1" with whole-thread scope
    Then the model context includes node "A1"
    And the model context includes node "B"

  # §5: sibling-inclusion is a selectable toggle, orthogonal to branch-vs-thread. Replying under "A"
  # with branch-only scope normally excludes its sibling "B"; opting siblings in adds "B" while still
  # staying off the whole thread (the deeper node "A1" remains out of context).
  Scenario: Branch scope can opt to include siblings
    Given the LLM will respond with "scoped reply"
    When the owner replies under "A" with branch-only scope including siblings
    Then the model context includes node "A"
    And the model context includes node "R"
    And the model context includes node "B"
    And the model context excludes node "A1"
