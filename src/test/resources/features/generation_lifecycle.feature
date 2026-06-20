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

  # UX state C (§4): a cancelled draft is the owner's choice, not a failure — it reads neutral (never
  # "failed") and can simply be drafted again. (True owner-initiated in-flight cancel — a cancel
  # endpoint that trips the CancellationToken against the ScriptableLlmClient's HangUntilCancelled
  # behaviour — needs async generation and rides with the deferred roomful concurrency; the seam is
  # already in place for the team.)
  Scenario: A cancelled draft is neutral, not an error, and can be drafted again
    Given the generation will be cancelled
    When the owner summons "sol"
    Then the reply is "cancelled"
    And the reply failureCategory is "CANCELLED"
    And the reply is not "failed"
    Given the LLM will respond with "drafted again"
    When the owner retries the reply
    Then the reply is "posted"
    And the reply body contains "drafted again"
