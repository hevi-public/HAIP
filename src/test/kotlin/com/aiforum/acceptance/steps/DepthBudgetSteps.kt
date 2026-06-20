package com.aiforum.acceptance.steps

import com.aiforum.acceptance.config.ScriptableLlmClient
import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import com.aiforum.acceptance.support.TestData
import com.aiforum.domain.budget.DepthBudget
import com.aiforum.repo.CommentRepository
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.http.ResponseEntity

/**
 * Depth-budget autonomy (§4/§7). Growth is driven over HTTP (`/auto-grow`); per-branch claims are
 * asserted against the real persisted tree (descendant counts) and the directive-visibility claim
 * against the LlmClient spy — each assertion reads the layer the behaviour actually lives in.
 *
 * The exhausted-branch fixture seeds two sibling branches under the thread so "other branches stay
 * quiet" has something to be quiet about; "that branch" is always branch A.
 */
class DepthBudgetSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
    private val data: TestData,
    private val comments: CommentRepository,
    private val llm: ScriptableLlmClient,
) {
    // --- Scenario 1: growth stalls when the budget is exhausted ---

    @Given("the owner has commented at level 0")
    fun ownerCommentedAtLevelZero() {
        world.replyIds["ownerLevel0"] = data.insertComment(
            world.threadId!!, authorId = "owner", body = "owner OP", parentId = null,
            depth = 0, depthBudget = DepthBudget.granted(),
        )
    }

    @When("the room auto-replies")
    fun theRoomAutoReplies() {
        capture(http.post("/threads/${world.threadId}/auto-grow"))
    }

    @Then("auto-replies stop after about {int} levels")
    fun autoRepliesStopAfter(levels: Int) {
        val grown = postedCount()
        assertTrue(
            grown in (levels - 1)..levels,
            "expected growth to stall at about $levels levels, got $grown — the budget must bound it",
        )
    }

    // --- Scenarios 2 & 3: re-granting an exhausted branch ---

    @Given("a branch whose depth budget is exhausted")
    fun anExhaustedBranch() {
        val (aRoot, aTip) = seedExhaustedBranch("A")
        val (bRoot, _) = seedExhaustedBranch("B")
        world.replyIds["branchA"] = aRoot
        world.replyIds["branchA.tip"] = aTip
        world.replyIds["branchB"] = bRoot
    }

    @When("the owner replies on that branch")
    fun theOwnerRepliesOnThatBranch() {
        http.post("/replies/${world.replyIds["branchA.tip"]}/owner-reply")
        snapshotBranchSizes()
        capture(http.post("/threads/${world.threadId}/auto-grow"))
    }

    // `\/` escapes the literal slash: in a Cucumber Expression a bare `/` is the alternation operator.
    @When("the owner invokes \\/more on that branch")
    fun theOwnerInvokesMore() {
        http.post("/replies/${world.replyIds["branchA.tip"]}/more")
        snapshotBranchSizes()
        capture(http.post("/threads/${world.threadId}/auto-grow"))
    }

    @Then("auto-replies resume on that branch")
    fun autoRepliesResumeOnThatBranch() {
        val after = comments.descendantCount(world.replyIds["branchA"]!!)
        assertTrue(after > world.counts["branchA"]!!, "expected new auto-replies under the refuelled branch")
    }

    @Then("other branches stay quiet")
    fun otherBranchesStayQuiet() {
        val after = comments.descendantCount(world.replyIds["branchB"]!!)
        assertEquals(world.counts["branchB"], after, "a re-grant on branch A must not wake the quiet sibling")
    }

    @Then("the branch is granted about {int} to {int} more levels")
    fun theBranchIsGrantedMoreLevels(lo: Int, hi: Int) {
        val grown = postedCount()
        assertTrue(grown in lo..hi, "expected $lo to $hi new auto-reply levels, got $grown")
    }

    @Then("the \\/more directive appears in the context handed to the model")
    fun moreDirectiveAppearsInContext() {
        assertTrue(
            llm.received.any { req -> req.context.comments.any { it.body.contains("/more") } },
            "the /more directive must be visible in the context handed to the model (§7)",
        )
    }

    /** Owner comment (budget GRANT) + a chain of auto-replies that drains it to zero. Returns root→tip. */
    private fun seedExhaustedBranch(label: String): Pair<String, String> {
        val grant = DepthBudget.granted()
        val root = data.insertComment(
            world.threadId!!, authorId = "owner", body = "$label owner", parentId = null,
            depth = 0, depthBudget = grant,
        )
        var parent = root
        var budget = DepthBudget.childBudget(grant)
        for (depth in 1..grant) {
            parent = data.insertComment(
                world.threadId!!, authorId = "sol", body = "$label L$depth", parentId = parent,
                depth = depth, depthBudget = budget,
            )
            budget = DepthBudget.childBudget(budget)
        }
        return root to parent
    }

    /** Capture each branch's descendant count after the re-grant but before growth, for delta asserts. */
    private fun snapshotBranchSizes() {
        world.counts["branchA"] = comments.descendantCount(world.replyIds["branchA"]!!)
        world.counts["branchB"] = comments.descendantCount(world.replyIds["branchB"]!!)
    }

    private fun postedCount(): Int = Html.countAttr(world.lastBody ?: "", "data-state", "posted")

    private fun capture(resp: ResponseEntity<String>) {
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }
}
