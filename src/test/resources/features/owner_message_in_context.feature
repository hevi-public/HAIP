Feature: The owner's composed message is posted and seeds the room
  When the owner submits the composer, the text they wrote IS the discussion: it must be persisted as
  the owner's own node — so it appears in the thread — AND handed to every summoned persona as context
  — so replies engage with the question instead of emitting a generic opener (§4/§5). Without this the
  owner's words vanish and each persona only ever sees a blank transcript.

  Background:
    Given a thread "Scaling SQLite" exists
    And a persona "sol" exists

  Scenario: The composed text is posted as the owner's node and reaches the persona
    Given the LLM will respond with "Indexes help here"
    When the owner submits the bottom composer with text "How do we scale?" selecting "sol"
    Then the thread shows the owner's post "How do we scale?"
    And the model context includes node "How do we scale?"
    And the reply body contains "Indexes help here"
