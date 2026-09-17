# ADR-0006: Local-first AI with consent-gated cloud fallback

**Status:** Accepted · **Date:** 2026-09-16

## Context
AI adds real value (categorization, receipt/W-2/1099 extraction, explanations). But sending tax return information to third parties is a disclosure under IRC §7216 and a Safeguards Rule vendor risk. AE already uses Ollama locally and OpenRouter for cloud models.

## Decision
- Default provider: **Ollama** container in the client's Compose stack.
- Pipeline order: deterministic rules → history match → local model → (optional) cloud model via Hub relay.
- Cloud use requires per-org admin enablement **and** taxpayer §7216 consent; PII (SSN, names, account numbers) redacted before sending.
- All AI outputs are *suggestions* requiring human confirmation for anything that posts to the ledger or a return.
- Provider interface `LlmClient` so models/providers can be swapped.

## Consequences
Hardware requirements rise for installs using local vision models. Need a benchmark set of receipts/tax docs to choose default models.
