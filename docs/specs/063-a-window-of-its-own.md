# 063 — A window of its own

**Status:** Done · **Owner review:** Needed · **CPA review:** Not needed

## Goal
Spec 060 left the desktop app opening a browser tab, and said a window was a later slice. This is it. Solid
should look and behave like an application on the machine: its own window, no address bar, no tab sitting
among forty others, and closing it puts Solid away.

## What it does not do
It does not stop being a web application. The window shows the same screens over the same loopback address —
that is how every desktop app of this shape works, Slack and VS Code included. What changes is that nobody
has to know that: no URL to type, no tab to lose.

## How, and why this way
The window is a **chromeless browser window** (`--app=`), driven by whichever Chromium-family browser the
machine already has — Edge on Windows, Chrome, Chromium, Brave. It runs against a profile folder of Solid's
own, so it carries none of the person's extensions, sign-ins or history, and appears as its own thing.

The alternative was to bundle a browser engine. Rejected on two grounds. JavaFX's WebView is GPL with the
classpath exception, and this project takes only permissive dependencies — a rule worth more than a window.
Embedding Chromium adds a hundred megabytes and a second thing to keep patched, for a window that would look
no different. Windows and macOS both ship a suitable browser; Linux nearly always has one.

If none is found, Solid opens the default browser as before and says so. A missing browser must not mean a
missing application.

## Closing the window closes Solid
The launcher waits for the window's process to end, then shuts the application down — the behaviour spec 060
promised. That makes shutdown ordinary rather than exceptional, which matters for the next part.

## Stopping the database, properly
Closing the window (and quitting any other way) must stop the bundled PostgreSQL. It did not always: six
orphaned `postgres.exe` processes survived a run on the owner's machine, kept file handles open on the
installed files, and blocked the next upgrade until they were killed by hand.

Two fixes, because there are two ways it happens:
- **On the way out:** the database is stopped in a shutdown hook, and Solid waits for it rather than racing
  the JVM's exit.
- **On the way in:** a data directory that still names a live server in `postmaster.pid` is not simply used.
  Solid stops that process first, then starts its own. A previous copy killed outright — task manager, a
  power cut, an installer — must not leave the app unable to start.

## Data contract
- `solid.desktop.window` — `auto` (default: a window if a browser can be found, otherwise the default
  browser), `browser` (always the default browser), `none` (open nothing; for tests and servers).
- `solid.desktop.open-browser=false` keeps working and means `none`.

## Acceptance criteria
1. With a Chromium-family browser present, starting Solid opens a chromeless window of its own, on a profile
   folder inside the Solid data folder rather than the person's own browser profile.
2. Closing that window shuts Solid down, and the bundled PostgreSQL stops with it — no process left behind.
3. With no such browser, Solid opens the default browser and stays running, saying which it did.
4. A data directory whose `postmaster.pid` names a live process is taken over: that process is stopped and
   Solid starts normally rather than failing.
5. A `postmaster.pid` naming a process that is long gone is ignored, not acted on — the pid may belong to
   something else entirely by now.
6. `solid.desktop.window=none` opens nothing, so tests and headless runs are unaffected.

## Out of scope
An icon in the window's title bar beyond what the browser shows, a tray icon, and single-instance activation
(clicking the shortcut twice already says the app is running).
