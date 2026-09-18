# 022 — Bank rules and receipts in the review queue

**Status:** Done · **Owner review:** Needed

## Goal
Bookkeeping is mostly the same handful of decisions repeated. Let a person teach Solid a rule once, categorize a
whole screenful at a time, and staple the receipt to the transaction while they are already looking at it.

## Scope
Frontend only — the API already has categorization rules (spec 008), bulk categorization and the document vault
(spec 016). The Bank page gains three things: a rules panel, a bulk "save all" action, and a receipt upload per row.

## Rules
- A rule is `contains` + account + priority; the list shows them in the order they are applied (lowest priority
  number first) so it is obvious which one wins.
- Saving every reviewed row uses the bulk endpoint in one request, and only sends rows where a category is chosen.
- Uploading a receipt on a row sends it to the vault with kind `receipt` and immediately links it to that
  `bank_transaction`, so the paperwork is attached before the row disappears from the queue.
- A row that already has a document linked says so, with a link to open the file.
- Failures keep the row's chosen category on screen and show the server's message.

## Acceptance criteria
1. The rules panel lists existing rules with their pattern, account and priority, sorted by priority.
2. A new rule is created with the typed pattern and chosen account, and the list refreshes.
3. Deleting a rule calls the delete endpoint and refreshes the list.
4. "Save all reviewed" posts one bulk request containing only the rows with a chosen category.
5. Uploading a receipt on a row uploads it with kind `receipt` and then links it to that bank transaction.
6. A row whose transaction already has a linked document shows a link to open it.
7. Any failure shows the server's message and leaves the queue usable.

## Out of scope
Editing an existing rule (delete and re-create), regular-expression rules, amount-based rules, drag-and-drop
uploads, and previewing the receipt inline.
