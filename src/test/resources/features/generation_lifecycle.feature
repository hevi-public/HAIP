Feature: Generation lifecycle
  Every generation moves through visible states (§4). The happy path: an owner summons a persona and
  the reply transitions drafting → posted.

  Background:
    Given a thread "Scaling SQLite" exists
    And a persona "sol" exists

  Scenario: Owner summons a persona and the reply posts
    Given the LLM will respond with "Indexes help here"
    When the owner summons "sol"
    Then the reply is "posted"
    And the reply body contains "Indexes help here"
