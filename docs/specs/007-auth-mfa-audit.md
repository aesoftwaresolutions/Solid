# 007 — Users, login, mandatory MFA, org membership & audit log

**Status:** Done · **Owner review:** Needed · **Security review:** Needed before any real data

## Goal
Nobody can touch financial data without a password **and** a second factor, users only see organizations they belong to, and security-relevant actions are written to a tamper-evident audit log. Required by the FTC Safeguards Rule (see docs/research/regulatory-compliance.md).

## Scope
- `iam` module: users, password hashing (Argon2id), TOTP MFA (RFC 6238) with recovery codes, server-side sessions, org memberships & roles, Spring Security configuration, CSRF.
- `audit` module: append-only, hash-chained `audit.event` table and `AuditLog` API.
- `platform`: AES-256-GCM `FieldEncryptor` for secrets at rest (MFA secrets), key from `SOLID_MASTER_KEY`.

## API
- `POST /api/v1/auth/signup` `{email, password, displayName}` → 201. Allowed only when no users exist (first user = instance admin) or `solid.auth.open-signup=true`.
- `POST /api/v1/auth/login` `{email, password}` → 200 `{mfaEnrolled, token}` + `solid_session` cookie (HttpOnly, SameSite=Strict, Secure when HTTPS). Session is **pending MFA**.
- `POST /api/v1/auth/mfa/enroll` → `{secret, otpauthUri}` (only while pending and not yet enrolled)
- `POST /api/v1/auth/mfa/activate` `{code}` → `{recoveryCodes:[10]}`; session becomes verified
- `POST /api/v1/auth/mfa/verify` `{code}` or `{recoveryCode}` → session verified
- `POST /api/v1/auth/logout` → 204 · `GET /api/v1/auth/me` → user + memberships
- `GET /api/v1/orgs` → organizations I belong to; creating an org makes me `owner`
- `GET/POST /api/v1/orgs/{orgId}/members` `{email, role}` (owner/admin only)
- `GET /api/v1/orgs/{orgId}/audit-events` (owner/admin only)

Auth for API clients: `Authorization: Bearer <token>` (same session token; CSRF not required). Browser: cookie + `X-XSRF-TOKEN` header matching the `XSRF-TOKEN` cookie on unsafe methods.

## Acceptance criteria
1. Passwords: 12–128 chars; stored as Argon2id; email unique case-insensitively. Second signup when users exist and open signup is off → 403.
2. Every `/api/v1/**` endpoint except signup/login/system info returns **401** without a session, and **401 `MFA_REQUIRED`** with a password-only session (except the MFA endpoints and logout).
3. TOTP: 6 digits, 30 s step, SHA-1, accepts ±1 step; implementation passes the RFC 6238 test vectors. A code can't be reused within its window (replay → 401).
4. MFA secret is stored encrypted (ciphertext differs from secret; decrypts with the master key). Recovery codes are stored hashed and are single-use.
5. Login lockout: 5 consecutive wrong passwords lock the account for 15 minutes (423 `ACCOUNT_LOCKED`), even with the right password. 5 wrong MFA codes revoke the session.
6. Sessions: token is 256-bit random, stored only as SHA-256 hash; idle timeout 30 min, absolute 12 h; logout revokes.
7. Org access: non-member → 404 for any `/orgs/{orgId}/**`; `viewer` gets 403 on POST/PUT/PATCH/DELETE; only `owner`/`admin` manage members and read audit events.
8. Cookie-authenticated unsafe requests without a valid CSRF token → 403; bearer-token requests don't need CSRF.
9. Audit events (hash-chained, app role has INSERT/SELECT only): signup, login_succeeded, login_failed, account_locked, mfa_enrolled, mfa_verified, mfa_failed, recovery_code_used, logout, org_created, member_added, period_lock_changed, journal_entry_reversed. Events never contain passwords, codes, tokens or secrets.
10. Security headers from Spring Security defaults (no-sniff, frame deny, no-cache on API responses).

## Decisions made without owner input
- Server-side opaque sessions (not JWT) so logout/lockout take effect immediately.
- TOTP (authenticator app) first; WebAuthn/passkeys in a later slice.
- `iam.*` and `audit.event` are global tables (not org-scoped by RLS) because identity spans orgs; access is enforced in the application and grants (see RLS allowlist with reasons).
- Master key via environment variable for now; KMS/Vault integration later.

## Out of scope
Login/MFA UI (frontend slice), password reset email, invitations, WebAuthn, per-entity permissions, SSO.
