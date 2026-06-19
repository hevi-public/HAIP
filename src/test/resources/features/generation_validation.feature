Feature: Composer validation
  Validation happens before spending an LLM call (§4): an empty question or no persona selected is
  rejected inline at the controller tier, no node is created, and the model is never invoked.

  Background:
    Given a thread "Scaling SQLite" exists
    And a persona "sol" exists

  Scenario: Empty question is rejected before any LLM call
    When the owner submits a generation with empty text
    Then the reply is "failed"
    And the reply failureCategory is "VALIDATION"
    And no LLM call was made

  Scenario: No persona selected is rejected before any LLM call
    When the owner submits a generation with no persona selected
    Then the reply is "failed"
    And the reply failureCategory is "VALIDATION"
    And no LLM call was made
