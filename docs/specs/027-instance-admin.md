# 027 — Instance admin area

**Status:** Done · **Owner review:** Needed

## Goal
An instance administrator should be able to answer, on screen: what version is this, is my backup going to be
usable, and which tax figures does this installation actually know?

## Scope
Frontend only. It reads `GET /system/info` (public), `GET /system/backup-status` (instance admin, spec 018) and
`GET /tax/rule-packs` (any signed-in user, spec 021).

## Rules
- Reached from the organizations page; a non-admin who opens it is told plainly that the backup panel is
  administrator-only rather than shown an error page.
- The key fingerprint is shown exactly as the server sends it (16 characters) and labelled as a fingerprint, never
  as a key. The page never asks for, displays or stores the master key itself.
- Byte counts are shown in MB/GB for reading, with the raw number kept in the title attribute.
- The backup panel repeats the one operational fact that matters: this fingerprint must match the
  `SOLID_MASTER_KEY` on any server a backup is restored to, and links to the operations guide.
- Rule packs are listed with their source text as written — never summarized, so nobody reads a paraphrase as a
  citation.

## Acceptance criteria
1. The page shows the app version and database schema version.
2. It shows the instance id, key fingerprint, database size, and the number and size of stored documents.
3. A non-administrator sees an explanation in the backup panel, and the rest of the page still works.
4. Rule packs are listed with title, the years covered, their source text and any todos.
5. Byte counts are shown in human units with the exact number available on hover.
