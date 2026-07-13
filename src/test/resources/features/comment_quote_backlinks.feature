Feature: A quoted comment shows who quoted it (backlinks)
  The backward direction of the quote graph (plan_docs/comment-quotes.md §6): a comment that has been
  quoted carries a "quoted by" backlink block — server-rendered, so it's the no-JS fallback AND the data
  the client promotes into an inline highlight + hover cone. Quotes of the SAME passage coalesce
  (per-exact-span), so the count and the distinct-passage groups are what we assert here; the inline
  highlight + cone are verified in the preview (the browser-only half, like nav.js).

  Background:
    Given a thread "Scaling SQLite" exists
    And a posted reply from "sol" saying "A recursive CTE keeps the tree query to a single round-trip on a cold cache"

  Scenario: A quoted comment links back to the comment that quoted it
    When the owner posts a reply "Strong agree" quoting "recursive CTE keeps the tree query" from "sol"'s reply
    And the owner views the thread page
    Then "sol"'s reply is quoted by "owner"'s reply
    And "sol"'s reply shows it was quoted 1 times

  Scenario: Several replies quoting the same passage coalesce into one
    When a reply quoting "recursive CTE keeps the tree query" from "sol"'s reply is posted
    And a reply quoting "recursive CTE keeps the tree query" from "sol"'s reply is posted
    And the owner views the thread page
    Then "sol"'s reply shows it was quoted 2 times
    And "sol"'s reply has 1 distinct quoted passages

  Scenario: Quotes of different passages stay separate
    When a reply quoting "recursive CTE" from "sol"'s reply is posted
    And a reply quoting "single round-trip" from "sol"'s reply is posted
    And the owner views the thread page
    Then "sol"'s reply shows it was quoted 2 times
    And "sol"'s reply has 2 distinct quoted passages

  Scenario: A comment nobody has quoted has no backlinks
    When the owner views the thread page
    Then "sol"'s reply has no backlinks

  Scenario: A typed or persona markdown blockquote counts as a quote
    Given a posted reply from "saul" saying "> recursive CTE keeps the tree query"
    When the owner views the thread page
    Then "sol"'s reply is quoted by "saul"'s reply
    And "sol"'s reply shows it was quoted 1 times

  Scenario: A typed blockquote coalesces with a toolbar quote of the same passage
    Given a posted reply from "saul" saying "> recursive CTE keeps the tree query"
    When the owner posts a reply "Me too" quoting "recursive CTE keeps the tree query" from "sol"'s reply
    And the owner views the thread page
    Then "sol"'s reply shows it was quoted 2 times
    And "sol"'s reply has 1 distinct quoted passages
