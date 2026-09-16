# ADR-0003: Immutable double-entry journal with integer minor units

**Status:** Accepted · **Date:** 2026-09-16

## Decision
- Store money as `bigint` cents + ISO currency; `BigDecimal` in Java; string decimals in JSON.
- Single signed `amount_minor` per journal line (+debit / −credit); DB constraint that each posted entry sums to zero.
- Posted entries are immutable; corrections are reversing entries. Period locks prevent back-dating.
- Hash-chain posted entries for tamper evidence.
- Every account maps to a tax line code.

## Why
Floating point and editable history are the two classic accounting-software failure modes. Immutable entries give auditors and the IRS a trustworthy trail and make the ledger → tax bridge reproducible.

## Trade-offs
Users can't "just edit" a posted transaction — UI must make reversal + re-entry feel like an edit. Slightly more storage.
