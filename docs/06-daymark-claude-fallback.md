# Daymark — Claude API Key Fallback

Status: **implemented**. Companion to `02-daymark-ai-system-design.md`, which describes the
Gemini-first pipeline this change extends without altering.

## 1. Why

Every Smart AI capability (Prioritize, Quick Add, Sub-tasks, Suggest Next) needs one thing: a
JSON object back from a model. Until now the only way to get it was Gemini — via Firebase AI
Logic, or a free AI Studio key pasted in Admin. Two failure modes followed from that:

- **No Gemini key and no Blaze plan.** A user could not use cloud AI at all, even with an
  Anthropic key in hand.
- **Gemini says no.** A `429` during a morning burst, a retired model id, an expired key, or a
  regional block dropped the *entire* request to on-device heuristics with no retry.

The fallback closes both gaps. It does not weaken the guardrails: quotas, PII redaction, the
circuit breaker, the admin kill switch and the human-in-the-loop review all sit *above* the
provider choice and are unchanged.

## 2. The chain

`AiProviderPolicy.chain(preference, geminiUsable, hasClaudeKey)` returns the list of providers
to try, in order. The user controls only the preference; the policy decides what is possible.

| `AiProviderPreference` | Claude key present | Chain |
|---|---|---|
| `AUTO` *(default)* | yes | Gemini → Claude |
| `AUTO` | no | Gemini |
| `GEMINI` | either | Gemini |
| `CLAUDE` | yes | Claude → Gemini |
| `CLAUDE` | no | Gemini *(degraded, see below)* |

Two rules are worth stating because they are decisions, not accidents:

1. **Claude is never attempted without a key.** No key means no attempt — not an empty request
   that spends a failed round trip.
2. **Choosing "Claude first" without a key does not disable cloud AI.** It falls back to Gemini
   alone and the Settings/Admin copy says so ("Claude first, but no Claude key is set — using
   Gemini"). A preference must never be able to leave the app with no model at all.

An empty chain means no cloud provider is usable, and the caller goes straight to the
on-device heuristics — the same path a total Gemini outage already took.

The admin **Cloud AI (Master Switch)** sits above all of this. It is checked before the chain is
built, so turning it off stops Gemini *and* Claude for every user; it is named "Cloud AI" rather
than after a provider precisely because it is not a per-provider control. Choosing between
providers is what **Admin → AI PROVIDER ORDER** is for.

## 3. Budgets are per provider

This is the part that turned "fallback implemented" into "fallback actually used".

`AiQuotaGuard` used to hold one 25-call, 40k-token bucket per user and to gate the request
*before* the provider chain ran. So a spent free Gemini day blocked everything, and the user
saw:

> Daily Gemini limit reached (25/day) — using offline smart rules until tomorrow

…with a working Claude key sitting in Admin, unused. The bucket was the bug: two providers were
sharing one wallet, and the free one emptied it.

Now:

- each provider has its own daily call and token budget (`AiProviderPolicy.providersWithRoom`);
- the gate blocks only when **no** provider has room;
- the chain attempts only the providers that do, so a spent Gemini day is skipped, not tried and
  failed over;
- usage is recorded against the provider that answered, by `FirebaseAiTaskService` — the only
  layer that knows.

The consequence worth stating plainly: the admin "daily quota per user" now means *per provider
per user*, so a user with both providers configured can make up to 2x that number of calls a day,
half of them on a key they supplied and pay for themselves.

The counters are keyed by provider, so the day this ships starts everyone from zero for both -
including a Gemini bucket that had already been emptied under the old shared key. Nothing is lost
that mattered: the old numbers were a single day's total and are meaningless the next morning.

## 4. Where the key lives

| Source | Precedence | Notes |
|---|---|---|
| Admin → `AI & Config` → **Claude API key** | 1 | `DataStore`, per device, cleared by "Clear Claude key" |
| `local.properties` → `CLAUDE_API_KEY=` | 2 | Gitignored, build-time, for developers |

`local.properties` is already in `.gitignore`; no key value is ever committed, logged, or sent
anywhere except `api.anthropic.com`. Saving a key in Admin also turns cloud AI on and marks the
consent prompt answered, exactly like the Gemini key does.

The Admin sheet also accepts an optional **model id** override. Blank means "use the built-in
list".

## 5. Naming in the product

User-facing copy says **Smart AI** and never names a provider. Settings, the consent sheet, the
focus-plan sheet and the step suggestions all read "Smart AI" / "cloud AI service", because a
person ranking their evening tasks does not care which company's GPU answered, and a name in the
copy becomes a promise the app cannot keep when the order changes.

Vendor names survive in exactly two places:

1. **Admin → AI & Config**, where labels like "Claude API key" and the provider-order radios are
   meaningless without them, and where an admin is pasting a key that the vendor issued.
2. **Troubleshooting notes** that would be unfixable otherwise — `Google Cloud Console >
   Credentials`, `Firebase Console > Build > AI Logic`, `aistudio.google.com/apikey`.

## 6. Models

`ClaudeMessages.modelCandidates(preferred)` yields, in order:

1. the Admin override, if any;
2. `claude-haiku-5-5` — the default, and the token-budget-correct choice for extraction and
   classification at $0.10 in / $0.50 out per million tokens;
3. `claude-sonnet-5-5` — $2 / $10 per million, if Haiku is unavailable;
4. `claude-haiku-4-5` — the previous generation, for accounts that still have it.

A `404` (retired id) advances to the next candidate and the winner is cached for the process
lifetime. A `401`/`403` does **not** advance: a rejected key fails identically on every id, so
retrying spends four failed requests to learn one fact. `429` and `5xx` fail the provider, which
hands the request to Gemini instead.

The Gemini `AiModelTier` is deliberately ignored on this path. Both tiers ask for a ranked
list of short task ids, which Haiku classifies as well as Sonnet, so paying 20x for the "Flash"
tier would buy nothing a user could see.

No `thinking`, `effort` or sampling parameters are sent. Current models use adaptive thinking
and some reject an explicit `{"type":"disabled"}`, so disabling it is not portable; the reply is
filtered instead.

## 7. Wire format and parsing

`POST https://api.anthropic.com/v1/messages` with `x-api-key` and
`anthropic-version: 2023-06-01`. `max_tokens` is required; Daymark sends 1024, which is ample
for a ranked list of task ids.

The Messages API has no JSON response mode, unlike Gemini. Three consequences, all handled in
`ClaudeMessages`:

- **Thinking blocks are filtered, not concatenated.** `content[]` interleaves
  `{"type":"thinking"}` and `{"type":"redacted_thinking"}` with the answer; only `"text"` blocks
  count, and they are joined in order.
- **The object is brace-sliced, not first-to-last-brace.** `extractJsonObject` counts depth and
  handles strings and escapes, so a chatty or fenced reply yields a clean object. This also
  replaced the greedy slice previously used on the Gemini path, which returned a syntactically
  broken string when a reply was cut short.
- **`stop_reason == "max_tokens"` is a failure, not a short answer.** Truncated JSON parses to
  nothing and would have looked like "the model returned garbage" rather than "we did not give
  it room". It fails over instead.

## 8. Files

| File | Role |
|---|---|
| `domain/ai/AiProvider.kt` | `AiProvider`, `AiProviderPreference` (persisted, so it lives in `domain`) |
| `data/ai/AiProviderPolicy.kt` | Chain ordering, per-provider budget check, key normalization, user-facing summary |
| `data/ai/ClaudeMessages.kt` | Request body, escaping, parsing, model candidates, error text — pure Kotlin, no Android imports |
| `data/ai/ClaudeAiTransport.kt` | The one HTTPS call; mirrors `callGoogleAiStudioRest` |
| `core/datastore/AiPreferences.kt` | Key, model override and preference stored in `DataStore` |
| `data/ai/FirebaseAiTaskService.kt` | `generateJsonText` walks the chain and records usage per provider |
| `ui/admin/AdminDashboardScreen.kt` | `AI PROVIDER ORDER` card + Claude key sheet |

## 9. Tests

`ClaudeMessagesTest` (17) and `AiProviderPolicyTest` (19) run on the JVM with no Android
emulator. The policy tests include the exact regression above: a spent Gemini day with a Claude
key must leave `[CLAUDE]` available, and must leave nothing available without one.
`AiGuardrailsAndCacheTest` repeats it at the guard level - spent Gemini plus a saved key must
execute, spent Gemini without one must block - so a future refactor of the gate cannot quietly
undo the fix. `ClaudeMessagesTest` carries a small strict JSON reader that parses every generated
request body, so a bug in `escape()` — an unescaped control character, a broken surrogate pair —
fails the build rather than the user's Prioritize tap.

## 10. Deliberately out of scope

- **Streaming.** All four features await one complete JSON object; partial output has nothing to
  show and would complicate the review sheet.
- **A server-side proxy.** The key never leaves the device, which is the simplest thing that is
  also honest about where prompts go.
- **Per-feature provider choice.** Ordering is global; a per-feature matrix is a settings
  surface nobody asked for, and the failure it would solve (one provider being better at
  sub-tasks) is not measurable in this app yet.
