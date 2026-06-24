package com.aiforum.web

import com.aiforum.shortcut.ShortcutService
import com.aiforum.shortcut.StorySource
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * The dedicated Shortcut page (GET /shortcut): a browsable, read-only list of stories with a source
 * switcher — `Search` (the configured/free-text query), `Recently updated`, and `Owner's stories`. The
 * page degrades the same way the rail box does: a clear "integration off" state when disabled, a quiet
 * note on error, never a 500.
 */
@Controller
class ShortcutController(private val shortcut: ShortcutService) {

    @GetMapping("/shortcut")
    fun page(
        @RequestParam(required = false) source: String?,
        @RequestParam(required = false) q: String?,
        model: Model,
    ): String {
        val selected = StorySource.from(source)
        model.addAttribute("enabled", shortcut.enabled)
        model.addAttribute("selected", selected)
        model.addAttribute("sources", StorySource.entries.toList())
        model.addAttribute("query", q.orEmpty())
        model.addAttribute("result", shortcut.pageStories(selected, q))
        return "shortcut"
    }
}
