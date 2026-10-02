# Operations & infrastructure

**Support facet — not a business line.** Shared infrastructure costs are left unassigned in Solid.

## What it covers

| System | Role |
|---|---|
| Hostinger VPS | Runs client WordPress sites, n8n, and Solid deployments |
| n8n | Scheduled and webhook workflows for the company and its clients |
| Ollama | Local models — Solid's category suggestions and private agent work |
| OpenRouter | Cloud models when a task needs more than a local model can do |
| Backups | Solid's `ops/backup.sh` and restore drill; site and n8n backups on the VPS |

## Runbooks

Solid's own operation is documented in [docs/operations.md](../../docs/operations.md) and
[ops/](../../ops/). Runbooks for the rest of the stack go in `runbooks/` here — one file per system, covering
how to restart it, back it up and restore it.

Not here: server addresses, SSH keys, API keys, `.env` files or anything from `infra/` notes in the private
workspace.
