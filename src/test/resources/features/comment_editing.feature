Feature: Editing a comment, an AI reply, and the opening post

  The owner can revise a posted body (§7): their own note, or an AI persona's reply when it misread the
  context — the corrected text then seeds future summons in that branch. Editing stamps an "(edited)"
  marker (data-edited) and never touches the author or the tree structure. Only posted nodes are
  editable, and a blank body is rejected. The opening post (title + body) is editable the same way.

  Background:
    Given a thread "Ideas" exists
    And a persona "sol" exists

  Scenario: The edit button is offered on a posted reply
    Given a posted reply from "sol" saying "First take"
    When the owner views the thread page
    Then the edit button is present on "sol"'s reply

  Scenario: A drafting reply offers no edit button — nothing has settled to edit
    Given a drafting reply from "sol" saying "thinking…"
    When the owner views the thread page
    Then the edit button is not present on "sol"'s reply

  Scenario: The owner edits their own note
    Given a posted reply from "owner" saying "My orignal note"
    When the owner edits "owner"'s reply to say "My corrected note"
    Then "owner"'s reply body shows "My corrected note"
    And "owner"'s reply is marked edited

  Scenario: The owner corrects an AI persona's reply
    Given a posted reply from "sol" saying "You meant Postgres."
    When the owner edits "sol"'s reply to say "You meant SQLite."
    Then "sol"'s reply body shows "You meant SQLite."
    And "sol"'s reply is marked edited

  Scenario: Editing a reply keeps its nested replies
    Given a posted reply from "sol" saying "Parent"
    And a posted reply from "owner" saying "Child" under "sol"'s reply
    When the owner edits "sol"'s reply to say "Parent revised"
    Then "sol"'s reply body shows "Parent revised"
    And the thread still shows "owner"'s reply

  Scenario: A blank edit is rejected — the body is left untouched
    Given a posted reply from "sol" saying "Keep me"
    When the owner edits "sol"'s reply to say ""
    Then "sol"'s reply body shows "Keep me"
    And "sol"'s reply is not marked edited

  Scenario: The owner edits the opening post title and body
    When the owner edits the opening post to title "Better ideas" and body "A clearer opening."
    And the owner views the thread page
    Then the thread page shows "Better ideas"
    And the opening post body shows "A clearer opening."
    And the opening post is marked edited
