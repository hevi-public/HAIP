package com.aiforum.domain.budget

/**
 * The engagement-fuelled depth budget that bounds autonomous growth (§4). Pure Tier-0 logic: an owner
 * comment or a /more directive GRANTS [DEFAULT_GRANT] levels to its branch; every descending reply gets
 * [childBudget] of its parent (one less, floored at zero), so a branch auto-grows ~3–4 levels past the
 * owner's last comment and then stalls. The budget is per-branch because it is carried on each node, so
 * a re-grant on one branch never leaks to a quiet sibling.
 */
object DepthBudget {
    /** Levels of autonomous growth an owner comment / `/more` fuels on its branch ("the K in run-K"). */
    const val DEFAULT_GRANT = 4

    /** Budget handed to a fresh owner comment or `/more` directive. */
    fun granted(): Int = DEFAULT_GRANT

    /** A child continues its parent's branch budget, decremented and floored at zero. */
    fun childBudget(parentBudget: Int): Int = maxOf(0, parentBudget - 1)

    /** A node may still sprout an autonomous reply while it has budget left. */
    fun canGrow(budget: Int): Boolean = budget > 0

    fun isExhausted(budget: Int): Boolean = !canGrow(budget)
}
