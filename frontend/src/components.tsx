import { useEffect, useState, type ReactNode } from 'react';
import { ApiError } from './api';

export function ErrorMessage({ error }: { error: unknown }) {
  if (!error) {
    return null;
  }
  const message = error instanceof Error ? error.message : String(error);
  const code = error instanceof ApiError ? error.code : undefined;
  return (
    <p role="alert" className="error">
      {message}
      {code ? ` (${code})` : ''}
    </p>
  );
}

/**
 * Shown while something is on its way from the server: three shimmering grey lines where the content will be.
 * The words are still there for screen readers, just not drawn.
 */
export function Loading({ what }: { what: string }) {
  return (
    <div className="skeleton-group" role="status" aria-live="polite">
      <span className="visually-hidden">Loading {what}…</span>
      <div className="skeleton" />
      <div className="skeleton" />
      <div className="skeleton" />
    </div>
  );
}

/** Runs an async loader and renders one of loading / error / content. */
export function useLoader<T>(load: () => Promise<T>, deps: unknown[]) {
  const [value, setValue] = useState<T | undefined>(undefined);
  const [error, setError] = useState<unknown>(undefined);
  const [reloadCount, setReloadCount] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setError(undefined);
    load()
      .then((result) => {
        if (!cancelled) {
          setValue(result);
        }
      })
      .catch((e) => {
        if (!cancelled) {
          setError(e);
        }
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, reloadCount]);

  return { value, error, reload: () => setReloadCount((c) => c + 1), setValue };
}

export function Card({ title, children, actions }: { title: string; children: ReactNode; actions?: ReactNode }) {
  return (
    <section className="card">
      <header className="card-header">
        <h2>{title}</h2>
        {actions}
      </header>
      {children}
    </section>
  );
}

export function MoneyCell({ children }: { children: ReactNode }) {
  return <td className="money">{children}</td>;
}
