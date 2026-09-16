# Market Analysis

_Researched September 2026. Prices change often — re-check before using in marketing._

## 1. The gap we're targeting

Today a small business owner typically pays for **three or four separate products**:

1. Bookkeeping (QuickBooks Online, Xero, FreshBooks, Wave, Zoho Books)
2. Personal tax prep (TurboTax, H&R Block, TaxAct, FreeTaxUSA)
3. Business tax prep (TurboTax Business, or a CPA)
4. Sales tax / 1099 tools (Avalara, TaxJar, Track1099, Tax1099)

Data is re-keyed between them every year. Nobody offers a **single, self-hosted system** where the business books flow directly into the owner's personal return (Schedule C/E/K-1 → 1040) with year-round tax estimates. That is the core differentiator.

A second differentiator is **data ownership**: every major competitor is SaaS-only. A self-hosted option appeals to privacy-conscious owners, accounting firms that want to host clients themselves, and businesses in regulated industries.

## 2. Bookkeeping competitors (2026 list prices, per month)

| Product | Tiers | Notes |
|---|---|---|
| QuickBooks Online | Simple Start $35 (1 user) · Essentials $65 (3) · Plus $99 (5) · Advanced $235 (25) | Market leader; payroll and multi-entity cost extra |
| Xero | Starter $29 (20 invoices/mo) · Standard $46 · Premium $62 | Unlimited users on all plans |
| FreshBooks | Lite $19 (5 clients) · Plus $33 (50) · Premium $60 | Service businesses / freelancers |
| Wave | Free · Pro $16 | Payroll and payments now paid add-ons |
| Zoho Books | Free (<$50K revenue) · $20 · $50 · $70 | Strong if already in Zoho ecosystem |

## 3. Tax prep competitors

| Product | Positioning |
|---|---|
| TurboTax (Intuit) | Premium consumer brand; highest prices |
| H&R Block | Online + in-person assisted |
| TaxAct / TaxSlayer | Mid-price |
| FreeTaxUSA | Free federal, low-cost state; frequently rated best value in 2026 |
| IRS Direct File | **Discontinued** — not available for the 2026 filing season (see tax-landscape doc) |

The end of Direct File leaves an opening for low-cost, trustworthy filing tools.

## 4. Open-source / self-hosted accounting

| Project | Notes |
|---|---|
| Akaunting | PHP/Laravel, SMB invoicing & accounting |
| ERPNext | Python/Frappe, full ERP, heavy |
| Bigcapital | Node/TypeScript, modern SMB accounting |
| GnuCash, Beancount, Ledger | Personal/technical double-entry |

None of these include US income tax preparation. They are useful references for UX and ledger design, but **check licenses** (several are GPL/AGPL) before copying any code into a proprietary product.

## 5. Target customer segments (proposed order)

1. **Sole proprietors / single-member LLCs** — Schedule C filers who today use Wave/QuickBooks + TurboTax. Simplest tax scope, biggest pain from re-keying.
2. **Households with rental property or side businesses** — Schedule E/C.
3. **S-corps and partnerships (1–10 owners)** — 1120-S / 1065 → K-1 → owner 1040.
4. **Accounting / bookkeeping firms** — host many clients on one install (multi-org).

## 6. Pricing hypotheses (to validate)

- **Self-hosted license:** annual per-organization license (e.g., tiered by number of entities) + optional install/maintenance service from AE.
- **Services Hub add-ons (usage-based):** bank-feed connections, e-file transmissions, 1099 e-file, sales-tax rate updates, tax-year rule pack updates.
- **Managed hosting:** AE hosts the instance on a Hostinger VPS for clients who don't want to self-host.

Annual tax rule updates are a natural recurring revenue driver — returns can't be prepared without the current year's pack.
