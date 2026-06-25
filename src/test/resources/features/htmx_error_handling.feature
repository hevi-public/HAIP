Feature: Honest failure UX for htmx requests
  When an uncaught exception hits an htmx request, the owner must get a small inline error FRAGMENT —
  not Boot's Whitelabel error PAGE, which htmx would swap whole into the fragment slot and corrupt the
  view, leaving the triggering control disabled and the spinner spinning (T1.4). The persona
  prompt-compose preview (POST /personas/compose) is the real surface: its synchronous LLM call is
  unguarded by design, so a model failure there genuinely escapes to the @ControllerAdvice. The failure
  is injected at the single LlmClient seam — no second mock.

  Scenario: A failed prompt-compose preview returns an inline error fragment, not a whole error page
    Given the LLM will fail with a process error
    When the owner previews a persona prompt over htmx and the model fails
    Then the response is the inline error fragment
    And the response is not a whole error page
    And the response status is 502

  # A rate-limit maps to its own status so the client can tell "busy, retry shortly" from a hard error,
  # but it is still the inline fragment, never the Whitelabel page.
  Scenario: A rate-limited compose preview still returns the fragment, with a 503
    Given the LLM will fail with a rate-limit
    When the owner previews a persona prompt over htmx and the model fails
    Then the response is the inline error fragment
    And the response status is 503

  # The advice is htmx-scoped: a NON-htmx request to the same failing endpoint keeps Boot's default
  # handling (it must NOT receive the inline fragment), so existing API/page behaviour is unchanged.
  Scenario: A non-htmx request to the same failing endpoint does not get the fragment
    Given the LLM will fail with a process error
    When the owner previews a persona prompt without htmx and the model fails
    Then the response is not the inline error fragment
