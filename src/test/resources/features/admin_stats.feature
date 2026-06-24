Feature: Admin statistics dashboard — /admin
  GET /admin shows a read-only snapshot of forum-wide statistics. The page is public (the app has
  no auth layer) and only reads — no settings, no mutations in this slice. Numbers are asserted via
  the stable data-stat hooks so the scenarios survive a visual redesign.

  Scenario: The dashboard renders with zeroes on an empty forum
    When the owner visits the admin page
    Then the admin dashboard is shown
    And the admin statistic "threads" is 0
    And the admin statistic "personas" is 0
    And the admin statistic "comments-total" is 0
    And the admin statistic "comments-posted" is 0

  Scenario: The dashboard counts threads, personas and posted comments
    Given a thread "Scaling SQLite" exists
    And a persona "sol" exists
    And a posted reply from "sol" saying "Indexes help here"
    When the owner visits the admin page
    Then the admin statistic "threads" is 1
    And the admin statistic "personas" is 1
    And the admin statistic "comments-total" is 1
    And the admin statistic "comments-posted" is 1
