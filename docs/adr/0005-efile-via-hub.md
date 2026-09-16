# ADR-0005: E-file in Phase 2+, transmitted through the Services Hub

**Status:** Accepted · **Date:** 2026-09-16

## Context
MeF requires IRS e-file application, suitability checks, ETIN, annual ATS testing, and A2A strong authentication with IRS-authorized X.509 certificates. FIRE retires after Nov 19, 2026; 1099s go through IRIS from 2027.

## Decision
- v1: generate IRS PDF forms and preparer handoff reports (no e-file).
- Start IRS/IRIS applications during Phase 3 so certification is ready for Phase 4.
- Instances build and validate MeF XML locally; the Hub is the sole transmitter, holding the ETIN and certificates.
- Build 1099 e-file against IRIS only.

## Alternatives
Partner with an existing transmitter (faster, per-return fee, dependency) — keep as fallback if IRS approval stalls (Open Question #6).

## Consequences
Hub becomes filing-season critical infrastructure (99.95% Jan–Apr). Rejection handling and ack sync must be robust and idempotent.
