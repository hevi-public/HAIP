@wip
Feature: Personas & admin
  PENDING the implementing team. The owner can view a persona profile and add/edit personas via the
  admin form, which persists the persona card (§6). Drafted as the spec.

  Scenario: View a persona profile
    Given a persona "sol" exists
    When the owner opens the profile for "sol"
    Then the profile shows the persona's name and descriptor

  Scenario: Admin adds a new persona
    When the owner adds a persona "lune" described as "systems poet"
    Then the persona "lune" exists
    And "lune" appears in the members list
