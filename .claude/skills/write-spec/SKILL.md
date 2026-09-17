---
name: write-spec
description: Interview the owner and write a slice spec in docs/specs/ before any implementation. Use when starting a new feature or slice.
disable-model-invocation: true
---
Write a spec for slice: $ARGUMENTS

1. Read docs/specs/README.md (index), docs/design/07-roadmap.md, and only the design docs/ADRs relevant to this slice.
2. Interview the owner with the AskUserQuestion tool. Skip obvious questions; dig into edge cases, failure modes, data rules, UX, and tradeoffs. Explain technical options in beginner-friendly terms with a recommended option. Keep going until nothing important is ambiguous.
3. Write `docs/specs/NNN-<kebab-name>.md` (next free number) using this structure:
   - Goal (1–3 sentences) and user story
   - Scope: modules and files likely touched
   - Data contracts (tables/columns, types, nullability; API request/response JSON)
   - Behavior: happy path, validation, errors
   - Acceptance criteria: numbered, each independently testable, with concrete example values
   - Failure modes & edge cases
   - Security/privacy notes (PII, permissions, audit events)
   - Out of scope
   - End-to-end verification: exact commands/steps that prove it works
4. Keep it under ~4 pages; if larger, propose splitting into multiple specs.
5. Add a row to docs/specs/README.md with status `Draft`.
6. Do not write application code. Tell the owner to review the spec, then `/clear` and start a plan-mode session.
