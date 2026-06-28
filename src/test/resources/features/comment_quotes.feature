Feature: Quoting another comment links the two
  Selecting text in a comment and choosing "Quote" drops it into a reply as a markdown blockquote AND
  records a quote edge, so the quoting reply shows a forward "↗ author" anchor that jumps to the source
  comment (the forward half of the quote graph — plan_docs/comment-quotes.md). The browser selection +
  context menu is verified in the preview; here we drive the server contract: the quotesJson the composer
  sends becomes a persisted, rendered link, robust to anything else on the page.

  Background:
    Given a thread "Scaling SQLite" exists
    And a posted reply from "sol" saying "A recursive CTE keeps the tree query to one round-trip"

  Scenario: A reply that quotes another shows a forward anchor to it
    When the owner posts a reply "Strong agree, that's the move" quoting "recursive CTE keeps the tree query" from "sol"'s reply
    And the owner views the thread page
    Then "owner"'s reply quotes "sol"'s reply
    And "owner"'s quote of "sol"'s reply shows "recursive CTE keeps the tree query"

  Scenario: A reply can quote several comments at once
    Given a posted reply from "ada" saying "Watch the busy_timeout under WAL"
    When the owner posts a reply "Both of these matter" quoting "recursive CTE" from "sol"'s reply and "busy_timeout" from "ada"'s reply
    And the owner views the thread page
    Then "owner"'s reply quotes "sol"'s reply
    And "owner"'s reply quotes "ada"'s reply

  Scenario: A reply with no quotes carries no forward anchor
    When the owner posts a reply "Just thinking out loud" with no quotes
    And the owner views the thread page
    Then "owner"'s reply has no quote anchors

  Scenario: A quote pointing at a comment that does not exist is ignored
    When the owner posts a reply "Ghost quote" quoting "vanished text" from a missing comment
    And the owner views the thread page
    Then "owner"'s reply has no quote anchors
