Feature: Reply and post bodies render markdown
  Bodies are GitHub-flavoured markdown rendered server-side (commonmark + GraalJS highlight.js). Fenced
  code blocks come back syntax-highlighted; raw HTML in a body is escaped, not executed — the firewall
  behind the $unsafe{} body output, since bodies are LLM-generated and untrusted.

  Background:
    Given a thread "Markdown rendering" exists
    And a persona "saul" exists

  Scenario: A fenced code block in a reply renders syntax-highlighted
    Given the LLM will respond with the markdown:
      """
      Sure — here's a snippet:

      ```yaml
      name: saul
      role: frontend
      ```
      """
    When the owner summons "saul"
    Then the reply is "posted"
    And the reply body contains "hljs-"
    And the reply body contains "language-yaml"

  Scenario: Raw HTML in a reply is escaped, not executed
    Given the LLM will respond with "Try <script>alert('x')</script> please"
    When the owner summons "saul"
    Then the reply is "posted"
    And the reply body contains "&lt;script&gt;"
    And the reply body does not contain "<script>"

  Scenario: A link with a hostile destination is neutralized
    Given the LLM will respond with "See [click me](javascript:alert('x')) for details"
    When the owner summons "saul"
    Then the reply is "posted"
    And the reply body contains "click me"
    # The raw markdown source is allowed to appear escaped (edit textarea, snippets) — the threat is
    # only a live href, so that exact form is what must be absent.
    And the reply body does not contain 'href="javascript:'
