# Building Solid with Claude Code — Playbook

_Researched September 2026 from Anthropic's Claude Code docs and practitioner write-ups (sources at bottom)._

## TL;DR — the 8 rules

1. **Never prompt "build the app."** Build one thin, testable *vertical slice* at a time (e.g., "post a balanced journal entry via API and see it in the trial balance").
2. **Spec → fresh session → plan → code.** Have Claude interview you and write a spec file; `/clear`; use plan mode; approve the plan; then implement.
3. **Give Claude a check it can run.** Tests + build must pass (`./mvnw verify`, `npm test`). Without a pass/fail signal, *you* become the test suite.
4. **Tests before code for money and tax.** You (and later your CPA) write the expected numbers; Claude writes code to pass them. Claude must never invent tax figures.
5. **Keep CLAUDE.md short (<200 lines).** Put topic rules in `.claude/rules/` scoped by path, and procedures in skills.
6. **Manage context aggressively.** `/clear` between tasks. If you've corrected Claude twice on the same thing, `/clear` and write a better prompt.
7. **Use a second opinion.** A reviewer subagent checks the diff against the spec in a fresh context before you merge.
8. **Small PRs you can actually read.** As a beginner, ask Claude to explain every diff. If you can't understand it, it's too big.

---

## 1. Why this matters for Solid specifically

Claude's biggest constraint is its **context window**: it fills up fast and quality drops as it fills. A 20-document, multi-year accounting/tax platform will never fit in one session. So the whole strategy is:

- The **repo** holds long-term memory (design docs, specs, ADRs, CLAUDE.md, tests).
- Each **session** does one slice with a clean context.
- **Automated checks** decide when a slice is done, not vibes.

Accounting software also has a failure mode most apps don't: code that *looks* right but is off by a cent or applies last year's tax limit. That's why verification (rule 3–4) is non-negotiable here.

## 2. One-time setup (do this before any app code)

Starter versions of these files come with this playbook. Summary:

| Piece | Purpose | File |
|---|---|---|
| `CLAUDE.md` | Commands, stack, non-negotiable rules — loaded every session | `/CLAUDE.md` |
| Path-scoped rules | Only load when Claude touches matching files (ledger, tax, migrations) | `.claude/rules/*.md` |
| Skills | Repeatable procedures you invoke: `/write-spec`, `/build-slice` | `.claude/skills/*/SKILL.md` |
| Subagents | Fresh-context reviewers: spec reviewer, security reviewer | `.claude/agents/*.md` |
| Hooks | Things that must happen *every time* (format, block edits to applied migrations, tests gate) | `.claude/settings.json` (add after scaffold exists) |
| `gh` CLI | Most context-efficient way for Claude to use GitHub (issues, PRs) | install + `gh auth login` |
| Spec index | Table of every slice and its status | `docs/specs/README.md` |

Also:
- Install `gh`, Git, Java 21, Docker Desktop, Node LTS on your machine before starting.
- Run `/permissions` to pre-approve safe commands (`./mvnw test`, `npm test`, `git status`, `git diff`) so you aren't clicking "allow" 50 times.
- Never give Claude production secrets or your Hostinger root password. Use `.env` files that are gitignored.

## 3. The per-slice workflow

```
┌ 1. Pick next slice from docs/specs/README.md (or roadmap)
├ 2. /write-spec <slice>   → Claude interviews you (AskUserQuestion) → docs/specs/NNN-name.md
├ 3. Review/edit the spec yourself (acceptance criteria + out of scope!)
├ 4. /clear                → fresh context
├ 5. Plan mode (Shift+Tab) → "Read @docs/specs/NNN-name.md and make an implementation plan"
│     Ctrl+G to edit the plan; push back on anything you don't understand
├ 6. Approve plan          → /build-slice NNN   (tests first, then code, run checks)
│     Optional: /goal "every acceptance criterion in docs/specs/NNN has a passing test and ./mvnw verify exits 0, or stop after 30 turns"
├ 7. Review                → "Use the spec-reviewer subagent on this diff against docs/specs/NNN"
│                            + security-reviewer for anything touching auth, PII, or money
├ 8. Ask Claude to explain the diff to you in plain English; ask questions until it makes sense
└ 9. Commit on a branch, open a PR with gh, merge, mark spec Done in the index, /clear
```

Rules of thumb:
- A slice should be **≤ 1–2 days of work** and touch one or two modules. If the spec runs past ~4 pages, split it.
- If you could describe the change in one sentence (rename, typo, add a log line), skip the spec and plan — just ask.
- Name sessions (`/rename ledger-posting`) so you can resume with `claude --resume`.

## 4. Suggested first slices (maps to Roadmap Phase 0–1)

| # | Slice | "Done" check |
|---|---|---|
| 001 | Project skeleton: Spring Boot + Postgres (Testcontainers) + React/Vite + Docker Compose + CI + license scan | `./mvnw verify`, `npm test`, `docker compose up` health check green in CI |
| 002 | `Money` value type (bigint cents, BigDecimal math, string JSON) | Property-based tests: add/subtract/allocate never lose a cent |
| 003 | Orgs & entities + Flyway migrations + RLS | Test proves org A can't read org B |
| 004 | Chart of accounts with tax-line codes + Schedule C template | Seeded COA test |
| 005 | Post journal entry (balanced, immutable, period lock) | DB rejects unbalanced entry; update/delete of posted rows fails |
| 006 | Trial balance + P&L + balance sheet reports | Golden-file tests from a hand-made sample ledger |
| 007 | Users, login, mandatory MFA, audit log | Integration tests + security-reviewer pass |
| 008 | CSV/OFX import → review queue → categorize → journal entry | Import fixture files produce expected entries; re-import is deduped |
| 009 | Bank reconciliation | Fixture statement reconciles to zero difference |
| 010 | Tax-line report ("hand this to your preparer") | Matches hand-computed Schedule C totals for sample ledger |

Do the tax engine (Phase 2) only after these are solid.

## 5. Prompt patterns (copy and adapt)

**Interview → spec** (or just run `/write-spec`):
```
I want to build slice 005: posting journal entries (see @docs/design/03-data-model.md and @docs/adr/0003-ledger-model.md).
Interview me in detail using the AskUserQuestion tool. Dig into edge cases, failure modes and tradeoffs, not obvious things.
Then write docs/specs/005-journal-posting.md with: goal, data contracts, API, acceptance criteria (numbered, testable),
failure modes, out of scope, and an end-to-end verification step.
```

**Plan (in plan mode, fresh session):**
```
Read @CLAUDE.md and @docs/specs/005-journal-posting.md. Look at how existing modules are structured under backend/src/main/java.
Make an implementation plan: files to create/change, migration SQL, tests mapped to each acceptance criterion, and risks.
Don't write code yet. Flag anything in the spec that is ambiguous.
```

**Implement with verification:**
```
Implement the approved plan. Write the failing tests for each acceptance criterion first and show me they fail.
Then implement until they pass. Run ./mvnw verify at the end and paste the summary. Fix root causes; never delete or weaken a test to make it pass.
```

**Bug report:**
```
Trial balance is off by $0.01 for @backend/src/test/resources/fixtures/ledger-rounding.csv.
Write a failing test that reproduces it, find the root cause (check Money.allocate and report aggregation), fix it, and run the full suite.
```

**Review:**
```
Use the spec-reviewer subagent to review the current diff against docs/specs/005-journal-posting.md.
Report only gaps that affect correctness or stated requirements, not style.
```

**Learning (use this a lot):**
```
Explain this diff to me like I'm a beginner who knows basic Java and SQL. What does each file do, why was it designed this way,
and what would break if I removed it? Quiz me with 3 questions at the end.
```

**Tax content (Phase 2+):**
```
Add the TY2026 standard deduction table to packs/US/2026. Use ONLY the values in @docs/tax-sources/rev-proc-2025-xx-extract.md.
Every value must cite its source line. If a value is missing, write TODO and fail the pack validation test — do not guess.
```

## 6. Accounting- and tax-specific guardrails

- **No floats, ever.** Enforce with a test/ArchUnit rule that fails if `double`/`float` appear in money code. (Written in `.claude/rules/money-and-ledger.md`.)
- **Expected numbers come from humans.** Keep hand-verified fixtures in `src/test/resources/fixtures/` and golden files. Claude may *add* tests but must not change expected values without you approving.
- **Tax values need citations.** Put IRS source extracts in the repo (`docs/tax-sources/`) and tell Claude to use only those. A missing value = TODO + failing test, never a guess from memory (tax law changes yearly; model knowledge can be stale).
- **Oracle tests:** later, compare against PolicyEngine in a separate CI container (see tax engine doc).
- **Migrations are append-only.** A hook blocks edits to already-applied Flyway files.
- **PII:** never put real SSNs or bank data in fixtures, prompts, or screenshots. Use obviously fake data (SSN 000-00-0000 style test ranges).

## 7. Context & session hygiene

| Situation | Do this |
|---|---|
| Switching to an unrelated task | `/clear` |
| Corrected Claude twice on same issue | `/clear`, rewrite prompt with what you learned |
| Need broad codebase research | "Use a subagent to investigate …" (keeps your main context clean) |
| Quick side question | `/btw …` (doesn't enter history) |
| Long session getting sluggish | `/compact Focus on the current slice, files changed, and test commands` |
| Risky experiment | Just try it; `Esc Esc` / `/rewind` to roll back (still commit to git often — rewind doesn't track Bash changes) |
| Check what loaded | `/context` |
| CLAUDE.md being ignored | It's too long or vague — prune it; convert must-always rules into hooks |

## 8. Hooks to add once the skeleton exists (slice 001)

Put in `.claude/settings.json` (ask Claude: "add these hooks and a script for each; test them"):

1. **PostToolUse on Edit|Write** → run formatter (Spotless for Java, Prettier for TS) on the changed file.
2. **PreToolUse on Edit|Write** → block edits to `backend/src/main/resources/db/migration/V*.sql` files that already exist on `main` (exit code 2 blocks the action).
3. **Stop hook** → run the fast test suite; block finishing if it fails (Claude Code gives up after 8 consecutive blocks, so it can't loop forever).

Hooks are deterministic; CLAUDE.md is only advice. Anything that must *always* happen belongs in a hook.

## 9. Scaling up later

- **Worktrees / parallel sessions:** once modules are stable, run separate sessions on independent slices (e.g., banking import vs. reports) in separate git worktrees.
- **Writer/Reviewer:** one session implements; another fresh session reviews. Or one writes tests, another writes code to pass them.
- **Headless (`claude -p`) in GitHub Actions:** automated PR review, or nightly "run the oracle comparison and open an issue for diffs."
- **Don't over-chase reviewer findings.** Reviewers asked to find gaps always find some; only fix what affects correctness or requirements.

## 10. Common failure patterns to watch for

| Pattern | Symptom | Fix |
|---|---|---|
| Kitchen-sink session | Claude "forgets" rules, mixes tasks | `/clear` between tasks |
| Mega-prompt | "Build the ledger, banking and reports" → huge unreviewable diff | One slice per spec |
| Trust-then-verify gap | Plausible code, fails edge cases | Tests with hand-made expected values |
| Test tampering | Claude edits expected values or skips tests | Rule in CLAUDE.md + review every test diff |
| Bloated CLAUDE.md | Rules ignored | <200 lines; move detail to rules/skills |
| Infinite exploration | Claude reads hundreds of files | Scope the question or use a subagent |
| Stale knowledge | Wrong library API or tax value | Give docs URLs / source extracts; verify |

## Sources

- Anthropic — Best practices for Claude Code: https://code.claude.com/docs/en/best-practices
- Anthropic — How Claude remembers your project (CLAUDE.md, rules, auto memory): https://code.claude.com/docs/en/memory
- Anthropic — /goal: https://code.claude.com/docs/en/goal
- Anthropic — Hooks guide: https://code.claude.com/docs/en/hooks-guide
- Anthropic — Skills: https://code.claude.com/docs/en/skills
- Anthropic — Subagents: https://code.claude.com/docs/en/sub-agents
- Joshua McDonald — Spec-driven development with Claude Code for a small team on a big project: https://joshmcdonald.medium.com/running-a-small-team-on-a-big-project-spec-driven-development-with-claude-code-9a1b97f58551
- DataCamp — Spec-driven development with Claude Code tutorial: https://www.datacamp.com/tutorial/spec-driven-development-with-claude-code
