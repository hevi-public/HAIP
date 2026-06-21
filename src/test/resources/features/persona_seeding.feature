Feature: The forum is seeded with a default persona team
  A fresh forum needs a usable set of personas without the owner hand-authoring them first, so the
  predefined personas in config (aiforum.seed.personas) are inserted on first startup if they are
  absent. The seed is idempotent: a reboot never duplicates a persona or clobbers an owner's edits (§6).

  Scenario: The predefined personas are seeded into an empty forum
    Given an empty forum
    When the predefined personas are seeded
    Then every predefined persona appears in the members list

  Scenario: Re-seeding never duplicates the predefined personas
    Given an empty forum
    And the predefined personas have already been seeded
    When the predefined personas are seeded again
    Then no personas are added the second time
    And every predefined persona appears exactly once in the members list
