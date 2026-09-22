#!/usr/bin/env python3
"""Proves a running Solid stack actually works, from the outside.

Run it against the URL the web container serves (default http://localhost:8080) after
`docker compose up --build`. It signs up a throwaway user, turns on MFA, creates an
organization and entity, applies a chart of accounts, posts one balanced entry, and checks
that the reports agree with it. Nothing here touches the database directly: if this passes,
the packaged jar, the migrations, the reverse proxy and the API all work together.

On a brand-new instance it signs itself up. Once the first account exists, sign-up closes (as it should),
so give it an existing user instead:

    SOLID_SMOKE_EMAIL=you@example.com SOLID_SMOKE_PASSWORD=... SOLID_SMOKE_TOTP_SECRET=... \
        python3 ops/smoke-test.py https://books.example.com

The TOTP secret is the one shown when that user turned on MFA. Make a dedicated user for this if you would
rather not put your own secret in a shell.

Usage:  python3 ops/smoke-test.py [base-url]
Exit code 0 means everything passed; anything else prints what failed.
"""

import base64
import hashlib
import hmac
import json
import struct
import sys
import time
import urllib.error
import os
import urllib.request
import uuid

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080").rstrip("/")
API = BASE + "/api/v1"
TOKEN = None


def totp(secret_b32: str) -> str:
    """The six digits an authenticator app would show right now (RFC 6238, 30-second steps)."""
    key = base64.b32decode(secret_b32 + "=" * (-len(secret_b32) % 8))
    counter = struct.pack(">Q", int(time.time()) // 30)
    digest = hmac.new(key, counter, hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    code = struct.unpack(">I", digest[offset:offset + 4])[0] & 0x7FFFFFFF
    return f"{code % 1_000_000:06d}"


def call(method: str, path: str, body=None, expect=(200, 201)):
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(API + path, data=data, method=method)
    request.add_header("Content-Type", "application/json")
    if TOKEN:
        request.add_header("Authorization", "Bearer " + TOKEN)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            text = response.read().decode()
            status = response.status
    except urllib.error.HTTPError as e:
        text, status = e.read().decode(), e.code
    if status not in expect:
        raise SystemExit(f"FAIL {method} {path} -> {status}\n{text[:500]}")
    if status == 401:
        return "retry"
    return json.loads(text) if text else None


def check(label: str, actual, expected):
    if actual != expected:
        raise SystemExit(f"FAIL {label}: expected {expected!r}, got {actual!r}")
    print(f"  ok  {label} = {actual}")


print(f"Smoke-testing {BASE}")

info = call("GET", "/system/info")
print(f"  ok  {info['name']} {info['version']}, schema {info['databaseSchemaVersion']}")

email = os.environ.get("SOLID_SMOKE_EMAIL")
password = os.environ.get("SOLID_SMOKE_PASSWORD")
secret = os.environ.get("SOLID_SMOKE_TOTP_SECRET")

if email:
    TOKEN = call("POST", "/auth/login", {"email": email, "password": password})["token"]
    if not secret:
        raise SystemExit("FAIL set SOLID_SMOKE_TOTP_SECRET too: that user's MFA secret is needed to sign in")
    # A code can only be used once. If this one has already been spent (a login moments ago, or a previous
    # run), wait for the next 30-second window rather than failing for a reason that fixes itself.
    if call("POST", "/auth/mfa/verify", {"code": totp(secret)}, expect=(200, 204, 401)) == "retry":
        wait = 30 - int(time.time()) % 30 + 1
        print(f"  ..  that code was already used; waiting {wait}s for the next one")
        time.sleep(wait)
        call("POST", "/auth/mfa/verify", {"code": totp(secret)}, expect=(200, 204))
    print(f"  ok  signed in as {email}")
else:
    # A fresh instance lets the first account sign itself up; after that, sign-up closes on purpose.
    email = f"smoke-{uuid.uuid4()}@example.test"
    password = "correct horse battery staple"
    signup = call("POST", "/auth/signup",
                  {"email": email, "password": password, "displayName": "Smoke Test"}, expect=(201, 403))
    if signup is not None and signup.get("status") == 403:
        raise SystemExit(
            "FAIL sign-up is closed on this instance, which is correct once it has a user.\n"
            "     Re-run with SOLID_SMOKE_EMAIL, SOLID_SMOKE_PASSWORD and SOLID_SMOKE_TOTP_SECRET set for an\n"
            "     existing user (see the comment at the top of this script).")
    TOKEN = call("POST", "/auth/login", {"email": email, "password": password})["token"]
    secret = call("POST", "/auth/mfa/enroll", {})["secret"]
    call("POST", "/auth/mfa/activate", {"code": totp(secret)})
    print("  ok  signed up, logged in, MFA on")

org = call("POST", "/orgs", {"name": "Smoke Test Org", "kind": "business"})["id"]
entity = call("POST", f"/orgs/{org}/entities", {"kind": "sole_prop", "legalName": "Smoke Test LLC"})["id"]
base = f"/orgs/{org}/entities/{entity}"
accounts = {a["code"]: a["id"] for a in call("POST", base + "/accounts/apply-template", {"template": "schedule_c"})}
print(f"  ok  organization, entity and {len(accounts)} accounts")

money = lambda amount: {"amount": amount, "currency": "USD"}
call("POST", base + "/journal-entries", {
    "entryDate": "2026-02-01", "memo": "Smoke test sale", "post": True,
    "lines": [{"accountId": accounts["1010"], "amount": money("1500.00")},
              {"accountId": accounts["4010"], "amount": money("-1500.00")}],
})

pnl = call("GET", base + "/reports/profit-and-loss?from=2026-01-01&to=2026-12-31")
check("net income", pnl["netIncome"]["amount"], "1500.00")
balance = call("GET", base + "/reports/balance-sheet?asOf=2026-12-31")
check("balance sheet balances", balance["balanced"], True)
cash = call("GET", base + "/reports/cash-flow?from=2026-01-01&to=2026-12-31")
check("closing cash", cash["closingCash"]["amount"], "1500.00")
chain = call("GET", base + "/journal/verify")
check("hash chain valid", chain["valid"], True)
setup = call("GET", base + "/setup")
check("setup reports the first entry done",
      next(s["status"] for s in setup["steps"] if s["key"] == "first_entry"), "done")

print("PASS — the packaged stack works end to end.")
