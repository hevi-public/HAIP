package com.aiforum.repo

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

/**
 * Owner `+1` votes are stored with full attribution (§13). The firewall that keeps them out of the
 * model prompt is at the prompt boundary (ContextAssembler), NOT here — storage keeps the truth.
 */
@Repository
class VoteRepository(private val jdbc: JdbcTemplate) {

    fun add(nodeId: String, voterId: String) {
        jdbc.update("INSERT OR IGNORE INTO vote(node_id, voter_id) VALUES (?, ?)", nodeId, voterId)
    }

    fun count(nodeId: String): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM vote WHERE node_id = ?", Int::class.java, nodeId) ?: 0

    fun countAll(): Map<String, Int> =
        jdbc.query("SELECT node_id, COUNT(*) AS cnt FROM vote GROUP BY node_id") { rs, _ ->
            rs.getString("node_id") to rs.getInt("cnt")
        }.toMap()
}
