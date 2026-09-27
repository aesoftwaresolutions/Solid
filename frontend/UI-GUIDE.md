# Solid UI guide

How the screens are built, and exact steps for common changes. Written so that a person — or a small
local language model — can make a change by reading this file plus the one or two files it names,
without reading the whole project.

## 1. Where everything is

| I want to change…                        | Edit this file                  |
|------------------------------------------|---------------------------------|
| A color, size, shadow, or animation speed | `src/styles/tokens.css`         |
| How buttons, tables, inputs, links look   | `src/styles/base.css`           |
| Top bar, sidebar, mobile menu, page width | `src/styles/layout.css`         |
| Cards, number tiles, messages, loading    | `src/styles/components.css`     |
| An animation (entrance, shimmer, …)       | `src/styles/motion.css`         |
| Which links are in the sidebar            | `src/ui/navigation.ts`          |
| An icon                                   | `src/ui/icons.tsx`              |
| Which page shows at which address         | `src/App.tsx` (the `<Routes>`)  |
| Shared pieces (`Card`, `Loading`, `ErrorMessage`) | `src/components.tsx`    |
| One screen                                | `src/pages/<Name>Page.tsx`      |

`src/styles.css` only imports the five style files. Do not add rules to it.

## 2. Rules (always follow these)

1. **Never type a color, shadow, or duration outside `tokens.css`.** Write `var(--accent)`, not `#14532d`.
   If no token fits, add one to `tokens.css` — in the light block **and** the dark-mode block.
2. **No `style={{…}}` in pages** for colors, spacing, or fonts. Use a class from the list in §4.
3. **No new libraries for looks or motion** (no Tailwind, Bootstrap, Framer Motion, icon packs).
   Everything is plain CSS and small inline SVG icons.
4. **Animate only `opacity` and `transform`.** Never animate `width`, `height`, `top`, `left`, or `margin`.
5. **Every animation uses the timing tokens** (`--dur-fast`, `--dur-base`, `--dur-slow`, `--ease-out`,
   `--stagger`), so "reduce motion" keeps working. A new looping animation must also be switched off in the
   `prefers-reduced-motion` block at the bottom of `motion.css`.
6. **Don't rename existing class names or visible text** (headings, button labels, link text).
   Tests find elements by their text.
7. **Keep each file under about 300 lines.** If a page grows past that, move a section into its own
   component file next to it.
8. **Money is never a JavaScript number.** Show it with `formatMoney(...)` from `src/api.ts`, in a
   `<td className="money">` (or `<MoneyCell>`), never by doing maths on it.
9. **Finish every change with both checks** (§6). Both must pass.

## 3. Page template

Every page looks like this. Copy it when making a new one.

```tsx
import { useParams } from 'react-router-dom';
import { api } from '../api';
import { Card, ErrorMessage, Loading, useLoader } from '../components';

export default function ExamplePage() {
  const { orgId = '', entityId = '' } = useParams();
  const data = useLoader(() => api.someCall(orgId, entityId), [orgId, entityId]);

  return (
    <main>
      <h1>Example</h1>
      <Card title="Something">
        <ErrorMessage error={data.error} />
        {!data.value && !data.error && <Loading what="something" />}
        {data.value && <p>…show data.value here…</p>}
      </Card>
    </main>
  );
}
```

- One `<main>` per page, one `<h1>` inside it.
- Put content in `<Card>`s. Cards animate in by themselves, one after another — nothing to add.
- `<Loading what="…" />` shows the grey shimmering lines. `<ErrorMessage error={…} />` shows nothing
  when there is no error.

## 4. The classes you may use

| Class              | Put it on            | What it does                                    |
|--------------------|----------------------|-------------------------------------------------|
| `card`             | `<section>`/`<form>` | White panel (prefer the `<Card>` component)     |
| `stats`            | `<div>`              | A row of number tiles; wraps on small screens   |
| `stat`             | `<div>` inside `stats` | One tile; lifts on hover                      |
| `value`            | `<div>` inside `stat` | The big number                                 |
| `muted`            | any text             | Grey, secondary text                            |
| `money`            | `<td>`/`<th>`        | Right-aligned amount                            |
| `secondary`        | `<button>`           | Plain white button (default buttons are green)  |
| `notice`           | `<p>`                | Green "done" message; slides in                 |
| `error`            | `<p>`                | Red message; slides in and nudges (prefer `<ErrorMessage>`) |
| `visually-hidden`  | `<span>`/`<label>`   | Read by screen readers, not drawn               |
| `right`            | any                  | Right-aligned text                              |

Number tiles:

```tsx
<div className="stats">
  <div className="stat">
    <div className="muted">Income</div>
    <div className="value">{formatMoney(total)}</div>
  </div>
</div>
```

## 5. Recipes

### Change the brand color
In `src/styles/tokens.css` change `--accent`, `--accent-hover`, `--accent-soft`, and `--link` — in both the
light block at the top and the `prefers-color-scheme: dark` block. Nothing else.

### Add a page and a sidebar link
1. Create `src/pages/PayrollPage.tsx` from the template in §3.
2. In `src/App.tsx`, add the import at the top with the other pages, then add a route inside `<Routes>`:
   `<Route path="/orgs/:orgId/entities/:entityId/payroll" element={<PayrollPage />} />`
3. In `src/ui/navigation.ts`, add one line to the right group:
   `{ label: 'Payroll', path: 'payroll', icon: 'list' },`
The sidebar highlights it automatically when it is open.

### Add an icon
In `src/ui/icons.tsx`, add an entry to `ICONS` using only `<path d="…" />` and `<circle … />` on a 24×24
grid. Use straight lines and circles so it matches the others. Then use it by name.

### Add a new animation
1. In `src/styles/motion.css`, add a `@keyframes your-name { from { … } to { … } }` using only `opacity`
   and `transform`.
2. Below it, apply it: `.your-class { animation: your-name var(--dur-base) var(--ease-out) both; }`
3. If it repeats forever, add `.your-class` to the `prefers-reduced-motion` block at the bottom.

### Make everything faster or slower
Change `--dur-fast`, `--dur-base`, `--dur-slow`, or `--stagger` in `tokens.css`.

### Add a new kind of box or label
Add the class to `src/styles/components.css` with a one-line comment saying what it is for, then add it to
the list at the top of that file and to §4 above.

## 6. Checks (run both, from the `frontend` folder)

```
npm test
npm run build
```

`npm test` must end with every test passed. `npm run build` must end with `✓ built`. If a test fails
after a styling change, the most likely cause is changed visible text or a removed element — put the text
back rather than editing the test.

## 7. How the app is laid out

```
<header class="topbar">      brand · (menu button on phones) · email · Sign out
<div class="app-body">
  <aside class="sidebar">    only inside an entity; a slide-out drawer below 900px wide
  <div class="content">
    <main>                   one per page; its children rise in one after another
```

The sidebar is drawn by `src/ui/Sidebar.tsx` from the list in `src/ui/navigation.ts`. The top bar and
the drawer's open/close logic are in `Shell()` in `src/App.tsx`.
