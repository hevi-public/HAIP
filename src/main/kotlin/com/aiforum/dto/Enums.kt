package com.aiforum.dto

/** Generation lifecycle states (§4): drafting → posted | failed | cancelled. */
enum class GenerationState { DRAFTING, POSTED, FAILED, CANCELLED }

/** How a non-posted reply failed, mapped onto the six UX error states (A–F). */
enum class FailureCategory { FAILED_RETRY, RATE_LIMITED, COULDNT_SAVE, VALIDATION, PARTIAL_ROOMFUL, CANCELLED }

enum class TriggerMode { SUMMON, FANOUT, MENTION }

enum class ScopeMode { BRANCH_ONLY, WHOLE_THREAD }
