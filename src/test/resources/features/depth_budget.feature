Feature: Depth-budget autonomy
  A thread auto-grows ~3–4 reply levels past the owner's last comment, then stalls; an owner comment or
  /more re-grants budget; the budget is per-branch (§4/§7).

  Background:
    Given a thread "Scaling SQLite" exists
    And a persona "sol" exists

  Scenario: Autonomous growth stalls when the depth budget is exhausted
    Given the owner has commented at level 0
    When the room auto-replies
    Then auto-replies stop after about 4 levels

  Scenario: An owner reply re-grants depth budget on that branch
    Given a branch whose depth budget is exhausted
    When the owner replies on that branch
    Then auto-replies resume on that branch
    And other branches stay quiet

  Scenario: /more grants depth budget and is visible to the model
    Given a branch whose depth budget is exhausted
    When the owner invokes /more on that branch
    Then the branch is granted about 3 to 4 more levels
    And the /more directive appears in the context handed to the model
