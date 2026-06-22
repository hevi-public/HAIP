Feature: Starred comments page — /starred
  GET /starred lists all starred POSTED comments across threads, grouped by thread.
  An empty state is shown when nothing has been starred yet.

  Scenario: The starred page has an empty state when nothing is starred
    When the owner visits the starred page
    Then the starred page has an empty state

  Scenario: The starred page lists starred comments with their thread title
    Given a thread "Scaling SQLite" exists
    And a persona "sol" exists
    And a posted reply from "sol" saying "Indexes help here"
    And the owner has starred "sol"'s reply
    When the owner visits the starred page
    Then the starred page shows "Indexes help here"
    And the starred page shows "Scaling SQLite"
    And the starred page has an entry for "sol"'s reply

  Scenario: Unstarred comments are not shown on the starred page
    Given a thread "Scaling SQLite" exists
    And a persona "sol" exists
    And a posted reply from "sol" saying "Indexes help here"
    When the owner visits the starred page
    Then the starred page has an empty state
