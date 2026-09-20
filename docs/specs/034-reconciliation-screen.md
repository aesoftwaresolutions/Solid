# 034 — Reconciliation screen

**Status:** Done · **Owner review:** Needed

## Goal
Reconciling is how you find out the books are right. The API has done it since slice 009; give it the screen it
needs — a statement balance, a list to tick off, and a difference that has to reach zero.

## Scope
Frontend only. A Reconcile page per bank account using the existing endpoints: start, candidates, cleared,
complete, undo, history.

## Rules
- Only one reconciliation is open at a time per bank account; when one is open the page goes straight to it
  instead of offering to start another.
- The difference is shown from the server's own figure, never recomputed in the browser, and **Finish** stays
  disabled until it is zero — the whole point of the exercise is that the difference is real.
- Ticking a line sends only that change; the returned status (cleared count, cleared balance, difference)
  replaces what is on screen, so the numbers always come from the server.
- Completing shows what was locked and when; a completed reconciliation can be undone, with the server's message
  shown if it refuses.
- Amounts use the shared money formatting, and dates stay ISO.

## Acceptance criteria
1. With no reconciliation open, the page asks for the statement date and ending balance and starts one.
2. Candidates are listed with date, memo and amount, and ticking one posts `{lineIds, cleared}` for that line.
3. The cleared count, cleared balance and difference come from the server's response after each change.
4. **Finish** is disabled while the difference is not zero and enabled when it is.
5. Completing calls the complete endpoint and the page then shows the completed state with an **Undo** button.
6. A server refusal (for example completing with a difference) shows the server's message.

## Out of scope
Auto-matching against imported bank transactions, partial reconciliations, reconciliation reports/PDFs, and
reconciling anything other than a bank or credit-card account.
