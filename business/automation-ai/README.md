# Automation & AI

**Business line in Solid:** `Automation & AI`

## What it sells

- **Always-On Front Desk** — a website assistant that answers inquiries around the clock, captures leads and
  passes them to the owner by SMS, email and a simple CRM. A one-off build plus a monthly care fee.
- **Workflow automation** — n8n workflows for lead capture, follow-up, onboarding and invoicing.
- **Monthly care** — monitoring, fixes and a monthly report for every live install.

## How its money is tracked

| Money | Solid account (Schedule C template) | Business line |
|---|---|---|
| Build fees | 4010 Sales and Service Revenue | Automation & AI |
| Monthly care fees | 4010 Sales and Service Revenue (a repeating invoice) | Automation & AI |
| Model and API usage billed by a client's install | 5010 Purchases and Materials | Automation & AI |
| Shared model subscriptions used across the company | 6220 Software and Subscriptions | *unassigned* |

Set the business line on the invoice; every income line on it takes that line when the invoice is finalized.

## What belongs in this folder

- `playbooks/` — the go-live checklist, launch tests and monthly-report procedure, written generally.
- `templates/` — workflow and system-prompt templates **with placeholders only**.

Not here: any client's filled-in prompt, workflow export, webhook URL or lead data.
