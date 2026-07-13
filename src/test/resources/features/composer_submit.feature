Feature: Composer submit (form-encoded)
  The browser composer posts the htmx form as application/x-www-form-urlencoded to the same
  generation endpoint the JSON API uses; the returned reply-node fragment is what htmx swaps into the
  page (§4). This pins the form-binding path the JSON acceptance scenarios don't exercise. The actual
  in-browser DOM swap is verified separately via the preview/verify harness.

  Background:
    Given a thread "Scaling SQLite" exists
    And a persona "sol" exists

  Scenario: Submitting the bottom composer posts a reply
    Given the LLM will respond with "Indexes help here"
    When the owner submits the bottom composer with text "How do we scale?" selecting "sol"
    Then the reply is "posted"
    And the reply body contains "Indexes help here"

  Scenario: The composer offers a one-click /ask shortcut button
    # Note is the default (the composer posts a silent owner comment), so the footer toggle offers the
    # OTHER mode — /ask — like /more on a reply: summoning a persona is one click. Both /note and /ask
    # also live in the slash palette (type "/").
    When the owner uses the bottom composer
    Then the composer offers a "/ask" shortcut button
