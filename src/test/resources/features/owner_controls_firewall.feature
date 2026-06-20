Feature: Owner controls — the +1 firewall
  The anti-sycophancy core (§7/§13): the owner's +1 is recorded with full attribution and shown to the
  owner, but it is firewalled at the prompt boundary — it never appears in the context handed to a
  model. Asserted by spying on what the LlmClient actually received.

  Background:
    Given a thread "Scaling SQLite" exists
    And a persona "sol" exists
    And a persona "vex" exists

  Scenario: Owner +1 is recorded and shown, but never reaches the model
    Given a posted reply from "sol" saying "Indexes help here"
    And the LLM will respond with "Vex builds on that"
    When the owner gives a +1 to "sol"'s reply
    Then the owner sees a vote count of 1 on "sol"'s reply
    When the owner summons "vex"
    Then the model's context included "sol"'s words "Indexes help here"
    And the model's context contained no vote signal
