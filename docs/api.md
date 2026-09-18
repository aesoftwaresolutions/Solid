# Using the Solid API

Everything the web app does, it does through this API, so anything you want to automate — a nightly recurring-entry
run, a script that imports a statement, a report pulled into a spreadsheet — can do the same.

## The description is generated, not written

Sign in and open **`/swagger-ui/index.html`** on your server to browse every endpoint, or fetch
**`/v3/api-docs`** for the OpenAPI 3 document (handy for generating a client). Both come from the running code, so
they always match the server you are talking to — and both need a signed-in session, because an installation's API
shape is not public information.

## Signing in from a script

```bash
BASE=https://books.example.com

TOKEN=$(curl -sS -X POST "$BASE/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"..."}' | jq -r .token)

# Two-factor is mandatory. Use the current code from your authenticator app:
curl -sS -X POST "$BASE/api/v1/auth/mfa/verify" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"code":"123456"}'

curl -sS "$BASE/api/v1/orgs" -H "Authorization: Bearer $TOKEN"
```

The token is opaque (it means nothing outside this server) and is the session itself, so treat it like a password:
keep it out of shell history and logs, and let it expire rather than storing it forever. Browsers use the
`solid_session` cookie plus the `X-XSRF-TOKEN` header instead; scripts do not need the CSRF header when they use a
bearer token.

## Money

```json
{ "amount": "1234.56", "currency": "USD" }
```

`amount` is a **string**. Parse it as a decimal, never as a float — `0.1 + 0.2` is how books stop balancing. Dates
are ISO-8601 (`2026-09-18`).

## Errors

Failures are RFC 9457 problem documents:

```json
{ "type": "about:blank", "title": "Business rule violated", "status": 409,
  "detail": "The period through 2026-09-30 is closed", "code": "PERIOD_LOCKED" }
```

Read `code` in scripts and show `detail` to people.

## A useful example: run recurring entries nightly

```cron
30 1 * * * /usr/local/bin/solid-recurring.sh >> /var/log/solid-recurring.log 2>&1
```

where the script logs in, completes MFA, and posts whatever is due:

```bash
curl -sS -X POST "$BASE/api/v1/orgs/$ORG/entities/$ENTITY/recurring-entries/run" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"through\":\"$(date -I)\"}"
```

Running it twice is harmless: each occurrence posts once.
