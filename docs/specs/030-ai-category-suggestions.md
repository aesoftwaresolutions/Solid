# 030 — Category suggestions from a local model

**Status:** Done · **Owner review:** Needed · **Security review:** Needed

## Goal
Rules (spec 008) catch the descriptions you have already seen. A small model running on the same server can have a
sensible guess at the rest — "SQ *BLUE BOTTLE" is probably meals, "GODADDY" probably software — without anything
leaving the machine.

## Scope
New `ai` module. One optional dependency-free HTTP call to an Ollama-compatible server, a suggestion endpoint, and
the wiring that only fills a suggestion when nothing better exists. No automatic posting, ever.

## Data contracts
Configuration (all in `application.yml`, off by default):
- `solid.ai.enabled` (default `false`), `solid.ai.base-url` (default `http://localhost:11434`),
  `solid.ai.model` (default `llama3.2`), `solid.ai.timeout-seconds` (default `8`)

`POST /api/v1/orgs/{orgId}/entities/{entityId}/bank-transactions/{txnId}/suggest` →
`{accountId, code, name, source: "ai", model}` or `{accountId: null, reason}` when there is no usable answer.

## Rules
- **Disabled by default.** With `solid.ai.enabled=false` the endpoint answers `{accountId: null, reason: "..."}`
  and no request is made. Nothing is installed or downloaded by Solid.
- The only thing sent to the model is the transaction's description, its amount, and the list of the entity's
  postable income and expense accounts (code and name). No names, addresses, tax ids, account numbers or balances.
  Documented plainly, because the person deserves to know what leaves the process — and with the default base URL,
  nothing leaves the machine at all.
- The model must answer with a code from the list it was given. Anything else — a made-up code, prose, malformed
  JSON, a timeout — is treated as "no suggestion". The model never invents an account, and its output is data, not
  an instruction: it can only select from the list.
- A suggestion is a **suggestion**: it is returned for a person to accept, never applied, and never posted. The
  existing rule and history suggestions win when they exist.
- Failures are quiet and safe: a connection error, a slow model or a bad answer all produce "no suggestion" with a
  reason, never a 500 and never a retry storm.
- No tax figure, deduction or amount ever comes from a model (CLAUDE.md), and that is stated in the module docs.

## Acceptance criteria
1. With AI disabled, the endpoint answers `accountId: null` with a reason and makes no HTTP call.
2. With AI enabled and a model that answers with a valid code, the endpoint returns that account's id, code, name,
   `source: "ai"` and the model name.
3. A code the entity does not have, a header/archived account, prose instead of JSON, or an empty answer all give
   `accountId: null` with a reason.
4. A model that times out or refuses the connection gives `accountId: null` with a reason — never a 500.
5. The request body sent to the model contains the description and the candidate codes, and does **not** contain the
   entity's legal name or any customer or vendor name.
6. The suggestion is not saved to the transaction: reading it back shows the same `suggestedAccountId` as before.

## Out of scope
Receipt OCR and extraction from images (needs a vision model and its own slice), learning from accepted
suggestions, batch suggestion for the whole queue, any use of a hosted model, and anything that posts automatically.
