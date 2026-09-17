import { useEffect, useState } from 'react';
import { fetchSystemInfo, type SystemInfo } from './api';

type State =
  | { kind: 'loading' }
  | { kind: 'ready'; info: SystemInfo }
  | { kind: 'error' };

export default function App() {
  const [state, setState] = useState<State>({ kind: 'loading' });

  useEffect(() => {
    fetchSystemInfo()
      .then((info) => setState({ kind: 'ready', info }))
      .catch(() => setState({ kind: 'error' }));
  }, []);

  return (
    <main style={{ fontFamily: 'system-ui, sans-serif', maxWidth: 640, margin: '48px auto', padding: '0 16px' }}>
      <h1>Solid</h1>
      <p>Business &amp; personal accounting with US tax.</p>
      {state.kind === 'loading' && <p>Connecting to server…</p>}
      {state.kind === 'ready' && (
        <p>
          Server version <strong>{state.info.version}</strong>, database schema{' '}
          <strong>{state.info.databaseSchemaVersion}</strong>
        </p>
      )}
      {state.kind === 'error' && <p role="alert">Can't reach the Solid server. Is the backend running?</p>}
    </main>
  );
}
