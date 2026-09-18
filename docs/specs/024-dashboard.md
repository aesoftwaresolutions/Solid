# 024 — A dashboard that says what to do next

**Status:** Done · **Owner review:** Needed

## Goal
The dashboard currently shows this year's totals and tax readiness. Make it the page a person can open on Monday
morning and know what needs doing: money owed in both directions, paperwork waiting, the month against its budget,
and whether the tax figures for this year are even on file.

## Scope
Frontend only; every number already has an endpoint (spec 010 readiness, 012/013 aging, 008 review queue, 019
budget, 021 rule coverage). Cards load independently so one failure does not blank the page.

## Rules
- Each card loads on its own and shows its own error; one endpoint failing must never hide the others.
- Every number that means "something to do" links to the page where it gets done.
- Amounts use the shared money formatting; nothing is computed in the browser beyond adding up the aging buckets
  the server already returned.
- The tax-coverage card names what is missing for this year and repeats the server's note that nothing is guessed —
  it never implies a figure exists.
- The budget card is shown only when a budget exists for this month; without one it offers to make one.

## Acceptance criteria
1. The dashboard shows this year's income, expenses and net profit, and the readiness list, as it does today.
2. It shows money owed to the entity and money it owes, each with a link to Sales or Purchases.
3. It shows how many bank transactions are waiting to be reviewed, linking to the Bank page.
4. With a budget for the current month, it shows planned versus actual net; with none, it links to the Budget page.
5. It shows how many tax rule packs cover this tax year and how many are missing, with the server's note.
6. If one card's request fails, the other cards still render and the failure is shown in place.

## Out of scope
Charts, a multi-entity or whole-organization overview, anything cached across sessions, and any new endpoint.
