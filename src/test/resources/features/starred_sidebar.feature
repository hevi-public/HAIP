Feature: Starred comments rail box — cross-thread bookmarks in the right rail
  The "Starred" box appears in the right rail on both the home page and every thread page.
  It lists all starred POSTED comments across threads (newest first, capped at five), linking
  back to each comment in its thread. An empty state keeps the layout stable before anything
  is starred. A "See all" link leads to /starred.

  Background:
    Given a thread "Scaling SQLite" exists
    And a persona "sol" exists
    And a posted reply from "sol" saying "Indexes help here"

  Scenario: The starred box shows an empty state on the home page before anything is starred
    When the owner opens the front page
    Then the front page shows the "starred-comments" rail box
    And the "starred-comments" rail shows an empty state

  Scenario: The starred box appears on the thread page
    When the owner views the thread page
    Then the page shows the "starred-comments" rail box

  Scenario: Starring a comment makes it appear in the starred box on the home page
    When the owner stars "sol"'s reply
    And the owner opens the front page
    Then the "starred-comments" rail shows "Indexes help here"
    And the "starred-comments" rail lists 1 entries

  Scenario: Starring a comment makes it appear in the starred box on the thread page
    When the owner stars "sol"'s reply
    And the owner views the thread page
    Then the "starred-comments" rail shows "Indexes help here"

  Scenario: The starred box always links to the starred page
    When the owner opens the front page
    Then the starred rail links to "/starred"
