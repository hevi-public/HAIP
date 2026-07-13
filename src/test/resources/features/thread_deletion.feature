Feature: Deleting a thread — cascade to its comments

  The owner can remove a whole thread from the front page (§8). Deletion cascades: the thread's
  comments (and their votes) and the owner's read marker go with it, so nothing is left pointing at a
  gone thread. Other threads on the page are untouched.

  Background:
    Given a thread "Doomed thread" exists
    And a posted reply from "sol" saying "a comment on the doomed thread"

  Scenario: The delete control is offered on a thread row
    When the owner opens the front page
    Then the delete control is present on the "Doomed thread" row

  Scenario: Deleting a thread removes it from the front page
    When the owner deletes the "Doomed thread" thread
    And the owner opens the front page
    Then the front page no longer shows the "Doomed thread" thread

  Scenario: Deleting a thread the owner has already opened clears its read marker too
    # Viewing the thread writes a thread_read row (FK → thread); deletion must clear it before the
    # thread row, or foreign_keys=on rejects the delete.
    When the owner views the thread page
    And the owner deletes the "Doomed thread" thread
    Then the response status is 200
    When the owner opens the front page
    Then the front page no longer shows the "Doomed thread" thread

  Scenario: Deleting a thread leaves other threads intact
    Given a thread "Survivor thread" exists
    When the owner deletes the "Doomed thread" thread
    And the owner opens the front page
    Then the front page no longer shows the "Doomed thread" thread
    And the front page still shows the "Survivor thread" thread
