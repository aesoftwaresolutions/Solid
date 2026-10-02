# AE Software Solutions — how the business is organized

This folder is the operating map of the company that builds Solid. It sits beside the product code so the
two stay in step: each revenue facet below is also a **business line** in Solid, so the books report
income and costs per facet (see [docs/specs/069-business-lines.md](../docs/specs/069-business-lines.md)).

> **This repository is public.** Nothing in `business/` may contain client names, prospect lists,
> contact details, contracts, credentials, internal pricing notes or outreach scripts. Those stay in the
> private project workspace. `.gitignore` blocks `private/` folders and CSV/XLSX files under `business/`
> as a backstop — it is not a substitute for checking before you commit.

## The facets

The company has five facets that earn money and three that support them.

### Revenue facets (each is a business line in Solid)

| # | Facet | What it sells | Folder | Business line |
|---|---|---|---|---|
| 1 | Automation & AI | Always-On Front Desk assistant, n8n workflow builds, monthly care | [automation-ai/](automation-ai/) | `Automation & AI` |
| 2 | Websites & hosting | WordPress design and rebuilds, hosting and site care on the VPS | [websites-hosting/](websites-hosting/) | `Websites & hosting` |
| 3 | Custom software | Proprietary software built to a client's specification | [custom-software/](custom-software/) | `Custom software` |
| 4 | Software products | Solid licences, productized templates and kits | [products/](products/) | `Software products` |
| 5 | Audits & consulting | The missed-lead audit and other paid advice | [audits-consulting/](audits-consulting/) | `Audits & consulting` |

### Support facets (costs, not business lines)

| Facet | Covers | Folder |
|---|---|---|
| Sales & marketing | How work is found and won | [sales-marketing/](sales-marketing/) |
| Operations & infrastructure | The VPS, n8n, local and cloud models, backups | [operations/](operations/) |
| Finance & admin | Bookkeeping in Solid, tax, the LLC itself | [finance-admin/](finance-admin/) |

Support costs that serve every facet (hosting the company site, the accountant, software subscriptions) are
left **unassigned** in Solid and show as *Shared / overhead* in the business-line report. Assign a cost to a
facet only when it exists for that facet alone — a client's dedicated server is `Websites & hosting`; the VPS
that runs everything is shared.

## Rules for this folder

1. One README per facet says what the facet is, what it sells, how its money is tracked and what belongs
   in its folder.
2. Playbooks and templates go in the facet that owns them. Anything client-specific stays private.
3. Adding a revenue facet means adding it here **and** as a business line in Solid, in the same week, so
   the books never lag the business.
