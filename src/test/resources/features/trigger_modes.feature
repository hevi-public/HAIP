Feature: Generation triggers — sequential fan-out and partial-roomful
  Fan-out runs sequentially in M1 (§4), and crucially one persona failing does NOT abort the room: the
  others still post (partial-roomful). LLM behaviours are enqueued in persona order.

  Background:
    Given a thread "Scaling SQLite" exists
    And a persona "sol" exists
    And a persona "vex" exists
    And a persona "pike" exists

  Scenario: One persona fails, the rest of the room still posts
    Given the LLM will respond with "sol's take"
    And the LLM will fail with a timeout
    And the LLM will respond with "pike's take"
    When the owner fans out to "sol, vex, pike"
    Then exactly 2 replies are posted
    And exactly 1 reply is failed
