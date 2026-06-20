Feature: Generation sad paths
  The sad path is first-class (§4): every failure mode is simulated at the Tier-1 IO seam, surfaces as
  the right UX state, and offers a working retry. Each failure here is injected into the LlmClient fake.

  Background:
    Given a thread "Scaling SQLite" exists
    And a persona "sol" exists

  Scenario Outline: A generation failure surfaces the right state with a working retry
    Given the LLM will fail with a <failureMode>
    When the owner summons "sol"
    Then the reply is "failed"
    And the reply failureCategory is "<category>"
    And the reply retryable is "<retryable>"
    Given the LLM will respond with "recovered after the failure"
    When the owner retries the reply
    Then the reply is "posted"
    And the reply body contains "recovered after the failure"

    Examples:
      | failureMode   | category     | retryable |
      | timeout       | FAILED_RETRY | true      |
      | process error | FAILED_RETRY | true      |
      | empty output  | FAILED_RETRY | true      |
      | malformed     | FAILED_RETRY | true      |
      | rate-limit    | RATE_LIMITED | true      |

  # UX state E (§4): the generation succeeds but the write fails. This is NOT an LlmException, so it
  # lives outside the outline above — the fault is injected at the repository write seam, and the
  # drafted text must survive so the owner can retry rather than lose their reply.
  Scenario: A save failure keeps the drafted reply and offers a working retry
    Given the LLM will respond with "Indexes help here"
    And the next save will fail
    When the owner summons "sol"
    Then the reply is "failed"
    And the reply failureCategory is "COULDNT_SAVE"
    And the reply body contains "Indexes help here"
    And the reply retryable is "true"
    Given the LLM will respond with "Indexes help here"
    When the owner retries the reply
    Then the reply is "posted"
    And the reply body contains "Indexes help here"
