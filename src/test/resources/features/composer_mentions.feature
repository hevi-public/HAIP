Feature: Summon a persona by @mention
  The composer lets the owner summon a specific persona by typing "@name" in the message instead of
  picking from the dropdown (§4). The mention is parsed server-side, so it works with JS off and — on
  the "Anyone" path — pre-empts the AI dispatcher: naming someone is a deliberate summon, so the room
  doesn't get a vote. This routes through the same single LLM seam the other generation scenarios use.

  Background:
    Given a thread "Scaling SQLite" exists
    And a persona "Sol" exists
    And a persona "Paul" exists

  Scenario: An @mention summons the named persona and skips the dispatcher
    # Only ONE reply is scripted: if the dispatcher were consulted it would consume this as its routing
    # pick (naming neither persona → fall back to the whole roster → a second draft with no script). So a
    # green run proves the mention pre-empted routing — exactly one persona, the one named, replies.
    Given the LLM will respond with "An index on the lookup column"
    When the owner asks the room "@Paul how do we make these queries faster?"
    Then the reply is "posted"
    And the reply author is "Paul"
    And the reply body contains "An index on the lookup column"
