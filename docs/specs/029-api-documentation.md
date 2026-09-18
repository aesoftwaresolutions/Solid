# 029 — API documentation

**Status:** Done · **Owner review:** Needed

## Goal
Solid's API is the real interface: scripts, a future mobile client, an accountant's tooling and the `ops` scripts
all speak it. Publish an OpenAPI description generated from the code, so the documentation cannot drift from what
the server actually does, and a browsable page for trying calls.

## Scope
`platform` module plus one dependency (springdoc-openapi, Apache-2.0). Adds the description document, the browsing
UI, and a short page in `docs/` about using the API. No endpoint behaviour changes.

## Data contracts
- `GET /v3/api-docs` — the OpenAPI 3.1 document (signed-in users)
- `GET /swagger-ui/index.html` — the browsable UI (signed-in users)
- Description, version and licence come from the build, so they match the running server.

## Rules
- Both paths require a signed-in session, exactly like the rest of `/api/**`: an installation holds one business's
  books, and its API shape is not public information. An anonymous caller gets 401.
- Authentication is described in the document: a bearer session token, or the session cookie plus the CSRF header.
  The description says plainly that tokens are opaque and MFA must be completed first.
- Money appears in the document as `{amount, currency}` with `amount` a **string**, so nobody generates a client
  that parses it as a float.
- The document is generated from the controllers; there is no hand-written copy of the endpoint list to fall out of
  date.

## Acceptance criteria
1. `GET /v3/api-docs` returns a document whose `openapi` version is 3.x and whose `info.title` names Solid.
2. It contains the real paths, including `/api/v1/orgs/{orgId}/entities/{entityId}/journal-entries`.
3. The Money schema shows `amount` as a string, not a number.
4. An anonymous request to the document or the UI gets 401.
5. A signed-in request for the Swagger UI page succeeds.
6. The document names the bearer and cookie security schemes.

## Out of scope
Hand-written per-endpoint prose, generated client libraries, publishing the document anywhere public, and
versioning the document across releases.
