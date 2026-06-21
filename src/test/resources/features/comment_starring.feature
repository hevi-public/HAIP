Feature: Star a comment — the owner's bookmark in the branch index
  Starring is a navigation aid: the owner stars a comment to mark it, and the branch-index rail swaps
  that entry's colour dot for a star (in the same hue) so the bookmark is easy to find. Like +1 it is
  firewalled from the model — a pure UI marker — and it is a toggle: starring again removes it. The
  rail's data-starred hook is the authoritative server-rendered state (the live client mirror in nav.js
  is progressive enhancement on top of this).

  Background:
    Given a thread "Scaling SQLite" exists
    And a persona "sol" exists
    And a posted reply from "sol" saying "Indexes help here"

  Scenario: The star control appears on a posted reply
    When the owner views the thread page
    Then the star button is present on "sol"'s reply

  Scenario: A comment is unstarred by default
    When the owner views the thread page
    Then the branch index entry for "sol"'s reply is not starred

  Scenario: Starring a comment marks its branch index entry
    When the owner stars "sol"'s reply
    And the owner views the thread page
    Then the branch index entry for "sol"'s reply is starred

  Scenario: Starring is a toggle — a second star removes the mark
    When the owner stars "sol"'s reply
    And the owner stars "sol"'s reply
    And the owner views the thread page
    Then the branch index entry for "sol"'s reply is not starred
