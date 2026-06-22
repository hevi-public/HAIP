Feature: The site header is a sticky scroll-to-top anchor
  On desktop the header pins to the top of the viewport (CSS) and clicking its bare chrome scrolls the
  page back to the top (header.js, whose decision lives in the unit-tested header-core). Those behaviours
  play out in the browser and aren't observable over HTTP. What the server contract guarantees — and what
  these scenarios pin — is that every page renders the header carrying the data-scroll-top hook the script
  binds to. Lose the hook and the click target silently vanishes, so it earns a structural check like the
  rail boxes do. The header is shared layout, so we check it on both the front page and a thread page.

  Scenario: The front-page header carries the scroll-to-top hook
    When the owner opens the front page
    Then the page header is a scroll-to-top anchor

  Scenario: The thread-page header carries the scroll-to-top hook
    Given a thread "Scaling SQLite" exists
    When the owner views the thread page
    Then the page header is a scroll-to-top anchor
