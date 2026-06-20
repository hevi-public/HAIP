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
