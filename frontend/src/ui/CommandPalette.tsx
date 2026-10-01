/*
  The jump-to palette: press Ctrl K (Cmd K on a Mac) inside an entity, type a page name, arrows + Enter to go.

  Where pages come from: every destination listed in src/ui/navigation.ts, plus the few addresses outside the
  entity (all entities, account, instance admin). Add a page to the sidebar — it appears here by itself, with
  its group heading searchable as a keyword. Nothing extra to do.

  The sidebar owns the open state and the search-styled button that opens it; this component owns everything
  else (the window-level shortcut, the filter, the keyboard travel). Styles are at the end of
  src/styles/components.css.
*/
import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icon } from './icons';
import type { IconName } from './icons';
import { ENTITY_NAV } from './navigation';

interface Destination {
  label: string;
  keywords: string;
  href: string;
  icon: IconName;
}

export function CommandPalette({
  orgId,
  entityId,
  open,
  onOpenChange,
}: {
  orgId: string;
  entityId: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const navigate = useNavigate();
  const inputRef = useRef<HTMLInputElement>(null);
  const [query, setQuery] = useState('');
  const [active, setActive] = useState(0);

  const destinations = useMemo<Destination[]>(() => {
    const base = `/orgs/${orgId}/entities/${entityId}`;
    return [
      { label: 'All entities', keywords: 'organization switch back', href: `/orgs/${orgId}`, icon: 'back' },
      ...ENTITY_NAV.flatMap((group) =>
        group.items.map((item) => ({
          label: item.label,
          keywords: group.heading,
          href: item.path ? `${base}/${item.path}` : base,
          icon: item.icon,
        })),
      ),
      { label: 'Account', keywords: 'me password sign in', href: '/account', icon: 'people' },
      { label: 'Instance', keywords: 'admin backup', href: '/instance', icon: 'server' },
    ];
  }, [orgId, entityId]);

  const shown = useMemo(() => {
    const needle = query.trim().toLowerCase();
    const matches = needle
      ? destinations.filter((d) => `${d.label} ${d.keywords}`.toLowerCase().includes(needle))
      : destinations;
    return matches.slice(0, 8);
  }, [destinations, query]);

  // Ctrl K toggles the palette; Escape closes it. Bound on window, so it works no matter where the focus is.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        onOpenChange(!open);
      } else if (open && event.key === 'Escape') {
        onOpenChange(false);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onOpenChange]);

  // Opening resets the search and puts the cursor straight into its field.
  useEffect(() => {
    if (open) {
      setQuery('');
      setActive(0);
      inputRef.current?.focus();
    }
  }, [open]);

  if (!open) {
    return null;
  }

  const go = (href: string) => {
    onOpenChange(false);
    navigate(href);
  };

  return (
    <div className="palette-backdrop" onClick={() => onOpenChange(false)}>
      <div
        className="palette"
        role="dialog"
        aria-modal="true"
        aria-label="Jump to"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="palette-search">
          <Icon name="search" size={18} />
          <input
            ref={inputRef}
            type="text"
            value={query}
            aria-label="Jump to page"
            placeholder="Jump to a page…"
            onChange={(event) => {
              setQuery(event.target.value);
              setActive(0);
            }}
            onKeyDown={(event) => {
              if (event.key === 'ArrowDown') {
                event.preventDefault();
                setActive((i) => Math.min(i + 1, shown.length - 1));
              } else if (event.key === 'ArrowUp') {
                event.preventDefault();
                setActive((i) => Math.max(i - 1, 0));
              } else if (event.key === 'Enter' && shown[active]) {
                go(shown[active].href);
              }
            }}
          />
          <kbd className="palette-kbd">Esc</kbd>
        </div>
        <ul role="listbox" aria-label="Pages">
          {shown.map((d, i) => (
            <li key={`${d.href}:${d.label}`}>
              <button
                type="button"
                role="option"
                aria-selected={i === active}
                className={i === active ? 'palette-item active' : 'palette-item'}
                onMouseEnter={() => setActive(i)}
                onClick={() => go(d.href)}
              >
                <Icon name={d.icon} />
                {d.label}
              </button>
            </li>
          ))}
          {shown.length === 0 && (
            <li className="palette-empty">Nothing named “{query}”. Try “bank”, “sales” or “reports”.</li>
          )}
        </ul>
      </div>
    </div>
  );
}
