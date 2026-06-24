Feature: Site navigation — the header section links
  The shared header exposes the primary section navigation (threads, members, github). These links are
  the only way to reach each section's page, so losing or mis-pointing one silently strands a section —
  they earn a structural check like the header's scroll-to-top hook does. Nav is shared layout, so it is
  checked on the front page and a thread page.

  Scenario: The front-page header links to every primary section
    When the owner opens the front page
    Then the header nav links "threads" to "/"
    And the header nav links "members" to "/personas"
    And the header nav links "github" to "/github"

  Scenario: The section nav is present on a thread page too
    Given a thread "Scaling SQLite" exists
    When the owner views the thread page
    Then the header nav links "github" to "/github"
