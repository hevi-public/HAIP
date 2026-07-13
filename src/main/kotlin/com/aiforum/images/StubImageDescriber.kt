package com.aiforum.images

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * The `stub` provider's vision sibling (see StubLlmClient): a canned caption with no model behind it, so
 * the describe → caption → prompt-injection path is walkable in demos. @Primary because the real
 * [OpenAiImageDescriber] is always loaded under `!test` — same override shape as the test profile's fake.
 */
@Component
@Primary
@Profile("!test")
@ConditionalOnProperty(prefix = "aiforum.llm", name = ["provider"], havingValue = "stub")
class StubImageDescriber : ImageDescriber {
    override fun describe(request: DescribeRequest): String =
        "Stubbed caption (provider=stub, no vision model): ${request.mimeType}, " +
            "${request.imageBytes.size} bytes — canned text standing in for a real description."
}
