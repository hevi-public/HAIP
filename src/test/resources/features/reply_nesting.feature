Feature: Replies nest under the message they answer
  The thread page renders the comment TREE, not a flat list: a persona's reply sits nested inside the
  owner's message it answered, so the shape of the conversation is visible (§4/§5). A regression that
  renders every node at level 0 — both messages present but as siblings — must fail this.

  Background:
    Given a thread "Scaling SQLite" exists
    And a persona "sol" exists

  Scenario: A summoned reply renders nested under the owner's message
    Given the LLM will respond with "Indexes help here"
    When the owner submits the bottom composer with text "How do we scale?" selecting "sol"
    # The htmx swap payload (what the browser appends live, before any refresh) must already nest…
    Then the returned fragment nests "sol"'s draft under the owner's message
    # …and so must the re-fetched thread page (server-side tree assembly).
    And "sol"'s reply renders nested under the owner's message
