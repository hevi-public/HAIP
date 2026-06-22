Feature: Image attachments reach the model as a caption, not raw bytes
  The owner can attach an image to a post/comment. Images are caption-only to the model: a vision model
  (a separate IO seam, invoked MANUALLY) turns the image into text, and that caption is injected into the
  discussion as part of the owner's message — so any generation model can use it, and raw image bytes
  never reach the room (the caption-only firewall). An undescribed image still tells the model an image
  is present so it doesn't talk past it.

  Background:
    Given a thread "Design review" exists
    And a persona "sol" exists

  Scenario: A described image's caption reaches the model; raw bytes never do
    When the owner attaches an image with the note "here's the mockup"
    Then the posted note shows an attachment
    Given the vision model will caption the image "a login screen with two text fields"
    When the owner describes the attachment
    Then the attachment caption is "a login screen with two text fields"
    Given the LLM will respond with "the spacing looks tight"
    When the owner summons "sol"
    Then the model context mentions "a login screen with two text fields"
    And the model context carries no raw image bytes

  Scenario: An undescribed image still tells the model an image is present
    When the owner attaches an image with the note "screenshot below"
    Given the LLM will respond with "what does it show?"
    When the owner summons "sol"
    Then the model context mentions "[Image attached (no description)]"

  Scenario: Deleting a note with an image removes the attachment cleanly
    When the owner attaches an image with the note "throwaway"
    And the owner deletes the image note
    Then the delete succeeds
    And the attachment is gone
