# 064 — Closed means closed

**Status:** Done · **Owner review:** Needed · **CPA review:** Not needed

## Goal
Spec 063 made closing the window stop Solid. But there are more ways out of an application than its window,
and each left something behind:

- **Quitting any other way** — a crash, Ctrl+C, signing out of Windows — left the window open in front of a
  server that had gone.
- **A database that would not stop** left processes holding the installed files. That is what blocked the
  owner's upgrade: six `postgres.exe` survivors, killed by hand.
- **A shutdown that jammed** left the whole thing running with nothing on screen at all.
- And the messages about any of this were **invisible**: everything the desktop setup says happens before
  Spring Boot has configured logging, so it was written into nothing. Including "I have just made a master
  key, back this folder up".

## What changes
One way down, taken by one shutdown hook, whatever started it:

1. **Close the window**, so quitting by any route takes it with it rather than stranding it.
2. **Stop the database**, and then **check that it really stopped** — `close()` has been seen to report
   success while the server was still running, which is exactly how the orphans were made.
3. **Stop waiting, eventually.** If shutting down jams, a backstop halts the JVM after 25 seconds. A daemon
   thread, so it never keeps the application alive by itself, and `halt` rather than `exit` because `exit`
   would queue behind the very hook that is stuck.
4. **Say it out loud.** The desktop setup now uses Spring Boot's deferred log, replayed once logging exists,
   so the early messages reach the log where someone can read them.

A copy killed outright still leaves its database running — no hook can run after a `SIGKILL` or a Task
Manager "End task", and no application can promise otherwise. That is what the takeover on startup is for,
and it now says what it did.

## Acceptance criteria
1. Quitting for any reason other than the window closing takes the window down with it.
2. A graceful stop leaves nothing: no application process, no database process.
3. If the database claims to have stopped but has not, it is stopped anyway.
4. A copy killed outright orphans its database; the next start takes it over and says so, rather than
   refusing to start.
5. The early messages — where the books live, a new master key, a database taken over — appear in the log.
6. Stopping twice is harmless.

## Evidence
One run, pids taken from the processes' own output:

```
1. start                     app=2873  database=2940
2. kill -9 the app           app: gone | database: orphaned, as expected
3. start again               "A database from an earlier run is still holding /tmp/v2/db (process 2940);
                              stopping it."
                             old database: gone (taken over) | app answering: 200
4. close it properly         app: gone | database: gone | stragglers: 0
```

## Out of scope
Surviving a power cut mid-write (PostgreSQL's own crash recovery handles that), and a tray icon that would let
Solid keep running with no window.
