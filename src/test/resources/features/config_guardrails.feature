Feature: Config guardrails
  Config drifts silently, so the guardrails are themselves tested (§14): under the test profile the app
  must use the test DB and disable backups. These rails assert the wiring from the outside.

  Scenario: The test profile uses the test DB and disables backups
    When the test diagnostics are read
    Then the active datasource points at the test database
    And backups are disabled
    And the active profile is "test"
