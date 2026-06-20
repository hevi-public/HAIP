Feature: Empty states & unread markers
  PENDING the implementing team. The front page shows a fresh-forum empty state when there are no
  threads, and a thread-level "N new" badge for unread replies (M1 ships thread-level only) (§2).
  Drafted as the spec.

  Scenario: Fresh forum shows the empty state
    Given there are no threads
    When the owner opens the front page
    Then the fresh-forum empty state is shown

  Scenario: Thread shows an unread count badge
    Given a thread "Scaling SQLite" exists
    And the thread has 3 replies unread by the owner
    When the owner opens the front page
    Then the thread row shows a "3 new" badge
