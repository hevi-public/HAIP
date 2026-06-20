package com.aiforum.tier0

import com.aiforum.domain.budget.DepthBudget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: the pure depth-budget rule that bounds autonomous growth (§4). No IO — just the arithmetic
 * that makes a branch grow ~3–4 levels past its last grant and then stall.
 */
@Tag("tier0")
class DepthBudgetTest {

    @Test
    fun `a child continues its parent budget, decremented`() {
        assertEquals(3, DepthBudget.childBudget(4))
        assertEquals(1, DepthBudget.childBudget(2))
    }

    @Test
    fun `child budget floors at zero and never goes negative`() {
        assertEquals(0, DepthBudget.childBudget(1))
        assertEquals(0, DepthBudget.childBudget(0))
        assertEquals(0, DepthBudget.childBudget(-5))
    }

    @Test
    fun `a node can grow while it has budget and is exhausted at zero`() {
        assertTrue(DepthBudget.canGrow(1))
        assertFalse(DepthBudget.canGrow(0))
        assertTrue(DepthBudget.isExhausted(0))
        assertFalse(DepthBudget.isExhausted(DepthBudget.granted()))
    }

    @Test
    fun `a fresh grant fuels exactly DEFAULT_GRANT descending levels then stalls`() {
        // Walk the chain the way autoGrow does: each level consumes one unit, starting from the grant.
        var budget = DepthBudget.granted()
        var levels = 0
        while (DepthBudget.canGrow(budget)) {
            levels++
            budget = DepthBudget.childBudget(budget)
        }
        assertEquals(DepthBudget.DEFAULT_GRANT, levels, "growth must stall after the granted depth")
    }
}
