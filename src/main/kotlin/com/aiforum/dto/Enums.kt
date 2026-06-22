package com.aiforum.dto

/** Generation lifecycle states (§4): drafting → posted | failed | cancelled. */
enum class GenerationState { DRAFTING, POSTED, FAILED, CANCELLED }

/** How a non-posted reply failed, mapped onto the six UX error states (A–F). */
enum class FailureCategory { FAILED_RETRY, RATE_LIMITED, COULDNT_SAVE, VALIDATION, PARTIAL_ROOMFUL, CANCELLED }

enum class TriggerMode { SUMMON, FANOUT, MENTION }

enum class ScopeMode { BRANCH_ONLY, WHOLE_THREAD }

/**
 * A reply where the model leaked its chain-of-thought. The reply is still persisted and shown — this
 * only tags it so the UI can badge it and we can log it (see ReplySanitizer). ACTUAL = we found and
 * stripped `<think>` reasoning tags (certain); POSSIBLE = a conservative heuristic flagged untagged
 * "thinking" preamble the model emitted in the open (uncertain, so we never discard on it). NULL = clean.
 */
enum class ReasoningLeak { ACTUAL, POSSIBLE }
