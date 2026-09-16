# ADR-0001: Self-hosted instances plus a central Services Hub

**Status:** Accepted · **Date:** 2026-09-16

## Context
Anthony wants to deliver Solid self-hosted per client. However several capabilities cannot live in every install: IRS e-file transmission needs one ETIN and IRS-authorized certificates; bank aggregator contracts/API keys belong to AE; tax rule packs and rate tables need a trusted distribution channel.

## Decision
Ship Solid as a Docker Compose deployment per client. Operate a separate AE-run **Services Hub** for licensing/updates, e-file transmission, bank-feed proxying, sales-tax data and optional consent-gated AI relay. Instances only make outbound mTLS calls to the Hub. The Hub never stores books.

## Options considered
| Option | Pros | Cons |
|---|---|---|
| **Self-hosted + Hub** (chosen) | Data ownership selling point; recurring Hub revenue; e-file possible | Two systems to run; must support many app versions |
| Pure self-hosted (no Hub) | Simplest for AE | No e-file, no bank feeds, manual updates |
| Multi-tenant SaaS | Easiest upgrades & support; one DB | Contradicts self-host goal; AE holds all taxpayer data (bigger compliance burden) |

## Consequences
- Hub API must be versioned and backward compatible for ≥2 releases.
- Offline installs still work for books & PDF returns.
- Code should stay tenant-aware (org_id + RLS) so a SaaS option is possible later.
