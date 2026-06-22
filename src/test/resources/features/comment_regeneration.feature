Feature: Regenerating an AI persona's reply, keeping every version

  The owner can regenerate a posted persona reply (§7): re-run the model for a fresh take WITHOUT losing
  the old one. Each regenerate appends a content revision, so the node shows a "2/3" switcher and the owner
  can step back to any earlier take. Regenerate is offered only on a posted persona reply — never on the
  owner's own notes — and a regenerate never touches the reply's nested children.

  Background:
    Given a thread "Ideas" exists
    And a persona "sol" exists

  Scenario: The regenerate button is offered on a posted persona reply
    Given a posted reply from "sol" saying "First take"
    When the owner views the thread page
    Then the regenerate button is present on "sol"'s reply

  Scenario: An owner note offers no regenerate button — there is nothing to regenerate
    Given a posted reply from "owner" saying "My own note"
    When the owner views the thread page
    Then the regenerate button is not present on "owner"'s reply

  Scenario: A reply with a single take shows no version indicator
    Given a posted reply from "sol" saying "Only take"
    When the owner views the thread page
    Then "sol"'s reply has no version indicator

  Scenario: Regenerating appends a new version and shows it
    Given a posted reply from "sol" saying "First take"
    And the LLM will respond with "Second take"
    When the owner regenerates "sol"'s reply
    Then "sol"'s reply body shows "Second take"
    And "sol"'s reply shows version 2 of 2

  Scenario: The owner can step back to an earlier version, keeping the original take
    Given a posted reply from "sol" saying "First take"
    And the LLM will respond with "Second take"
    When the owner regenerates "sol"'s reply
    And the owner switches "sol"'s reply to version 1
    Then "sol"'s reply body shows "First take"
    And "sol"'s reply shows version 1 of 2

  Scenario: Regenerating keeps the reply's nested replies
    Given a posted reply from "sol" saying "Parent"
    And a posted reply from "owner" saying "Child" under "sol"'s reply
    And the LLM will respond with "Parent regenerated"
    When the owner regenerates "sol"'s reply
    Then "sol"'s reply body shows "Parent regenerated"
    And the thread still shows "owner"'s reply
