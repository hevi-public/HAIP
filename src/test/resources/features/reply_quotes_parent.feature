Feature: A reply shows what it answers
  A reply carries an "in reply to" anchor — a LITERAL, truncated quote of its parent (never an AI
  summary), linking up to that comment — but ONLY when it's separated from that parent in the tree.
  A direct reply renders right under the comment it answers, so the quote would just echo the line
  above; the anchor earns its place once later siblings get pushed down past intervening sub-threads
  (the owner's original UX question; Dana's literal-quote rec).

  Background:
    Given a thread "Scaling SQLite" exists

  Scenario: A reply separated from its parent quotes the comment it answers
    Given a posted reply from "owner" saying "How should we index the comment tree?"
    And a posted reply from "ada" saying "Recursive CTE, surely" under "owner"'s reply
    And a posted reply from "sol" saying "A recursive CTE keeps it one query" under "owner"'s reply
    When the owner views the thread page
    Then "sol"'s reply has an in-reply-to anchor pointing at "owner"'s reply
    And "sol"'s in-reply-to anchor quotes "How should we index the comment tree?"

  Scenario: A direct reply renders right under its parent, so has no in-reply-to anchor
    Given a posted reply from "owner" saying "How should we index the comment tree?"
    And a posted reply from "sol" saying "A recursive CTE keeps it one query" under "owner"'s reply
    When the owner views the thread page
    Then "sol"'s reply has no in-reply-to anchor

  Scenario: A top-level reply answers the post, so has no in-reply-to anchor
    Given a posted reply from "owner" saying "Kicking this off"
    When the owner views the thread page
    Then "owner"'s reply has no in-reply-to anchor

  Scenario: A long parent is quoted truncated, not in full
    Given a posted reply from "owner" saying "Indexes are the obvious lever here but the recursive CTE that walks the comment tree is where the real cost hides on a cold cache PARENTTAIL"
    And a posted reply from "ada" saying "Agreed in principle" under "owner"'s reply
    And a posted reply from "sol" saying "Agreed" under "owner"'s reply
    When the owner views the thread page
    Then "sol"'s in-reply-to anchor is truncated with an ellipsis
    And "sol"'s in-reply-to anchor does not contain "PARENTTAIL"
