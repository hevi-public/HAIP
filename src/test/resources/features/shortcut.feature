Feature: Shortcut ticketing surfaces (read-only)
  The forum can display read-only Shortcut stories on three surfaces: a right-rail box on the home and
  thread pages, a browsable /shortcut page with a source switcher, and inline links for bare sc-N
  references in comment bodies. All three degrade gracefully — dark when the integration is off, a quiet
  note (never a 500) when a call fails.

  Background:
    Given the Shortcut integration is active

  Scenario: the right-rail Shortcut box lists stories on the front page
    Given Shortcut has the story sc-123 "Fix the login bug" of type "bug" in state "In Progress"
    When the owner opens the front page
    Then the page shows the "shortcut" rail box
    And the Shortcut box lists the story "sc-123"
    And the Shortcut story "sc-123" shows the state "In Progress"

  Scenario: the Shortcut box stays dark when the integration is off
    Given the Shortcut integration is off
    When the owner opens the front page
    Then the page does not show the "shortcut" rail box

  Scenario: the Shortcut box shows a quiet note when no stories match
    When the owner opens the front page
    Then the page shows the "shortcut" rail box
    And the Shortcut box shows an empty state

  Scenario: the /shortcut page lists stories and offers all three sources
    Given Shortcut has the story sc-9 "Ship the thing" of type "feature" in state "Done"
    When the owner opens the Shortcut page
    Then the Shortcut page lists the story "sc-9"
    And the Shortcut page offers the "query" source
    And the Shortcut page offers the "recent" source
    And the Shortcut page offers the "owner" source

  Scenario: the owner's-stories source queries by the configured owner
    When the owner opens the Shortcut page source "owner"
    Then Shortcut was queried with "owner:owner"

  Scenario: the /shortcut page shows a quiet error when Shortcut can't be reached
    Given the next Shortcut call fails
    When the owner opens the Shortcut page
    Then the Shortcut page shows a Shortcut error

  Scenario: the /shortcut page explains itself when the integration is off
    Given the Shortcut integration is off
    When the owner opens the Shortcut page
    Then the Shortcut page shows the integration-disabled note

  Scenario: a bare sc-N reference in an opening post links to the story
    When the owner starts a thread titled "Planning" with body "let's track this in sc-42 today" from the browser
    And the owner views the thread page
    Then the opening post links sc-42 to Shortcut
