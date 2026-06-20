@wip
Feature: New thread creation
  PENDING the implementing team. The owner starts a thread with a title, a question, and a roomful of
  personas; the fresh thread shows the "waiting on the room" empty state (§2). Drafted as the spec.

  Background:
    Given a persona "sol" exists
    And a persona "vex" exists

  Scenario: Owner starts a thread and asks the room
    When the owner creates a thread "Why is SQLite fast?" asking "explain the design" of "sol, vex"
    Then the thread exists with title "Why is SQLite fast?"
    And the thread shows the waiting-on-the-room empty state
