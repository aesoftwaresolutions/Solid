# 062 — Forms that line up

**Status:** Done · **Owner review:** Needed · **CPA review:** Not needed

## Goal
The owner installed Solid, reached the new first-account screen, and found the input boxes butted up against
the words naming them — "Password" running into its own field. Every form in the app had it, because every
form uses the same markup: a `<label>` with the control inside it.

An input is inline by default, so it ran on immediately after the label's text, with nothing between them,
and each field started wherever its label happened to end. Three fields, three different starting points, and
the longest label touching its box.

## Scope
`styles.css` — so the fix lands on every form at once rather than one screen at a time — plus the last rough
edges on the first-account screen.

## What changes
- The control goes on its own line under the words that name it, at a consistent width. Fields now share a
  left edge and a size whatever their labels say.
- A tick box is the exception: it belongs *beside* its words, so checkboxes and radios keep their place and
  are not stretched to field width.
- `textarea` gets the same border, padding and font as every other control — it had none, so the address and
  payment-instruction boxes on the letterhead looked foreign.
- A heading inside a card gets space under it; the two-factor screen had its field stuck to the heading.
- Signing in and setting up are read before they are filled in, so those screens get a narrower column
  (560px) instead of the full 960.
- Creating the first account marks the instance claimed before signing in, not after. If the account is made
  but the sign-in that follows fails, the screen must not still be offering to create it.

## Acceptance criteria
1. On the first-account screen the three fields share a left edge and width, with a gap between each label
   and its field.
2. Checkboxes still sit beside their text and are not stretched.
3. Textareas match the other controls.
4. The existing tests pass untouched — they find fields by label, which is what the markup still says.

## Out of scope
A design system, spacing scale, or dark mode. This is the layout bug and its immediate neighbours.
