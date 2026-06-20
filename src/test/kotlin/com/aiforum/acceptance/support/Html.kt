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

    fun contains(html: String, needle: String): Boolean = html.contains(needle, ignoreCase = true)

    /** Count of elements carrying data-[name]="[value]". */
    fun countAttr(html: String, name: String, value: String): Int =
        Regex("$name=\"${Regex.escape(value)}\"").findAll(html).count()

    /** The data-scope value on the composer element whose data-target-id == [targetId], or null. */
    fun composerScope(html: String, targetId: String): String? {
        val tag = Regex("<[^>]*data-target-id=\"${Regex.escape(targetId)}\"[^>]*>")
            .find(html)?.value ?: return null
        return Regex("""data-scope="([^"]*)"""").find(tag)?.groupValues?.get(1)
    }
}
