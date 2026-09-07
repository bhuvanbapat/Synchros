import { useCallback, useEffect, useState } from 'react';
import {
  ApiError,
  adminMetrics,
  adminReconcile,
  cancelReservation,
  createOrder,
  createReservation,
  fetchInventory,
  getReservation,
  listEvents,
  listMyNotifications,
  login,
  payOrder,
  type Auth,
  type EventDto,
} from './api';
import type { AdminMetrics, ChargeResult, InventoryPool, NotificationItem, Order, Reservation } from './types';
import { HoldCountdown } from './HoldCountdown';

type View = 'shop' | 'admin';

const AUTH_STORAGE_KEY = 'flashreserve.auth';

/** sessionStorage (not localStorage): the token dies with the tab. */
function loadStoredAuth(): Auth | null {
  try {
    const raw = sessionStorage.getItem(AUTH_STORAGE_KEY);
    return raw ? (JSON.parse(raw) as Auth) : null;
  } catch {
    return null;
  }
}

export default function App() {
  const [auth, setAuthState] = useState<Auth | null>(loadStoredAuth());
  const [view, setView] = useState<View>('shop');

  function setAuth(a: Auth | null) {
    setAuthState(a);
    if (a) sessionStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify(a));
    else sessionStorage.removeItem(AUTH_STORAGE_KEY);
  }

  if (!auth) {
    return <Login onLogin={(a) => setAuth(a)} />;
  }

  function signOut() {
    setAuth(null);
    setView('shop');
  }

  return (
    <div className="app">
      <header className="topbar">
        <strong>⚡ FlashReserve</strong>
        <span className="spacer" />
        <span className="user">{auth.email}</span>
        <nav>
          <button onClick={() => setView('shop')} disabled={view === 'shop'}>
            Reserve
          </button>
          <button
            onClick={() => setView('admin')}
            disabled={view === 'admin'}
            hidden={!auth.email.startsWith('admin@')}
          >
            Admin
          </button>
          <button onClick={signOut} className="secondary">
            Sign out
          </button>
        </nav>
      </header>
      {view === 'shop' ? <Shop auth={auth} /> : <Admin auth={auth} />}
    </div>
  );
}

function Login({ onLogin }: { onLogin: (a: Auth) => void }) {
  const [email, setEmail] = useState('alice@example.com');
  const [password, setPassword] = useState('password');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      // Exchange credentials for a JWT (401 on bad creds).
      onLogin(await login(email, password));
    } catch (err) {
      setError(err instanceof ApiError ? `${err.code}: ${err.message}` : 'Network error');
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="login" onSubmit={submit}>
      <h1>⚡ FlashReserve</h1>
      <p className="subtitle">High-concurrency reservation demo</p>
      <label>
        Email <input value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="username" />
      </label>
      <label>
        Password{' '}
        <input
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          autoComplete="current-password"
        />
      </label>
      <button disabled={busy}>{busy ? 'Checking…' : 'Sign in'}</button>
      {error && <p className="error" role="alert">{error}</p>}
      <p className="hint">Demo users: alice@example.com / bob@example.com / admin@flashreserve.dev — password: password</p>
    </form>
  );
}

function Shop({ auth }: { auth: Auth }) {
  const [events, setEvents] = useState<EventDto[]>([]);
  const [pools, setPools] = useState<InventoryPool[]>([]);
  const [selected, setSelected] = useState<EventDto | null>(null);
  const [reservation, setReservation] = useState<Reservation | null>(null);
  const [order, setOrder] = useState<Order | null>(null);
  const [charge, setCharge] = useState<ChargeResult | null>(null);
  const [notifications, setNotifications] = useState<NotificationItem[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const refreshReservation = useCallback(async () => {
    if (!reservation) return;
    try {
      setReservation(await getReservation(auth, reservation.id));
    } catch {
      /* reservation may 404 for other users; ignore */
    }
  }, [auth, reservation]);

  useEffect(() => {
    listEvents(auth)
      .then((evs) => {
        setEvents(evs);
        if (evs.length > 0) setSelected(evs[0]);
      })
      .catch((e) => setError(String(e)));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (selected) {
      fetchInventory(auth, selected.id)
        .then(setPools)
        .catch((e) => setError(String(e)));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selected?.id]);

  async function reserve(section: string) {
    if (!selected) return;
    setBusy(true);
    setError(null);
    setOrder(null);
    setCharge(null);
    try {
      // Idempotency-Key per logical intent: one key per click-intent.
      const key = `ui-${selected.id}-${section}-${Date.now()}`;
      const r = await createReservation(auth, selected.id, section, 1, key);
      setReservation(r);
      listMyNotifications(auth).then(setNotifications).catch(() => {});
    } catch (e) {
      setError(e instanceof ApiError ? `${e.code}: ${e.message}` : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function checkout() {
    if (!reservation) return;
    setBusy(true);
    setError(null);
    try {
      const o = await createOrder(auth, reservation.id);
      setOrder(o);
      const result = await payOrder(auth, o.id);
      setCharge(result);
      // Backend state is authoritative — refetch both.
      setReservation(await getReservation(auth, reservation.id));
      listMyNotifications(auth).then(setNotifications).catch(() => {});
    } catch (e) {
      setError(e instanceof ApiError ? `${e.code}: ${e.message}` : String(e));
      // State may have changed (e.g. hold expired) — refetch.
      void refreshReservation();
    } finally {
      setBusy(false);
    }
  }

  async function cancel() {
    if (!reservation) return;
    setBusy(true);
    try {
      await cancelReservation(auth, reservation.id);
      setReservation(await getReservation(auth, reservation.id));
    } catch (e) {
      setError(e instanceof ApiError ? `${e.code}: ${e.message}` : String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="shop">
      <section>
        <h2>Events</h2>
        <ul className="event-list">
          {events.map((ev) => (
            <li
              key={ev.id}
              className={selected?.id === ev.id ? 'selected' : ''}
              onClick={() => setSelected(ev)}
            >
              <strong>{ev.name}</strong>
              <span className="muted">{new Date(ev.startsAt).toLocaleString()} · {ev.state}</span>
            </li>
          ))}
        </ul>
      </section>

      {selected && (
        <section>
          <h2>Inventory — {selected.name}</h2>
          <table className="inv-table">
            <thead>
              <tr>
                <th>Section</th>
                <th>Total</th>
                <th>Available</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {pools.map((p) => (
                <tr key={p.id} data-testid={`pool-${p.section}`}>
                  <td>{p.section}</td>
                  <td>{p.total}</td>
                  <td data-testid={`avail-${p.section}`}>{p.available}</td>
                  <td>
                    <button disabled={busy || p.available === 0} onClick={() => reserve(p.section)}>
                      {p.available === 0 ? 'Sold out' : 'Reserve 1'}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}

      {reservation && (
        <section className="hold-panel" data-testid="hold-panel">
          <h2>Your reservation</h2>
          <p>
            <code>{reservation.id.slice(0, 8)}…</code> · {reservation.section} ×{reservation.quantity} ·{' '}
            <HoldCountdown reservation={reservation} refetch={refreshReservation} />
          </p>
          {reservation.state === 'HELD' && (
            <p className="actions">
              <button disabled={busy} onClick={checkout}>
                Pay ${((order?.amountCents ?? 5000) / 100).toFixed(2)} (simulated)
              </button>
              <button disabled={busy} onClick={cancel} className="secondary">
                Cancel
              </button>
            </p>
          )}
          {charge && (
            <p data-testid={`charge-${charge.outcome}`} className={charge.outcome === 'SUCCESS' ? 'ok' : 'error'}>
              Payment {charge.outcome}: {charge.message}
            </p>
          )}
        </section>
      )}

      {error && (
        <p className="error" role="alert" data-testid="error">
          {error}
        </p>
      )}

      {notifications.length > 0 && (
        <section>
          <h2>Notifications</h2>
          <ul className="notif-list">
            {notifications.slice(0, 5).map((n) => (
              <li key={n.id} data-testid="notification">
                <strong>{n.kind}</strong> — {n.body}
              </li>
            ))}
          </ul>
        </section>
      )}
    </main>
  );
}

function Admin({ auth }: { auth: Auth }) {
  const [metrics, setMetrics] = useState<AdminMetrics | null>(null);
  const [recon, setRecon] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function load() {
    try {
      setMetrics(await adminMetrics(auth));
      setError(null);
    } catch (e) {
      setError(e instanceof ApiError ? `${e.code}: ${e.message}` : String(e));
    }
  }

  useEffect(() => {
    void load();
    const t = setInterval(load, 5000);
    return () => clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function reconcile() {
    try {
      const r = (await adminReconcile(auth)) as { poolsChecked: number; consistent: boolean; findings: unknown[] };
      setRecon(`pools=${r.poolsChecked} consistent=${r.consistent} findings=${r.findings.length}`);
    } catch (e) {
      setRecon(String(e));
    }
  }

  if (error) return <p className="error">{error}</p>;
  if (!metrics) return <p>Loading…</p>;

  return (
    <main className="admin">
      <h2>Operations</h2>
      <section>
        <h3>Inventory pools</h3>
        <table className="inv-table" data-testid="admin-pools">
          <thead>
            <tr>
              <th>Section</th>
              <th>Total</th>
              <th>Available</th>
              <th>Held</th>
              <th>Sold</th>
            </tr>
          </thead>
          <tbody>
            {metrics.pools.map((p) => (
              <tr key={p.id}>
                <td>{p.section}</td>
                <td>{p.total}</td>
                <td>{p.available}</td>
                <td>{p.held}</td>
                <td>{p.sold}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      <section className="admin-grid">
        <div>
          <h3>Reservations</h3>
          <ul>
            {Object.entries(metrics.reservations).map(([k, v]) => (
              <li key={k}>{k}: {v}</li>
            ))}
          </ul>
        </div>
        <div>
          <h3>Outbox</h3>
          <ul>
            {Object.entries(metrics.outbox).map(([k, v]) => (
              <li key={k}>{k}: {v}</li>
            ))}
          </ul>
          <p>Dead letters: <strong data-testid="deadletters">{metrics.deadLetters}</strong></p>
        </div>
      </section>

      <section>
        <button onClick={reconcile}>Run reconciliation</button>
        {recon && <p data-testid="recon-result">{recon}</p>}
      </section>
    </main>
  );
}
