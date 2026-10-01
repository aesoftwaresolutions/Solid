/*
  Small line icons, drawn on a 24×24 grid with a 1.75px stroke in the current text color.

  To add one: add a name and its SVG shapes to ICONS below. Shapes are plain SVG elements
  (<path d="..." />, <circle cx cy r />). Keep to straight lines and circles so every icon looks like one set.
  Use it with <Icon name="your-name" />.
*/
import type { ReactNode } from 'react';

const ICONS = {
  home: (
    <>
      <path d="M4 11 12 4l8 7" />
      <path d="M6 10v10h12V10" />
    </>
  ),
  search: (
    <>
      <circle cx="11" cy="11" r="6" />
      <path d="m15.5 15.5 4.5 4.5" />
    </>
  ),
  bank: (
    <>
      <path d="m3 9 9-5 9 5" />
      <path d="M5.5 10v8M10 10v8M14 10v8M18.5 10v8" />
      <path d="M3 20h18" />
    </>
  ),
  check: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="m8 12.5 2.5 2.5L16 9.5" />
    </>
  ),
  'arrow-in': (
    <>
      <path d="M12 4v11" />
      <path d="m7 10 5 5 5-5" />
      <path d="M5 20h14" />
    </>
  ),
  'arrow-out': (
    <>
      <path d="M12 16V5" />
      <path d="m7 10 5-5 5 5" />
      <path d="M5 20h14" />
    </>
  ),
  list: (
    <>
      <path d="M9 6h11M9 12h11M9 18h11" />
      <circle cx="5" cy="6" r="1" />
      <circle cx="5" cy="12" r="1" />
      <circle cx="5" cy="18" r="1" />
    </>
  ),
  book: (
    <>
      <path d="M5 4h10a4 4 0 0 1 4 4v12H9a4 4 0 0 1-4-4z" />
      <path d="M9 9h6" />
    </>
  ),
  file: (
    <>
      <path d="M7 3h7l5 5v13H7z" />
      <path d="M14 3v5h5" />
    </>
  ),
  box: (
    <>
      <path d="m4 8 8-4 8 4v8l-8 4-8-4z" />
      <path d="m4 8 8 4 8-4M12 12v8" />
    </>
  ),
  percent: (
    <>
      <path d="M6 18 18 6" />
      <circle cx="7" cy="7" r="2" />
      <circle cx="17" cy="17" r="2" />
    </>
  ),
  target: (
    <>
      <circle cx="12" cy="12" r="9" />
      <circle cx="12" cy="12" r="5" />
      <circle cx="12" cy="12" r="1" />
    </>
  ),
  chart: (
    <>
      <path d="M4 4v16h16" />
      <path d="M8 16v-4M12 16V8M16 16v-6" />
    </>
  ),
  upload: (
    <>
      <path d="M12 15V4" />
      <path d="m7 9 5-5 5 5" />
      <path d="M5 15v5h14v-5" />
    </>
  ),
  gear: (
    <>
      <circle cx="12" cy="12" r="3" />
      <path d="M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M5.6 18.4l2.1-2.1M16.3 7.7l2.1-2.1" />
    </>
  ),
  people: (
    <>
      <circle cx="12" cy="8" r="3.5" />
      <path d="M5.5 20c1.4-3.6 3.8-5.2 6.5-5.2s5.1 1.6 6.5 5.2" />
    </>
  ),
  server: (
    <>
      <rect x="4.5" y="4.5" width="15" height="6" rx="1.5" />
      <rect x="4.5" y="13" width="15" height="6" rx="1.5" />
      <path d="M8 7.5h.01M8 16h.01" />
    </>
  ),
  menu: <path d="M4 7h16M4 12h16M4 17h16" />,
  close: <path d="M6 6l12 12M18 6 6 18" />,
  back: <path d="m15 6-6 6 6 6" />,
} satisfies Record<string, ReactNode>;

export type IconName = keyof typeof ICONS;

export function Icon({ name, size = 18 }: { name: IconName; size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.75}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {ICONS[name]}
    </svg>
  );
}
