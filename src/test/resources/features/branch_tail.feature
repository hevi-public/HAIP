Feature: Composer replies continue at the branch tail
  When the owner writes into a node's inline composer, the message attaches at the END of that branch —
  under its last node — so the conversation extends rather than forking off whatever node the owner
  happened to click Reply on (§4/§5). The persona then answers beneath that.

  Background:
    Given a thread "Scaling SQLite" exists
    And a persona "sol" exists
    # a branch that has already continued past its head:  H (owner) → G (sol)
    And a root comment "H" by "owner"
    And a reply "G" under "H" by "sol"

  Scenario: An inline reply attaches under the branch's last node, not the clicked node
    Given the LLM will respond with "happy to continue"
    When the owner replies inline on "H" with text "one more please" selecting "sol"
    Then the new owner message is nested under "G"
