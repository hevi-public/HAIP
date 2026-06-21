Feature: Composer replies attach to the comment you clicked
  When the owner writes into a node's inline composer, the message attaches directly under THAT node —
  the comment they clicked Reply on — even when the branch has already continued past it. The live htmx
  swap appends under the clicked node and the persisted tree parents it there too, so a reply never
  "jumps" to a different node on refresh (§4/§5). The persona then answers beneath the owner's message.

  Background:
    Given a thread "Scaling SQLite" exists
    And a persona "sol" exists
    # a branch that has already continued past its head:  H (owner) → G (sol)
    And a root comment "H" by "owner"
    And a reply "G" under "H" by "sol"

  Scenario: An inline reply attaches under the clicked node, not the branch's tail
    Given the LLM will respond with "happy to continue"
    When the owner replies inline on "H" with text "one more please" selecting "sol"
    Then the new owner message is nested under "H"
