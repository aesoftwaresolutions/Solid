# 033 — Fixes from the security and correctness review

**Status:** Done · **Owner review:** Needed · **Security review:** Done

## Goal
A review of slices 016–032 found eight real defects. Fix them, and add the tests that would have caught them.

## What was wrong, and what now stops it

1. **A recurring entry could post twice.** The "already posted?" check, the posting and the occurrence row were
   three separate transactions, so a cron run and a person clicking *Run* at the same moment could both book the
   rent — and a posted entry is immutable. Now the three are one transaction and the posting carries the
   idempotency key `recurring:<template>:<date>`, so a second attempt returns the first entry.
2. **Opening balances could be entered twice**, silently doubling opening cash and equity, because the existence
   check and the posting were separate transactions. Now they share one transaction with the entity row locked.
3. **The export silently stopped at 1000 journal entries** (and 2000 bank transactions) while its README claimed
   to hold everything — the worst possible failure for the feature whose whole point is getting your data out.
   The export now reads through unlimited listings.
4. **Every negative amount in the CSV was escaped as text.** The formula guard prefixed an apostrophe to anything
   starting with `-`, so a spreadsheet would not add up a column of credits. Plain numbers are left alone; `=`,
   `+`, `@` and non-numeric `-` values are still neutralised.
5. **Long invoices lost lines and their total.** A 60-line invoice drew past the bottom of the page and the
   Amount-due block vanished. Lines now continue onto further pages.
6. **Vault files were interchangeable.** The ciphertext was not bound to its path, so a file swapped in by a bad
   restore would decrypt and be served as someone else's document. Encryption now authenticates the storage key
   (AES-GCM AAD, format version 2; version 1 files still read), and a download re-checks the stored SHA-256.
7. **The master-key check ran after the port opened**, leaving a window in which an upload could be encrypted with
   the wrong key on a mis-restored server. It now runs during startup, before the server accepts requests.
8. **A monthly template stopped forever after 120 occurrences.** The per-run cap was applied from the template's
   first month, so after ten years nothing was ever due again. The walk now starts from what is still outstanding.

## Acceptance criteria
1. An occurrence's posting carries its idempotency key: posting again under that key replays the first entry
   instead of creating a second.
2. A negative amount in the export is `-54.99`, not `'-54.99`.
3. A 60-line invoice PDF contains its first line, its last line and its amount due.
4. A vault file moved to another document's path is refused rather than served.
5. `dueDates` returns at most 120 dates per run and continues past the 120th occurrence on the next run.
6. Everything that passed before still passes.
