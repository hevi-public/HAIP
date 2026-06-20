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

  # The browser path: the home page's new-thread form posts form-urlencoded (not the JSON the API uses)
  # and the owner is redirected onto the fresh thread page. Pins the form binding the JSON scenario
  # above doesn't exercise (mirrors composer_submit for the generate endpoint).
  Scenario: Owner starts a thread from the browser form
    When the owner starts a thread titled "Scaling SQLite" from the browser
    Then the thread exists with title "Scaling SQLite"
    And the thread shows the waiting-on-the-room empty state
