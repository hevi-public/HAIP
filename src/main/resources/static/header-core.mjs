/*
 * header-core — pure decision for "click the header to scroll to top".
 *
 * NO DOM, NO globals: the one function takes the list of tag names walked from the clicked node up
 * to (and including) the header, and decides whether that click was on bare header chrome (scroll)
 * or on an interactive control (let it act normally). This is the unit-tested heart; the DOM glue
 * (header.js) collects the path and performs the scroll. See src/test/js/header-core.test.mjs.
 */

// Controls that own their own click — clicking these must NOT also scroll the page. The brand link,
// the threads/members nav links (all <a>) and the dark/light/auto theme buttons (<button>) are all
// covered here, so the page only scrolls when the click lands on empty header chrome.
const INTERACTIVE = new Set(["a", "button", "input", "select", "textarea", "label", "summary"]);

/**
 * Decide whether a header click should scroll the page to the top.
 * @param {string[]} pathTags lowercased tag names from the clicked element up to the header.
 * @returns {boolean} true only when no interactive control sits between the target and the header.
 */
export function shouldScrollToTop(pathTags) {
  if (!Array.isArray(pathTags)) return false;
  return !pathTags.some((tag) => INTERACTIVE.has(String(tag).toLowerCase()));
}
