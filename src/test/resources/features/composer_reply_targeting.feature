Feature: Composer & reply targeting
  PENDING the implementing team. The inline composer opens at the clicked node and defaults to
  this-branch scope; the persistent bottom composer always targets level 0 and defaults to whole-thread;
  only one inline composer is open at a time (§4). Drafted as the executable spec.

  Background:
    Given a thread "Scaling SQLite" exists
    And a persona "sol" exists

  Scenario: Inline composer targets the clicked node and defaults to branch scope
    Given a posted reply from "sol" saying "deep in a tangent"
    When the owner opens the inline composer on "sol"'s reply
    Then the composer targets that node
    And the composer scope defaults to "BRANCH_ONLY"

  Scenario: The bottom composer always targets level 0
    When the owner uses the bottom composer
    Then the reply targets the post at level 0
    And the composer scope defaults to "WHOLE_THREAD"

  Scenario: The composer is wired to submit a summon via htmx
    When the owner uses the bottom composer
    Then the composer posts to the generation endpoint
    And the composer offers a text field and a persona picker
    And the composer summons on submit
