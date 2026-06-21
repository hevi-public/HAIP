Feature: The front page shows the side-rail boxes
  The home page frames the thread index between two rails of side boxes (the design's left/right
  rails). The left rail carries a "~/forum" nav (live counts) and a Members roster; the right rail
  carries Recent comments, Active threads, and an Ask-the-room CTA. Each box is always present so the
  layout doesn't pop as content lands — data-bearing boxes show an empty state until they're filled.

  Scenario: The left rail lists the forum members
    Given a persona "Sol" exists
    And a persona "Mira" exists
    When the owner opens the front page
    Then the front page shows the "members" rail box
    And the "members" rail lists 2 entries
    And the "members" rail has an entry for "Sol"
    And the "members" rail has an entry for "Mira"

  Scenario: The Members rail shows an empty state when no personas exist
    When the owner opens the front page
    Then the front page shows the "members" rail box
    And the "members" rail shows an empty state

  Scenario: The left rail nav links to threads and personas with live counts
    Given a thread "Scaling SQLite" exists
    And a persona "Sol" exists
    When the owner opens the front page
    Then the front page shows the "forum-nav" rail box
    And the "forum-nav" rail has an entry for "threads"
    And the "forum-nav" rail has an entry for "personas"

  Scenario: The right rail lists the active threads
    Given a thread "Scaling SQLite" exists
    And a thread "Indexing strategies" exists
    When the owner opens the front page
    Then the front page shows the "active-threads" rail box
    And the "active-threads" rail lists 2 entries

  Scenario: The right rail lists recent comments across threads
    Given a thread "Scaling SQLite" exists
    And a posted reply from "Sol" saying "Indexes help here"
    When the owner opens the front page
    Then the front page shows the "recent-comments" rail box
    And the "recent-comments" rail lists 1 entries
    And the "recent-comments" rail shows "Indexes help here"

  Scenario: The Recent-comments rail shows an empty state before anyone has posted
    Given a thread "Scaling SQLite" exists
    When the owner opens the front page
    Then the front page shows the "recent-comments" rail box
    And the "recent-comments" rail shows an empty state
