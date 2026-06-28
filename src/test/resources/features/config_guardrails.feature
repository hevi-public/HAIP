Feature: Config guardrails
  Config drifts silently, so the guardrails are themselves tested (§14): under the test profile the app
  must use the test DB and disable backups. These rails assert the wiring from the outside.

  Scenario: The test profile uses the test DB and disables backups
    When the test diagnostics are read
    Then the active datasource points at the test database
    And backups are disabled
    And the active profile is "test"

  Scenario: The test profile never authorises personas to reach the network
    # Headless `claude -p` denies WebFetch / MCP tools unless `--allowedTools` pre-authorises them (see
    # ProcessLlmClient). These rails keep both toggles off under test so CI personas can't hit the network
    # or the gh-readonly GitHub tools (both fetch untrusted content from the host).
    When the test diagnostics are read
    Then persona web fetch is disabled
    And persona GitHub tools are disabled
