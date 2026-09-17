# 06 — Security Architecture

Security is a product requirement, not an add-on: the FTC Safeguards Rule, IRS Pub 1345/4557 and §7216 all apply (see research/regulatory-compliance.md).

## 1. Threat model (top risks)

| Threat | Mitigation |
|---|---|
| Account takeover → fraudulent refund filing | Mandatory MFA (WebAuthn preferred, TOTP fallback); risk-based re-auth before e-file/export; login alerts |
| Stolen VPS disk / backups | Encrypted volumes (LUKS); field-level encryption; encrypted restic backups with keys not stored on the VPS |
| SQL injection / XSS | Parameterized queries only (JPA/jOOQ), CSP, React escaping, OWASP ZAP in CI |
| Cross-org data leak in multi-org install | Postgres RLS on `org_id` + authorization checks + tests that try cross-tenant access |
| Insider misuse (bookkeeper browsing SSNs) | Least-privilege roles, SSN masked by default, "reveal" is audited |
| Tampering with books after filing | Immutable posted journal + hash chain + period locks |
| Malicious update / rule pack | Signed images & packs, signature verified before install |
| Supply chain | Dependabot/Renovate, SBOM (CycloneDX), `mvn dependency-check` / `npm audit`, license scan |
| Compromised Hub | Hub stores no books; e-file payloads encrypted; short retention; separate hosting account; mTLS per instance, revocable |
| Leaking tax data to AI providers | Local Ollama default; cloud relay requires §7216 consent + PII redaction; per-org toggle |
| Brute force / bots | Rate limiting (Caddy + Bucket4j), CAPTCHA / challenge on signup & login |

## 2. Identity & access

- Passwords: Argon2id; minimum 12 chars; breached-password check (k-anonymity HIBP API optional).
- MFA required for all users; recovery codes; admin can't disable MFA globally.
- Sessions: 15 min idle timeout for sensitive areas, 12 h absolute; server-side session store; rotate on privilege change.
- RBAC with permissions like `ledger.post`, `tax.return.sign`, `pii.reveal`; scoped to org and optionally entity.
- API tokens: hashed at rest, scoped, expiring.

## 3. Encryption

| Data | Protection |
|---|---|
| In transit | TLS 1.2+ (prefer 1.3) via Caddy; HSTS; mTLS instance↔Hub |
| At rest (disk) | LUKS / provider disk encryption; Postgres on encrypted volume |
| Sensitive fields (SSN, EIN, bank/routing numbers, DOB, IP PIN) | AES-256-GCM envelope encryption: per-org data key encrypted by a master key (file-based KEK with passphrase, or HashiCorp Vault / cloud KMS when available) |
| Documents | Encrypted per object with org data key |
| Backups | restic (AES-256) to offsite storage |
| Deletion | Crypto-shredding: destroy org data key |

## 4. Audit & monitoring

- Append-only, hash-chained `audit.event` (see data model); INSERT-only DB grant.
- Log: auth events, PII reveals, exports, permission changes, journal posts/reversals, return sign & e-file.
- Admin security dashboard: failed logins, new devices, MFA changes, export activity.
- Optional forwarding to syslog / SIEM.

## 5. Secure SDLC

- Branch protection, required reviews, CI with tests + SAST (Semgrep/CodeQL) + dependency scan + secret scanning.
- Threat model review for every new module touching PII.
- Annual third-party pen test of product and Hub; vulnerability scans at least every 6 months (more often before filing season).
- Security.txt + responsible disclosure policy.

## 6. Compliance mapping (summary)

| Obligation | Where addressed |
|---|---|
| FTC Safeguards: MFA, encryption, access control, logging, pen test, IR plan | §§2–5 above + incident runbook |
| IRS Pub 1345 online provider standards | TLS/EV cert on hosted offerings, vuln scans, privacy policy, bot challenge, US registrar, incident reporting |
| IRS Pub 4557 WISP | Ship WISP template + in-app checklist for firm customers |
| §7216 | Consent flows (Rev. Proc. 2013-14 wording), no data egress without consent |
| State breach laws / FTC 500+ notice | Incident response runbook with notification matrix |

## 7. Customer (self-host) responsibilities

Document clearly in a Shared Responsibility Matrix: OS patching (automated unattended-upgrades by installer), firewall, offsite backup target, physical/VPS account security (provider MFA), user management.
