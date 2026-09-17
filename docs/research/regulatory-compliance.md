# Regulatory & Compliance Requirements

> Not legal advice. AE Software Solutions should have a tax/privacy attorney review this list before launch.

## 1. Who is regulated?

Once the software prepares tax returns, **AE Software Solutions is treated as a tax return preparer for data-protection purposes** (Treas. Reg. §301.7216-1 covers people who provide software or online services used to prepare returns). The client businesses running our software are also "financial institutions" under the FTC Safeguards Rule when they handle customer financial data.

## 2. IRS e-file program (Phase 2+)

| Role | What it means | Credential |
|---|---|---|
| Software Developer | Writes software that formats returns to MeF specs | ETIN; must pass ATS annually |
| Transmitter | Sends returns to the IRS | ETIN + A2A certificate |
| Online Provider | Lets taxpayers self-prepare & file | Additional security/privacy standards in Pub 1345 |
| ERO | Originates returns for clients (a tax firm) | EFIN — relevant if accounting firms use our product |

Steps: IRS e-Services account → IRS e-file Application (Pub 3112) with suitability/background checks for Principals → ETIN → ATS testing (Pub 1436 individual, Pub 5078 business) → communications test → production. Plan **6–12 months** lead time and repeat ATS each year.

**Online Provider security & privacy standards (Pub 1345)** — confirm current text, but historically:
1. Extended-validation TLS certificate and strong TLS on taxpayer-facing sites
2. Regular external vulnerability scanning
3. Published information privacy & safeguard policies
4. Bot / challenge-response protection on account creation & login
5. Public domain registration with a US-based registrar
6. Reporting security incidents to the IRS promptly (next business day)

**Self-hosted wrinkle:** each client install cannot hold its own ETIN. Returns must be transmitted through AE's central Services Hub (see ADR-0005).

## 3. IRIS (1099 e-file)

- Requires an **IRIS TCC** (Transmitter Control Code) application.
- FIRE retires after Nov 19, 2026; build for IRIS only.

## 4. FTC Safeguards Rule (16 CFR Part 314)

Applies to tax preparers and most businesses handling consumer financial data. Product must *enable* clients to comply and AE must comply itself for the Services Hub.

| Requirement | Product feature |
|---|---|
| Qualified Individual oversees program | Admin role + security dashboard |
| Written risk assessment | Documentation template shipped with installer |
| MFA for anyone accessing customer info | MFA mandatory (TOTP/WebAuthn), cannot be disabled |
| Encryption in transit and at rest | TLS 1.2+ only; encrypted DB volumes + field-level encryption for SSN/EIN/bank numbers |
| Access controls / least privilege | RBAC per organization & entity |
| Activity logging & monitoring | Immutable audit log of logins, reads of sensitive fields, changes, exports |
| Secure disposal | Data retention policies + crypto-shredding of tenant keys |
| Change management | Signed releases, migration logs |
| Pen test annually + vuln scans every 6 months (larger firms) | AE runs these on the product each release |
| Vendor oversight | Document sub-processors (Plaid/Teller, OpenRouter, etc.) |
| Incident response plan | Runbook + in-app breach reporting |
| **FTC breach notice** if ≥500 consumers affected, within 30 days | Incident workflow template |

## 5. IRS Publication 4557 / WISP

Pub 4557 ("Safeguarding Taxpayer Data") expects tax professionals to maintain a **Written Information Security Plan (WISP)**. Ship a WISP template and security checklist for accounting-firm customers.

## 6. IRC §7216 — use & disclosure of tax return information

- Tax return information may not be used or disclosed for purposes other than preparing the return without **specific, signed taxpayer consent** in the format required by Rev. Proc. 2013-14.
- Criminal penalties for knowing/reckless violations.
- **Product impact:**
  - No analytics/telemetry containing return data leaves the install without consent.
  - Sending tax data to a **cloud LLM (e.g., OpenRouter)** is a disclosure → requires §7216 consent flow, off by default. Local Ollama is the default.
  - No marketing use of return data.

## 7. State requirements

- **State breach-notification laws** in all 50 states (shorter timelines than FTC in some).
- **State e-file programs:** most states piggyback on MeF (Fed/State), each with its own schemas, ATS and approval.
- **Privacy laws** (CCPA/CPRA, etc.) — support data export & deletion requests.
- **Sales-tax filing** rules vary per state.

## 8. Other

| Area | Note |
|---|---|
| Record retention | IRS generally 3 years, 7 for some cases; payroll 4 years. Default retention ≥7 years. |
| Bank data | Plaid/Teller developer agreements; CFPB §1033 personal financial data rights rule — monitor status |
| Payments (if we add invoicing payments) | Use Stripe/processor; never store card data (stay out of PCI scope) |
| Accessibility | WCAG 2.1 AA (important for a tax tool) |
| Circular 230 | Applies to people practicing before IRS, not software — but avoid "tax advice" claims |
| Payroll (future) | Form 941/940, W-2 via SSA BSO, state withholding — very large scope; defer or integrate (Gusto, Check) |
