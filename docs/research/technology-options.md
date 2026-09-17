# Technology Options

## 1. Tax calculation engines

| Option | License | Language | Fit |
|---|---|---|---|
| **IRS Direct File (Fact Graph)** | CC0 (public domain) | Scala / Scala.js | **Best reference.** Declarative graph of tax facts; handles incomplete answers; MeF XML generation. Archived, so port concepts. |
| PolicyEngine US | **AGPL-3.0** | Python | Excellent federal+state model, but AGPL makes it risky for a proprietary product. Use only as a **test oracle** in CI, not linked into the product. |
| PSL Tax-Calculator | Open (check CC0/BSD) | Python | Federal microsimulation; useful for cross-checking federal liability. |
| OpenTaxSolver | GPL | C | Simple form-by-form; license + design not a fit. |
| Build our own | Proprietary | Java | **Chosen.** Rule packs as data (YAML/JSON) evaluated by a Java engine (ADR-0004). |

## 2. Bank feeds

| Provider | Pricing notes (2026) | Fit |
|---|---|---|
| **Teller** | Free dev tier up to 100 live connections; transactions ~$0.30/enrollment/month | Good default: simple, cheap, US-focused |
| Plaid | Largest coverage; sales-negotiated pricing | Enterprise option |
| MX / Finicity (Mastercard) / Akoya | Enterprise | Later |
| **SimpleFIN Bridge** | Low-cost, user brings own token | Great for self-hosted personal users |
| OFX / QFX / CSV import | Free | **Must-have** baseline for every install |

Self-hosted implication: aggregator API keys should live in the **Services Hub**, not in every client install (protects AE's contract and keys). Clients may alternatively plug in their own Plaid/Teller/SimpleFIN credentials.

## 3. Sales tax rates

| Option | Notes |
|---|---|
| Avalara AvaTax | Most complete, most expensive |
| TaxJar (Stripe) | Popular for e-commerce |
| TaxCloud, Zamp, Ziptax, Quaderno | Cheaper APIs |
| Streamlined Sales Tax (SST) member-state rate/boundary files | Free for ~24 SST states |
| State DOR rate tables | Free but manual |

Plan: pluggable `SalesTaxProvider` interface. v1 = manual rates + SST files distributed via Hub; paid API adapter later.

## 4. Documents & AI

| Need | Option |
|---|---|
| Receipt / W-2 / 1099 extraction | Ollama vision models (e.g., Llama 3.2 Vision, Qwen2.5-VL) locally; Tesseract OCR fallback |
| Categorization | Rules first → embeddings + small local LLM → user confirms (learns) |
| Cloud LLM for hard cases | OpenRouter, opt-in with §7216 consent and PII redaction |
| PDF form filling | Apache PDFBox (Apache-2.0) with IRS fillable PDFs |
| MeF XML | JAXB-generated classes from IRS XSDs + Schematron-like business rules |

## 5. Core stack candidates

| Layer | Option A (chosen) | Option B | Why A |
|---|---|---|---|
| Backend | Java 21 + Spring Boot 3 | TypeScript + NestJS | Anthony knows Java; BigDecimal, JAXB/XML, PDFBox mature; strong typing for money |
| DB | PostgreSQL 16+ | MySQL | NUMERIC precision, row-level security, JSONB, strong constraints |
| Frontend | React + TypeScript (Vite) | Thymeleaf + HTMX | Complex interactive grids/interviews; huge ecosystem |
| Jobs | Spring + db-backed queue (e.g., JobRunr or pg-based) | RabbitMQ | Fewer moving parts for self-hosted |
| Files | Local disk / S3-compatible (MinIO) | — | Works on a VPS |
| Search | Postgres full-text | OpenSearch | Fewer containers |
| Packaging | Docker Compose | Kubernetes | Hostinger VPS friendly |
| Auth | Spring Security + WebAuthn/TOTP (or Keycloak) | Auth0 | Self-hostable |

## 6. License hygiene rule

Proprietary product ⇒ **no GPL/AGPL code linked into the shipped app.** Allowed: MIT, BSD, Apache-2.0, CC0/public domain. Enforce with a license scanner in CI (e.g., `license-maven-plugin`, `license-checker` for npm).
