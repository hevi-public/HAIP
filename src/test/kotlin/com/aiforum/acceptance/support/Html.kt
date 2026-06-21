package com.aiforum.acceptance.support

/**
 * Tiny HTML probe for the stable data-* semantic hooks the JTE templates emit (see the
 * jte-spring-kotlin skill). We deliberately assert on data-* attributes, never CSS classes, so the
 * scenarios survive a visual redesign and can later be re-pointed at a real DOM driver.
 */
object Html {

    /** The value of [attr] on the element whose data-reply-id == [replyId], or null. */
    fun replyAttr(html: String, replyId: String, attr: String): String? {
        // find the <article ... data-reply-id="ID" ...> tag and read [attr] within it
        val tag = Regex("<[^>]*data-reply-id=\"${Regex.escape(replyId)}\"[^>]*>")
            .find(html)?.value ?: return null
        return Regex("$attr=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)
    }

    /** True if any element carries data-[name]="[value]". */
    fun hasAttr(html: String, name: String, value: String): Boolean =
        Regex("$name=\"${Regex.escape(value)}\"").containsMatchIn(html)

    /** Every distinct data-reply-id in document order — one for a summon, several for a fan-out. */
    fun allReplyIds(html: String): List<String> =
        Regex("data-reply-id=\"([^\"]+)\"").findAll(html).map { it.groupValues[1] }.distinct().toList()

    fun contains(html: String, needle: String): Boolean = html.contains(needle, ignoreCase = true)

    /** Count of elements carrying data-[name]="[value]". */
    fun countAttr(html: String, name: String, value: String): Int =
        Regex("$name=\"${Regex.escape(value)}\"").findAll(html).count()

    /** Every value carried by [name]="…" in document order — e.g. the reply id on each rail entry. */
    fun attrValues(html: String, name: String): List<String> =
        Regex("${Regex.escape(name)}=\"([^\"]*)\"").findAll(html).map { it.groupValues[1] }.toList()

    /** The data-reply-id of the first <article> whose data-author == [author], or null. */
    fun replyIdWithAuthor(html: String, author: String): String? {
        val tag = Regex("<article\\b[^>]*data-author=\"${Regex.escape(author)}\"[^>]*>").find(html)?.value ?: return null
        return Regex("data-reply-id=\"([^\"]+)\"").find(tag)?.groupValues?.get(1)
    }

    /**
     * True if the <article> with data-reply-id=[childId] is nested INSIDE the one with
     * data-reply-id=[parentId] — genuine DOM containment, not merely both present on the page (which is
     * what the flat-rendering bug produced). Articles nest, so we balance <article>/</article> from the
     * parent's opening tag to find its matching close and look for the child only within that span.
     */
    fun isNestedUnder(html: String, childId: String, parentId: String): Boolean {
        val open = Regex("<article\\b[^>]*data-reply-id=\"${Regex.escape(parentId)}\"[^>]*>").find(html) ?: return false
        val token = Regex("<article\\b|</article>")
        var i = open.range.last + 1   // start scanning after the parent's opening tag (parent = depth 1)
        var depth = 1
        while (true) {
            val m = token.find(html, i) ?: return false
            if (m.value == "</article>") {
                depth--
                if (depth == 0) {     // parent's matching close — child must lie in the span before it
                    return html.substring(open.range.last + 1, m.range.first).contains("data-reply-id=\"$childId\"")
                }
            } else {
                depth++
            }
            i = m.range.last + 1
        }
    }

    /** The data-scope value on the composer element whose data-target-id == [targetId], or null. */
    fun composerScope(html: String, targetId: String): String? = composerAttr(html, targetId, "data-scope")

    /** The value of an arbitrary [attr] on the composer element whose data-target-id == [targetId], or
     *  null. Reads from the single opening tag carrying data-target-id, so it sees the hx-* wiring and
     *  data-* hooks that live together on the composer <form>. */
    fun composerAttr(html: String, targetId: String, attr: String): String? {
        val tag = Regex("<[^>]*data-target-id=\"${Regex.escape(targetId)}\"[^>]*>")
            .find(html)?.value ?: return null
        return Regex("${Regex.escape(attr)}=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)
    }
}
