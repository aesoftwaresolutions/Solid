# Security policy for Solid

Solid keeps other people's books. We treat a report of a security problem as the most
important mail we get.

## Reporting a vulnerability

Email **security@aesoftwaresolutions.com**. Please include:

- What you found and the version (`/api/v1/system/info` reports it, or the installer's filename)
- Steps to reproduce, or a proof of concept
- Whether you have shared it with anyone else

You will get a reply within two business days. If the report is a genuine vulnerability,
we will confirm it, tell you our plan and its timeline, and credit you in the release
notes if you would like that. We do not pursue legal action against good-faith research
that follows this policy.

Please give us a reasonable window — we aim for 90 days — to fix and ship before any
public disclosure.

## What is in scope

- The Solid application, its API, and its installable builds (server Docker stack and
  desktop installers)
- Cross-organization isolation (row-level security) — a way past it is our worst bug
- Authentication, MFA, sessions, rate limiting
- The encrypted document store and master-key handling
- The install and upgrade path (installers, auto-deploy script)

## What is out of scope

- Issues in third-party services we do not run (report those to their vendors)
- Denial-of-service that requires already being the instance administrator
- Findings from automated scanners with no demonstrated exploit

## Supported versions

| Version | Supported |
|---------|-----------|
| latest release | yes |
| anything older | no — upgrade first |

Security fixes ship as patch releases and are listed in the changelog. A desktop install
learns about updates by reinstalling the newer installer over the old one; the data folder
is untouched and migrations run on first start.
