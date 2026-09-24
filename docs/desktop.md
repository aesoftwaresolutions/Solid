# Solid on one computer

Solid normally runs on a server: Docker, a domain, several people signing in. This is the other way to run it
— one person, one laptop, one double-click. Spec 060 is the design; this is how to build it and what it does.

## For the person installing it

Download the file for their computer and open it:

| Their computer | The file | What it does |
|---|---|---|
| Windows | `Solid-<version>.msi` | Installs Solid and adds a Start menu entry |
| macOS | `Solid-<version>.dmg` | Drag Solid to Applications |
| Linux | `solid_<version>_amd64.deb` | `sudo apt install ./solid_<version>_amd64.deb` |

Opening Solid starts everything and opens **its own window** — no address bar, no tab among forty others.
Closing that window puts Solid away, database and all. There is no Java to install, no database to set up and
no configuration file.

The window is drawn by a browser the machine already has (Edge on Windows, Chrome, Chromium, Brave) running
chromeless against a profile of Solid's own, so it carries none of the person's extensions or sign-ins. No
browser engine is bundled: JavaFX's WebView is GPL and this project takes only permissive dependencies, and
embedding Chromium would add a hundred megabytes for a window that would look no different. Where no such
browser exists, Solid opens the default browser at `http://127.0.0.1:18080` and says so. `solid.desktop.window`
is `auto`, `browser` or `none`.

**There is no default password.** A fresh install has no accounts, so the first screen offers to create one:
that account is the administrator, and two-factor authentication is set up immediately afterwards — on a
laptop as on a server. A shipped default credential is a shipped vulnerability, so Solid does not have one.

**The one thing to tell them:** everything is in a single folder, and that folder is the backup.

| Their computer | The folder |
|---|---|
| Windows | `%LOCALAPPDATA%\Solid` |
| macOS | `~/Library/Application Support/Solid` |
| Linux | `~/.local/share/solid` |

It holds the database, the uploaded documents, and `master.key` — the key those documents are encrypted with.
Copy the whole folder and the books are safe; lose `master.key` and the encrypted documents cannot be read,
even with the database. `ops/backup.sh` still works if they prefer a script.

Nothing listens to the network: the server binds the loopback interface, so a desktop install is reachable
only from that computer. Two people who need the same books want the server install instead.

## Building the installers

They cannot be cross-built — `jpackage` makes an installer only for the system it is running on.

```bash
ops/desktop/build.sh              # .deb on Linux, .dmg on macOS
ops/desktop/build.sh app-image    # unpacked, for trying it without installing
```

```powershell
powershell -ExecutionPolicy Bypass -File ops\desktop\build.ps1     # .msi on Windows
```

Or let CI do all three: run **Desktop installers** in the Actions tab, or push a `v*` tag. That workflow also
starts each build and smoke-tests it before packaging, which is the only check Windows and macOS get — the
test suite runs on Linux.

Both scripts need JDK 21 and Node. The Windows `.msi` needs WiX 3 installed; without it, ask for `-Type exe`.

## How it differs from a server install

Only in what supplies the plumbing. There is one Spring profile, `desktop`, and it provides what an operator
would otherwise provide; no business logic knows the difference.

- **PostgreSQL is bundled and started by the app**, from binaries inside the installer, listening on the
  loopback interface. It is real PostgreSQL because it has to be: isolation between organizations is row-level
  security plus the non-superuser `solid_app` role, and a lighter database has neither. `DesktopModeTests`
  proves the wall is still up under the bundled database.
- **The master key is generated on first run** into `master.key`, owner-only where the OS supports it, and is
  never regenerated — a second key would orphan every encrypted document.
- **The app serves the web UI itself**, since there is no Caddy. Only the UI's own files are readable without
  signing in; every `/api/**` path is exactly as protected as on a server.
- **One copy at a time.** A second start says so and stops, rather than letting two servers share one data
  directory.
- **The database stops when Solid does**, and a data directory still held by a copy that was killed outright
  is taken over — that server is stopped first. Otherwise its files stay locked and the next upgrade fails,
  which is exactly what happened once before spec 063.
- **Quitting any way at all closes everything** (spec 064): the window goes down with the application, the
  database is checked rather than trusted to have stopped, and a shutdown that jams is cut short after 25
  seconds. A copy ended with Task Manager still orphans its database — no program can run code after being
  killed — which is what the takeover above is for.

## What it does not do yet

The installers are unsigned, so Windows and macOS will
warn until the owner's certificates are added. There is no auto-update: installing a newer version over the
old one migrates the existing folder, as a server upgrade does.
