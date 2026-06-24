Feature: Routing observability — Admin → Statistics
  GET /admin/stats surfaces how the "Anyone" dispatcher is doing. Every routing decision records exactly
  one outcome, and the page shows the per-outcome breakdown plus the headline parse-miss rate — how often
  the model replied but named no roster member, so we silently widened to the whole room.

  Background:
    Given a thread "Scaling SQLite" exists
    And a persona "Sol" exists
    And a persona "Paul" exists

  Scenario: The stats page reports no rate before any routing has happened
    When the owner visits the routing stats page
    Then the routing stats page has no parse-miss rate yet

  Scenario: Routing outcomes are counted and the parse-miss rate is shown
    # First ask: the dispatcher names Sol → MATCHED. Second ask: an unparseable answer → WIDENED_NO_MATCH.
    Given the LLM will respond with "Sol"
    And the LLM will respond with "indexes help here"
    When the owner asks the room "how do we make these faster?"
    Then the reply is "posted"
    Given the LLM will respond with "not sure who"
    And the LLM will respond with "happy to take a look"
    When the owner asks the room "any thoughts on this?"
    Then the reply is "posted"
    When the owner visits the routing stats page
    Then the routing stats page counts 1 "MATCHED" event
    And the routing stats page counts 1 "WIDENED_NO_MATCH" event
    And the routing stats page shows a parse-miss rate of "50%"
