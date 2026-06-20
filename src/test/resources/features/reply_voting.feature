Feature: Reply voting — owner +1 from the browser

  The +1 is the anti-sycophancy firewall (§7): recorded and shown to the owner, never fed to
  the model. These scenarios cover the browser-visible gap that was not yet closed: the +1
  button must appear in the rendered thread page, and vote counts must survive a reload
  (ThreadController now loads real counts from VoteRepository, not the default zero).

  Background:
    Given a thread "Ideas" exists
    And a persona "sol" exists
    And a posted reply from "sol" saying "This is worth exploring"

  Scenario: +1 button appears on every posted reply
    When the owner views the thread page
    Then the +1 button is present on "sol"'s reply

  Scenario: Vote count persists after page reload
    When the owner gives a +1 to "sol"'s reply
    And the owner views the thread page
    Then the owner sees a vote count of 1 on "sol"'s reply
