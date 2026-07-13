Feature: Deleting a comment — cascade to its replies

  The owner can remove a comment from the tree (§7). Deletion cascades: a comment's whole subtree
  of replies is removed with it, so no child is ever orphaned pointing at a gone parent. Sibling
  branches that don't descend from the deleted node are untouched.

  Background:
    Given a thread "Ideas" exists
    And a persona "sol" exists
    And a persona "vex" exists
    And a persona "pike" exists
    And a posted reply from "sol" saying "Parent thought"
    And a posted reply from "vex" saying "Child thought" under "sol"'s reply

  Scenario: The delete button is offered on a posted reply
    When the owner views the thread page
    Then the delete button is present on "sol"'s reply

  Scenario: Deleting a reply removes it and its descendants
    When the owner deletes "sol"'s reply
    And the owner views the thread page
    Then the thread no longer shows "sol"'s reply
    And the thread no longer shows "vex"'s reply

  Scenario: Deleting a reply leaves sibling branches intact
    Given a posted reply from "pike" saying "Separate branch"
    When the owner deletes "sol"'s reply
    And the owner views the thread page
    Then the thread no longer shows "sol"'s reply
    And the thread still shows "pike"'s reply
