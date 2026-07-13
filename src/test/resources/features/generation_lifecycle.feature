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
  # "failed") and can simply be drafted again. This is the TRUE owner-initiated in-flight cancel: the
  # draft is started async (the worker hangs in the LlmClient seam), the owner trips it via the cancel
  # endpoint, and the shared CancellationToken kills the in-flight generation → CANCELLED.
  Scenario: A cancelled draft is neutral, not an error, and can be drafted again
    Given the generation hangs until cancelled
    When the owner starts a draft from "sol"
    Then the reply is "drafting"
    When the owner cancels the draft
    Then the reply is "cancelled"
    And the reply failureCategory is "CANCELLED"
    And the reply is not "failed"
    Given the LLM will respond with "drafted again"
    When the owner retries the reply
    Then the reply is "posted"
    And the reply body contains "drafted again"

  # A leaked chain-of-thought is NOT a failure (§4): the body is cleaned and the reply still posts, only
  # flagged so the reader knows the model leaked its reasoning. ACTUAL = we stripped <think> tags;
  # POSSIBLE = a heuristic suspected untagged "thinking" preamble. The data-reasoning-leak hook drives the
  # badge; the body survives either way.
  Scenario Outline: A reply that leaks the model's reasoning still posts, cleaned and flagged
    Given the LLM responds with "Indexes help here" flagged as a <kind> reasoning leak
    When the owner summons "sol"
    Then the reply is "posted"
    And the reply is not "failed"
    And the reply body contains "Indexes help here"
    And the reply reasoning-leak is "<attr>"

    Examples:
      | kind     | attr     |
      | ACTUAL   | actual   |
      | POSSIBLE | possible |
