Feature: Honest failure UX for htmx requests
  When an uncaught exception hits an htmx request, the owner must get a non-blocking TOAST — and crucially
  NOTHING swapped into the request's target (e.g. the compose textarea) and not Boot's Whitelabel error
  PAGE (T1.4). htmx 2.0.6's default responseHandling discards a non-2xx body without swapping, so the
  advice returns the mapped NON-2xx status with an empty body (nothing lands in the field) and signals the
  failure out-of-band via an HX-Trigger response header (app:error) the client toasts off. The persona
  prompt-compose preview (POST /personas/compose) is the real surface: its synchronous LLM call is
  unguarded by design, so a model failure there genuinely escapes uncaught to the controller advice. The
  failure is injected at the single LlmClient seam — no second mock.

  Scenario: A failed prompt-compose preview returns a non-2xx with a toast signal and no swapped body
    Given the LLM will fail with a process error
    When the owner previews a persona prompt over htmx and the model fails
    # The mapped non-2xx status: htmx swaps nothing on a non-2xx, so nothing lands in the compose field.
    Then the response status is 502
    # No fragment body — the old swap design is gone; the toast is the sole feedback.
    And the response has no error fragment body
    # ...the failure rides the HX-Trigger header instead, which htmx fires regardless of status.
    And the response carries an htmx error trigger with status 502

  # A rate-limit maps to its own status so the client can word "busy, retry shortly" apart from a hard
  # error — still a non-2xx + HX-Trigger toast signal, never the Whitelabel page, never a swapped body.
  Scenario: A rate-limited compose preview signals 503 with a toast
    Given the LLM will fail with a rate-limit
    When the owner previews a persona prompt over htmx and the model fails
    Then the response status is 503
    And the response has no error fragment body
    And the response carries an htmx error trigger with status 503

  # A timeout maps to 504 — a second distinct status, so the mapping (not just one code) is exercised.
  Scenario: A timed-out compose preview signals 504 with a toast
    Given the LLM will fail with a timeout
    When the owner previews a persona prompt over htmx and the model fails
    Then the response status is 504
    And the response has no error fragment body
    And the response carries an htmx error trigger with status 504

  # The advice is htmx-scoped: a NON-htmx request to the same failing endpoint keeps Boot's default
  # handling — the rethrow surfaces as Boot's error response with a NON-empty body (the Whitelabel error)
  # and NO htmx error trigger. The status is Boot's default 500 (the 502/503/504 mapping lives only in the
  # advice's htmx branch; a bare rethrown exception carries no @ResponseStatus, so Boot uses 500). This
  # guards against a regression to swallowing the exception into a 200 — it must stay an honest error.
  Scenario: A non-htmx request to the same failing endpoint keeps Boot's default handling
    Given the LLM will fail with a process error
    When the owner previews a persona prompt without htmx and the model fails
    Then the response status is 500
    And the response has a non-empty error body
    And the response carries no htmx error trigger
