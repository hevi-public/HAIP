Feature: Honest failure UX for htmx requests
  When an uncaught exception hits an htmx request, the owner must get a small inline error FRAGMENT that
  htmx will actually SWAP into the slot — not Boot's Whitelabel error PAGE (which htmx would swap whole
  into the fragment slot and corrupt the view) and not a silently-discarded body (T1.4). htmx 2.0.6's
  default responseHandling discards a non-2xx body without swapping, so the advice returns the fragment
  at HTTP 200 and signals the real failure out-of-band via an HX-Trigger response header (app:error) the
  client toasts off. The persona prompt-compose preview (POST /personas/compose) is the real surface: its
  synchronous LLM call is unguarded by design, so a model failure there genuinely escapes uncaught to
  the controller advice. The failure is injected at the single LlmClient seam — no second mock.

  Scenario: A failed prompt-compose preview returns a swappable inline error fragment with a failure signal
    Given the LLM will fail with a process error
    When the owner previews a persona prompt over htmx and the model fails
    Then the response is the inline error fragment
    And the response is not a whole error page
    # HTTP 200 so htmx's default responseHandling actually swaps the fragment into the slot.
    And the response status is 200
    # ...with the real failure carried out-of-band for the client toast.
    And the response carries an htmx error trigger with status 502

  # A rate-limit maps to its own status in the trigger payload so the client can word "busy, retry
  # shortly" apart from a hard error — still the swappable inline fragment at 200, never the Whitelabel page.
  Scenario: A rate-limited compose preview still returns the fragment, signalling 503
    Given the LLM will fail with a rate-limit
    When the owner previews a persona prompt over htmx and the model fails
    Then the response is the inline error fragment
    And the response status is 200
    And the response carries an htmx error trigger with status 503

  # The advice is htmx-scoped: a NON-htmx request to the same failing endpoint keeps Boot's default
  # handling (it must NOT receive the inline fragment, and gets no htmx error trigger), so existing
  # API/page behaviour is unchanged.
  Scenario: A non-htmx request to the same failing endpoint does not get the fragment
    Given the LLM will fail with a process error
    When the owner previews a persona prompt without htmx and the model fails
    Then the response is not the inline error fragment
    And the response carries no htmx error trigger
