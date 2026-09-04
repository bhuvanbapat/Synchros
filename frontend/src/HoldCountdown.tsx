import { useEffect, useMemo, useState } from 'react';
import type { Reservation } from './types';

/**
 * Countdown driven by the BACKEND's holdExpiresAt (authoritative). When the
 * local timer hits zero the component does NOT assume expiry — it refetches
 * the reservation and renders whatever the server says (per §54).
 */
export function HoldCountdown({
  reservation,
  onMaybeExpired,
  refetch,
}: {
  reservation: Reservation;
  onMaybeExpired?: () => void;
  refetch: () => Promise<unknown>;
}) {
  const [now, setNow] = useState(() => Date.now());

  const deadline = useMemo(
    () => new Date(reservation.holdExpiresAt).getTime(),
    [reservation.holdExpiresAt],
  );

  useEffect(() => {
    if (reservation.state !== 'HELD') return;
    const t = setInterval(() => setNow(Date.now()), 500);
    return () => clearInterval(t);
  }, [reservation.state]);

  const remaining = Math.max(0, deadline - now);

  useEffect(() => {
    if (reservation.state === 'HELD' && remaining === 0) {
      // Timer hit zero: the SERVER decides. Refetch and report.
      void refetch().then(() => onMaybeExpired?.());
    }
  }, [remaining, reservation.state, refetch, onMaybeExpired]);

  if (reservation.state !== 'HELD') {
    return <span className={`badge badge-${reservation.state}`}>{reservation.state}</span>;
  }

  const totalSec = Math.floor(remaining / 1000);
  const mm = String(Math.floor(totalSec / 60)).padStart(2, '0');
  const ss = String(totalSec % 60).padStart(2, '0');
  const danger = remaining < 30_000;

  return (
    <span className={`hold-timer ${danger ? 'hold-timer-danger' : ''}`} aria-live="polite">
      ⏳ {mm}:{ss}
    </span>
  );
}
