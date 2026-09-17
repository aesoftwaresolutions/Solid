---
name: build-slice
description: Implement an approved slice spec with tests first, run all checks, and get an independent review. Use after a spec and plan are approved.
disable-model-invocation: true
---
Implement the approved spec in docs/specs/ whose number or name matches: $ARGUMENTS

1. Read CLAUDE.md, the spec, and the approved plan. If the spec is ambiguous, stop and ask.
2. Create a branch `slice/<spec-number>-<name>` if not already on one.
3. For each acceptance criterion, write a failing test first. Run the tests and show they fail for the right reason.
4. Implement the smallest code that makes them pass, following existing patterns and module boundaries.
5. Run the full checks (backend `./mvnw verify`, frontend `npm test` if touched). Paste the summary output. Fix root causes; never weaken or delete tests.
6. Use the spec-reviewer subagent on the diff. If the slice touches auth, PII, money, or external input, also use the security-reviewer subagent. Fix only findings that affect correctness, security, or stated requirements; list the rest as optional.
7. Explain the change to the owner in plain English: what each file does and why, plus anything they should learn.
8. Commit with a descriptive message, update the spec status to `Done` (or `In review`) in docs/specs/README.md, and offer to open a PR with `gh pr create`.
