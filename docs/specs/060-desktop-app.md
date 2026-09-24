# 060 — Solid on one computer

**Status:** Done · **Owner review:** Needed · **CPA review:** Not needed

## Goal
Solid is built to be self-hosted, which means a server, a domain and Docker. That is the right shape for a
firm, and the wrong shape for one person who wants to keep their own books on their own laptop. This slice
makes a **double-click installer**: one file, no server, no terminal, no Docker.

## What the person gets
- Windows: `Solid-<version>.msi` (or `.exe`). macOS: `Solid-<version>.dmg`. Linux: `.deb` and an app image.
- Installing puts Solid in the usual place and adds a Start menu / Applications entry.
- Opening it starts everything it needs and opens the books in their browser. Closing the window stops it.
- No Java to install, no database to set up, no configuration file to edit.

## How it works
- **Java is bundled.** `jpackage` builds a runtime image containing only the modules Solid needs, so the
  installer carries its own JRE. Nothing already on the machine is used, changed, or required.
- **PostgreSQL is bundled too, and it is still real PostgreSQL.** Solid's security rests on row-level
  security and on a non-superuser role (`solid_app`); no embedded toy database has those, so swapping the
  database out would quietly remove the wall between one organization's data and another's. The desktop build
  starts its own PostgreSQL on the loopback interface, from binaries shipped inside the app.
- **Everything lives in one folder** — `%LOCALAPPDATA%\Solid` on Windows, `~/Library/Application Support/Solid`
  on macOS, `~/.local/share/solid` on Linux — holding the database, the uploaded documents and the master key.
  Backing up that one folder backs up everything; the existing backup scripts still work against it.
- **The master key is generated on first run** and written to `master.key` in that folder with owner-only
  permissions. It is what encrypts documents and secrets, so the folder is the thing to back up, and the app
  says so on first run.
- **Nothing listens to the network.** The server binds `127.0.0.1` only. A desktop install is for the person
  sitting at the machine; if two people need the books, that is what the server install is for.

## Rules
- The desktop build changes no business logic. It is a Spring profile (`desktop`) that supplies the database,
  the key and the paths; every module behaves exactly as it does on a server.
- Row-level security must still apply. The bundled database connects as a superuser to run migrations, then
  every pooled connection switches to `solid_app` exactly as on a server — this is load-bearing, and has its
  own test.
- The frontend is served by the app itself in desktop mode (there is no Caddy), including the single-page
  fallback, so a refresh on any screen still works.
- Only permissively licensed parts: the PostgreSQL binaries are under the PostgreSQL License and the wrapper
  is Apache-2.0.
- A second copy started while one is running must say so plainly rather than corrupting the database.

## Acceptance criteria
1. With the `desktop` profile and an empty app folder, the app starts, migrates, and serves the API — no
   environment variables set, no database running beforehand.
2. First run creates `master.key` in the app folder, owner-only where the OS supports it, and reuses it on the
   next start rather than generating a new one (which would orphan every encrypted document).
3. Cross-organization isolation still holds in desktop mode: another organization's data is not visible, so
   row-level security is provably still in force under the bundled database.
4. The server binds the loopback interface only.
5. Uploaded documents land in the app folder, not the working directory.
6. The packaged application starts from the installed files and answers on its port, with the web UI served by
   the app itself (verified on Linux in CI and locally; the Windows and macOS installers are built by the same
   configuration on those runners).
7. A second instance refuses to start and says the app is already running.

## Out of scope
Code signing and notarisation (they need
the owner's certificates), auto-update, and multi-user access to a desktop install.
