# 039 — Statement of cash flows

**Status:** Done · **Owner review:** Needed · **CPA review:** Needed

## Goal
Profit is an opinion; cash is a fact. Show where the money actually went in a period, split into operating,
investing and financing, from the ledger itself.

## Scope
`reporting` module. One report, no new tables. It reads posted journal lines that touch a cash account and
classifies each by what the money moved to or from — the direct method, which is explainable line by line rather
than reconciled from net income.

## How it is worked out
1. **Cash accounts** are asset accounts with subtype `bank` or `cash`. Their movement in the period *is* the
   change in cash, so the statement can never disagree with the balance sheet.
2. For each posted entry that touches cash, the cash side is classified by the entry's **other** lines:
   - income, expense (including cost of sales) → **operating**
   - fixed assets and their accumulated depreciation → **investing**
   - equity, loans and other long-term liabilities → **financing**
   - other current assets and liabilities (receivables, payables, tax payable) → **operating**, because that is
     working capital
3. An entry whose other side spans several categories is split between them in proportion to those lines, on
   integer minor units, with any leftover cent going to the largest category — so the parts always sum to the
   cash movement exactly.
4. A transfer between two cash accounts nets to zero and is excluded rather than shown twice.

## Data contracts
`GET /api/v1/orgs/{orgId}/entities/{entityId}/reports/cash-flow?from=&to=` →
```
{from, to, currency, openingCash, operating, investing, financing, netChange, closingCash, unclassified, note,
 sections: {operating: [{accountId, code, name, amount}], investing: [...], financing: [...]}}
```
Each section lists the counterpart accounts that moved the cash, so every figure can be traced.

## Rules
- Only **posted** entries count; drafts are not cash.
- `openingCash + netChange` equals `closingCash`, and the sum of the three sections equals `netChange`. Both are
  checked in the tests, because a cash-flow statement that does not tie is worthless.
- Anything the rules above cannot place is reported in `unclassified` rather than quietly folded into operating.
- The note says this is the direct method from the ledger, and that a preparer may present it differently.

## Acceptance criteria
1. For the golden ledger fixture, opening cash plus the net change equals closing cash, and the three sections
   sum to the net change.
2. Customer receipts and supplier payments land in operating; buying equipment lands in investing; an owner's
   contribution or draw lands in financing.
3. A transfer between two bank accounts does not appear in any section.
4. An entry split across categories (paying a bill and buying a laptop in one entry) is split, and the parts sum
   to the cash movement to the cent.
5. Draft entries are excluded.
6. Each section lists its counterpart accounts with codes, so a figure can be traced back.
7. Another organization gets 404.

## Out of scope
The indirect method, cash-flow forecasting, restricted cash, and foreign currency.
