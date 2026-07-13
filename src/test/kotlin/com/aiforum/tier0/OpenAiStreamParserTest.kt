package com.aiforum.tier0

import com.aiforum.llm.OpenAiStreamParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: the pure per-chunk normalisation of OpenAI Chat Completions streaming `data:` payloads. The
 * client accumulates these and folds them into a synthetic envelope for [com.aiforum.llm.OpenAiResponseParser].
 */
@Tag("tier0")
class OpenAiStreamParserTest {

    @Test
    fun `a content chunk yields its delta content`() {
        val d = OpenAiStreamParser.parseData("""{"choices":[{"delta":{"content":"Hel"},"finish_reason":null}]}""")
        assertEquals("Hel", d?.content)
        assertNull(d?.finishReason)
    }

    @Test
    fun `DONE and blank payloads parse to null`() {
        assertNull(OpenAiStreamParser.parseData("[DONE]"))
        assertNull(OpenAiStreamParser.parseData("   "))
    }

    @Test
    fun `a role-only opening chunk has null content`() {
        val d = OpenAiStreamParser.parseData("""{"choices":[{"delta":{"role":"assistant"}}]}""")
        assertNull(d?.content)
    }

    @Test
    fun `the finish_reason chunk is surfaced`() {
        val d = OpenAiStreamParser.parseData("""{"choices":[{"delta":{},"finish_reason":"length"}]}""")
        assertEquals("length", d?.finishReason)
    }

    @Test
    fun `reasoning_content is surfaced as reasoning`() {
        val d = OpenAiStreamParser.parseData("""{"choices":[{"delta":{"reasoning_content":"thinking"}}]}""")
        assertEquals("thinking", d?.reasoning)
    }

    @Test
    fun `an unparseable payload parses to null`() {
        assertNull(OpenAiStreamParser.parseData("{not json"))
    }
}
