# Local-model reasoning leak — investigation & handling

> **Date:** 2026-06-22 · **Status:** resolved · relates to requirements §4 (generation lifecycle) and
> the `openai` provider (LM Studio). Code: `ReplySanitizer`, `OpenAiResponseParser`, `OpenAiLlmClient`,
> `PromptRenderer`, migration V12 (`comment.reasoning_leak`).

## Symptom

Running generation against a **local model via LM Studio** (`provider: openai`), persona replies came
back as the model's **chain-of-thought instead of an answer** — e.g. a reply that opened with
*"Thinking Process: 1. Analyze the Request…"* or *"The user wants me to act as Paul…"*, with the actual
answer (if present at all) buried at the very end. The same personas behave correctly on `claude -p`.

The first model in play was **Gemma 4 (e4b / small variants)**.

## Root cause — it's the model, and the *shape* of how it emits reasoning

There are two structurally different ways a model can surface reasoning over the OpenAI Chat Completions
API, and they need completely different handling:

1. **Inline in `content`, no markers.** The model writes its reasoning as ordinary prose at the start of
   `message.content`, then (sometimes) the answer. There is **no delimiter** — no `<think>` tags, no
   separate field. This is what **Gemma** does: its "thinking" is *prompt-pattern-induced*, not a real
   reasoning channel. You cannot reliably separate thought from answer because there's nothing to split
   on. **This is unfixable by parsing.**

2. **Structurally separable.** Either
   - wrapped in `<think>…</think>` tags inside `content` (Qwen, DeepSeek-distills, …), or
   - split into a dedicated field — `message.reasoning_content` (DeepSeek convention) or
     `message.reasoning` (some servers) — leaving `content` as the clean answer.

   Both are mechanically removable, and the better reasoning models also honour a request-level switch to
   **not reason at all**.

The whole problem reduces to: **Gemma is case 1; the fix is to use a case-2 model** (and/or turn its
thinking off).

### How to tell which case you're in

Run with the **`debug` profile** (see below). `OpenAiLlmClient` then DEBUG-logs the raw HTTP body, so you
can read the exact JSON:

- clean `content`, no `<think>`, no `reasoning_content` → thinking is off (or absent). 🎯
- `<think>…</think>` inside `content` → case 2a.
- a populated `reasoning_content` / `reasoning` field → case 2b.
- prose reasoning in `content` with none of the above → **case 1 (Gemma); switch models.**

## What we built (defence in depth, in pipeline order)

Policy throughout: **strip what we safely can, flag the rest, never discard.** A leaked reply is a
*flagged success* — the body is salvaged and shown with a badge — deliberately **outside** the A–F
failure taxonomy (§4). The flag is persisted (`comment.reasoning_leak`, migration V12) and rendered as a
`data-reasoning-leak` hook + badge.

1. **Source-side — `PromptRenderer`.** Every task prompt now instructs the persona to emit only the final
   message and, if it must reason, wrap it in `<think>…</think>` so it's machine-strippable. Applies to
   all personas immediately (it lives in the task prompt, not the stored per-persona system prompt).
2. **Request-side — `disable-thinking`.** `aiforum.llm.openai.disable-thinking: true` sends
   `chat_template_kwargs: { enable_thinking: false }`, turning reasoning **off at generation time**.
   Honoured only by models with a thinking switch in their chat template (Qwen3, vLLM/SGLang); a **no-op
   for Gemma**.
3. **Response-side — `OpenAiResponseParser`.** Reads `reasoning_content` / `reasoning`: takes `content` as
   the answer and **drops** the reasoning field, flagging it `ACTUAL` (its presence is a definite leak).
4. **Response-side — `ReplySanitizer` (Tier-0, both parsers).** Strips `<think>`/`<thinking>` blocks
   (`ACTUAL`); a conservative, start-anchored heuristic flags untagged "thinking" preamble (`POSSIBLE`).
   Never discards — a heuristic false positive only over-badges a message.
5. **Diagnostics — `debug` profile.** Raw-response logging to see ground truth (above).

### What each lever does and does NOT fix

| Lever | Fixes case 1 (Gemma, inline) | Fixes case 2 (`<think>` / field) |
|---|---|---|
| Prompt hardening | ⚠️ only if the model complies (Gemma didn't) | ✅ encourages the `<think>` form |
| `disable-thinking` flag | ❌ no-op | ✅ when the template honours it |
| `reasoning_content` parsing | ❌ nothing to parse | ✅ clean answer, no regex |
| `<think>` strip + heuristic | ⚠️ heuristic flags it, can't cleanly separate | ✅ strips tagged reasoning |
| **Switch to a case-2 model** | ✅ **the real fix** | — |

## Model recommendation

- **Avoid Gemma for this app.** Its reasoning is inline and unseparable; no amount of parsing recovers a
  clean reply, and prompt hardening alone didn't stop it.
- **Use a Qwen3-arch model** (what we settled on: **Qwen3.5 9B**, dense, ~6 GB at 4-bit MLX, full GPU
  offload on Apple Silicon). Qwen3 separates reasoning via `<think>` tags **and** honours
  `enable_thinking`, so it fits the pipeline either way.
- For a brainstorming/role-play forum the personas write short conversational replies — they don't need
  heavy reasoning — so **turn thinking off** for speed and cleanliness.

### The reliable off-switch is LM Studio's UI

The most dependable way to disable thinking is the **LM Studio preset / "Enable Thinking" toggle**
(server-side) — it doesn't depend on whether the MLX engine forwards our `chat_template_kwargs`. Our
`disable-thinking: true` config sends the request-level flag too, so they reinforce each other, and the
`<think>` strip remains a backstop if anything slips through. With Qwen3.5 + thinking off, replies come
back clean and in-character with **no leak badges**.

## Operational checklist (running against LM Studio)

1. Load a **Qwen3-arch** model; start the LM Studio local server (`http://localhost:1234/v1`).
2. In LM Studio, set the preset to **No Thinking** (or toggle Enable Thinking off).
3. Set `aiforum.llm.default-model` to the **exact id** LM Studio reports (`GET /v1/models`, or the loaded
   model row). LM Studio uses the loaded model regardless, but a correct id keeps logs honest.
4. First run with the **`debug` profile** to confirm the response shape, then drop it:
   ```
   SPRING_PROFILES_ACTIVE=dev,openai,debug ./gradlew bootRun   # inspect raw body
   SPRING_PROFILES_ACTIVE=dev,openai ./gradlew bootRun          # normal
   ```
5. If you still see leaks: you're on a case-1 model — switch models. If you see clean text with an
   `ACTUAL` badge, the model reasoned in a separable form and we cleaned it (fine; flip `disable-thinking`
   on or use the UI preset to drop the reasoning entirely).
