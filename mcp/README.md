# Solid MCP server

Lets a local LLM agent manage Solid through the Model Context Protocol. The agent
gets read access to the books (organizations, accounts, review queue, all reports,
backup status) and a deliberately small set of writes (categorize a bank transaction,
create a journal entry, create a customer). Destructive operations — delete, void,
reset — are not exposed at all.

Nothing in the Solid app changes; this talks to the same REST API the web UI uses,
with the same session cookie and CSRF rules.

## Setup

```bash
cd mcp
npm install
```

Configure with environment variables:

| Variable | Required | Purpose |
|---|---|---|
| `SOLID_URL` | no | API origin. `http://127.0.0.1:18080` (desktop) is the default; use `http://localhost:8080` or your server URL otherwise |
| `SOLID_EMAIL` | yes | The account the agent acts as (its role sets what it may do) |
| `SOLID_PASSWORD` | yes | That account's password |
| `SOLID_MFA_SECRET` | if MFA on | The base32 TOTP secret from enrollment, so MFA verifies headlessly. Server computes codes itself — no human |

**Give the agent its own account** with the least role it needs, not the instance
administrator's. Every action it takes lands in the audit log under that identity.

## Wiring it into a client

Claude Desktop (`claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "solid": {
      "command": "node",
      "args": ["/path/to/Solid/mcp/server.js"],
      "env": {
        "SOLID_URL": "http://127.0.0.1:18080",
        "SOLID_EMAIL": "agent@example.com",
        "SOLID_PASSWORD": "...",
        "SOLID_MFA_SECRET": "BASE32SECRET"
      }
    }
  }
}
```

The same stdio shape works for Ollama-based agents (via an MCP client bridge),
Cursor, or any MCP host. Transport is stdio only; there is no network listener,
which matches the desktop install's loopback-only posture.

## Tool inventory

| Tool | Side effect |
|---|---|
| `solid_system_info` | none (no login needed) |
| `solid_login_status` | none |
| `solid_list_organizations` / `solid_list_entities` / `solid_list_accounts` | none |
| `solid_review_queue` | none |
| `solid_profit_and_loss` / `solid_balance_sheet` / `solid_tax_lines` | none |
| `solid_backup_status` | none |
| `solid_list_customers` | none |
| `solid_categorize_transaction` | posts a journal entry (this is the point of the queue) |
| `solid_create_journal_entry` | creates a draft by default; `post: true` posts |
| `solid_create_customer` | creates a customer |

Every response is the API's own JSON — including problem-details (`code`, `detail`)
on failure, so the agent can read and react to e.g. `TRANSACTION_RECONCILED`.

## Notes for agent builders

- Ids come from list calls; there are no magic strings. Start with
  `solid_list_organizations`, drill down.
- Money is `{ "amount": "1200.00", "currency": "USD" }` — strings, never floats,
  matching the API's own rule.
- Journal-entry amounts: positive = debit, negative = credit, lines must net to
  zero or the API refuses it (`BusinessRuleException` surfaces as a 4xx with a code).
- The session auto-refreshes on a 401, so long-running agents do not stall on an
  expired cookie.
