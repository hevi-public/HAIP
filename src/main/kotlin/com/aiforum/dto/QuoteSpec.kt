package com.aiforum.dto

/**
 * A pending quote captured by the composer before the reply is posted: [text] is the verbatim span the
 * owner selected from comment [targetId]. Sent as the `quotesJson` field (a JSON array of these) and
 * turned into a [com.aiforum.repo.QuoteEdge] once the quoting reply has an id. Defaults so Jackson can
 * build it from partial / malformed JSON without throwing (a bad entry is dropped, not a 400).
 */
data class QuoteSpec(val targetId: String = "", val text: String = "")
