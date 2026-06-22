package com.aiforum.images

/**
 * The single Tier-1 IO seam for vision (the sibling of LlmClient). It is the ONLY place a multimodal
 * payload is ever built — the generation clients stay strictly text-only, so any model can run the forum
 * (the caption-only design: a vision model turns an image into text here, and that text is what reaches
 * generation). Under the `test` profile a scriptable fake stands in, so the acceptance suite never does
 * real vision IO.
 */
interface ImageDescriber {
    fun describe(request: DescribeRequest): String
}

/** Raw image bytes + their sniffed mime, to be sent to the vision model as a base64 image content block. */
data class DescribeRequest(val imageBytes: ByteArray, val mimeType: String) {
    // data class with a ByteArray: override equals/hashCode so two requests over the same bytes compare
    // equal (the generated identity-based ones would not), matching how the rest of the domain behaves.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DescribeRequest) return false
        return mimeType == other.mimeType && imageBytes.contentEquals(other.imageBytes)
    }

    override fun hashCode(): Int = 31 * imageBytes.contentHashCode() + mimeType.hashCode()
}

/**
 * Thrown by the real describer when vision is not enabled (aiforum.images.describe.enabled=false, the
 * default — a vision model is brought up manually). The service maps it to a FAILED caption with a clear
 * reason rather than a 500, so "describe" on a forum without a vision model is a graceful no-op.
 */
class VisionUnavailableException(message: String) : RuntimeException(message)
