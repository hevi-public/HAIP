Feature: Composer & reply targeting
  PENDING the implementing team. The inline composer opens at the clicked node and (like the bottom
  composer) defaults to whole-topic scope — so the summoned persona reads the whole topic, matching the
  on-screen "looking at: whole topic" control; the owner narrows to this branch explicitly via /branch.
  The persistent bottom composer always targets level 0. Only one inline composer is open at a time
  (§4). Drafted as the executable spec.

  Background:
    Given a thread "Scaling SQLite" exists
    And a persona "sol" exists

  Scenario: Inline composer targets the clicked node and defaults to whole-topic scope
    Given a posted reply from "sol" saying "deep in a tangent"
    When the owner opens the inline composer on "sol"'s reply
    Then the composer targets that node
    # Scope is the context the persona READS — whole topic by default (matches the visible control), so a
    # reply on a deep node still sees sibling branches. Placement (targets that node) is independent.
    And the composer scope defaults to "WHOLE_THREAD"

  Scenario: The bottom composer always targets level 0
    When the owner uses the bottom composer
    Then the reply targets the post at level 0
    And the composer scope defaults to "WHOLE_THREAD"

  Scenario: The composer is wired to submit a summon via htmx
    When the owner uses the bottom composer
    Then the composer posts to the generation endpoint
    And the composer offers a text field and a persona picker
    And the composer summons on submit
