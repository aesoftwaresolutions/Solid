# ADR-0004: Declarative, versioned tax rule packs evaluated by an in-house Java engine

**Status:** Accepted · **Date:** 2026-09-16

## Context
Tax law changes yearly (OBBBA added many provisions that sunset after 2028). Hard-coding rules makes each year a rewrite. Open-source engines are either AGPL (PolicyEngine US) or archived/other language (IRS Direct File, CC0).

## Decision
Build a small Java engine that evaluates signed YAML rule packs (facts graph, tables, form maps, interview, tests), borrowing the **Fact Graph** concepts from IRS Direct File (public domain). Use PolicyEngine US only as an out-of-process test oracle.

## Alternatives
| Option | Why not |
|---|---|
| Hard-code in Java | Yearly rewrites, CPA can't review |
| Embed PolicyEngine US | AGPL-3.0 conflicts with proprietary licensing; Python |
| Port Direct File Fact Graph code | Scala; archived; only federal individual scope — reuse concepts |
| License commercial engine | Costly, usually SaaS-only; revisit for state coverage |

## Consequences
- Up-front cost to build engine + expression language.
- Rule packs become a product (annual update revenue).
- Needs strong test harness (ATS, oracle, golden files).
