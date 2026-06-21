Feature: The thread rail shows a branch index
  The thread view reserves a side rail (the full-bleed layout work). Its first occupant is the
  "branch index" the design calls for — a table of contents of the posted comments, in tree order,
  each a jump link to its node. The box is always present (so the layout doesn't pop from one column
  to two when the first reply lands); before anything is posted it shows an empty state.

  Background:
    Given a thread "Scaling SQLite" exists

  Scenario: The rail lists every posted comment as a jump link, with a preview
    Given a posted reply from "owner" saying "How do we scale?"
    And a posted reply from "sol" saying "Indexes help here"
    And a posted reply from "mira" saying "WAL mode too"
    When the owner views the thread page
    Then the thread rail shows a branch index
    And the branch index lists 3 entries
    And the branch index has an entry for "sol"'s reply
    And the branch index entry for "sol"'s reply shows "sol"
    And the branch index entry for "sol"'s reply shows "Indexes help here"
    And every branch index entry links to a comment anchored on the page

  Scenario: A long comment is previewed truncated in the branch index, so it can't overflow
    Given a posted reply from "owner" saying "Indexes are the obvious lever but the recursive CTE is where the cost hides BRANCHTAIL"
    When the owner views the thread page
    Then the branch index entry for "owner"'s reply is truncated with an ellipsis
    And the branch index entry for "owner"'s reply does not contain "BRANCHTAIL"

  Scenario: A thread with no posted comments still shows the branch index, empty
    When the owner views the thread page
    Then the thread rail shows a branch index
    And the branch index lists 0 entries
    And the branch index shows an empty state
