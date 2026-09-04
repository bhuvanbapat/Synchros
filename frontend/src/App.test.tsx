import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from './App';

// Mock the API module so tests exercise component behavior, not network.
vi.mock('./api', async () => {
  const actual = await vi.importActual<typeof import('./api')>('./api');
  return {
    ...actual,
    listEvents: vi.fn(),
    fetchInventory: vi.fn(),
    createReservation: vi.fn(),
    getReservation: vi.fn(),
    createOrder: vi.fn(),
    payOrder: vi.fn(),
    cancelReservation: vi.fn(),
    listMyNotifications: vi.fn(),
  };
});

import {
  listEvents,
  fetchInventory,
  createReservation,
  getReservation,
  createOrder,
  payOrder,
} from './api';

const mocked = {
  listEvents: vi.mocked(listEvents),
  fetchInventory: vi.mocked(fetchInventory),
  createReservation: vi.mocked(createReservation),
  getReservation: vi.mocked(getReservation),
  createOrder: vi.mocked(createOrder),
  payOrder: vi.mocked(payOrder),
};

const AUTH = { email: 'alice@example.com', password: 'password' };
const EVENT = {
  id: 'ev-1',
  name: 'Neon Pulse Live',
  description: null,
  startsAt: '2026-10-04T11:00:00Z',
  state: 'ON_SALE',
};
const POOLS = [
  { id: 1, publicId: 'p1', eventId: 1, section: 'FLOOR', total: 10, available: 10 },
  { id: 2, publicId: 'p2', eventId: 1, section: 'BALCONY', total: 500, available: 0 },
];
const RESERVATION = {
  id: 'res-1',
  eventId: 'ev-1',
  section: 'FLOOR',
  quantity: 1,
  state: 'HELD' as const,
  createdAt: new Date().toISOString(),
  holdExpiresAt: new Date(Date.now() + 120_000).toISOString(),
};

beforeEach(() => {
  vi.clearAllMocks();
  mocked.listEvents.mockResolvedValue([EVENT]);
  mocked.fetchInventory.mockResolvedValue(POOLS);
  mocked.createReservation.mockResolvedValue(RESERVATION);
  mocked.getReservation.mockResolvedValue(RESERVATION);
});

describe('App — inventory display', () => {
  it('shows events and per-section availability after login', async () => {
    mocked.listEvents.mockResolvedValue([EVENT]);
    render(<App />);
    await userEvent.clear(screen.getByLabelText(/email/i)); await userEvent.type(screen.getByLabelText(/email/i), AUTH.email);
    await userEvent.clear(screen.getByLabelText(/password/i)); await userEvent.type(screen.getByLabelText(/password/i), AUTH.password);
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));

    await waitFor(() => expect(screen.getByText('Neon Pulse Live')).toBeInTheDocument());
    await waitFor(() => expect(screen.getByTestId('avail-FLOOR')).toHaveTextContent('10'));
    // Sold-out section is labeled as such and disabled.
    expect(screen.getByText('Sold out')).toBeDisabled();
  });

  it('marks the sold-out section and keeps Reservable sections enabled', async () => {
    render(<App />);
    await userEvent.clear(screen.getByLabelText(/email/i)); await userEvent.type(screen.getByLabelText(/email/i), AUTH.email);
    await userEvent.clear(screen.getByLabelText(/password/i)); await userEvent.type(screen.getByLabelText(/password/i), AUTH.password);
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
    await waitFor(() => screen.getByTestId('pool-FLOOR'));
    expect(screen.getByText('Sold out')).toBeDisabled();
    expect(screen.getByRole('button', { name: /Reserve 1/i })).toBeEnabled();
  });
});

describe('App — reservation flow', () => {
  async function loginAndReserve() {
    render(<App />);
    await userEvent.clear(screen.getByLabelText(/email/i)); await userEvent.type(screen.getByLabelText(/email/i), AUTH.email);
    await userEvent.clear(screen.getByLabelText(/password/i)); await userEvent.type(screen.getByLabelText(/password/i), AUTH.password);
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
    await waitFor(() => screen.getByTestId('pool-FLOOR'));
    await userEvent.click(screen.getByRole('button', { name: /Reserve 1/i }));
    await waitFor(() => screen.getByTestId('hold-panel'));
  }

  it('creates a hold and shows the countdown with HELD state', async () => {
    await loginAndReserve();
    expect(mocked.createReservation).toHaveBeenCalledWith(
      AUTH, 'ev-1', 'FLOOR', 1, expect.stringMatching(/^ui-/),
    );
    expect(screen.getByText(/HELD|⏳/)).toBeInTheDocument();
  });

  it('shows INVENTORY_UNAVAILABLE error when the API rejects', async () => {
    const { ApiError } = await import('./api');
    mocked.createReservation.mockRejectedValueOnce(
      new ApiError('INVENTORY_UNAVAILABLE', 'Requested inventory is no longer available', 409),
    );
    render(<App />);
    await userEvent.clear(screen.getByLabelText(/email/i)); await userEvent.type(screen.getByLabelText(/email/i), AUTH.email);
    await userEvent.clear(screen.getByLabelText(/password/i)); await userEvent.type(screen.getByLabelText(/password/i), AUTH.password);
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
    await waitFor(() => screen.getByTestId('pool-FLOOR'));
    await userEvent.click(screen.getByRole('button', { name: /Reserve 1/i }));
    await waitFor(() => screen.getByTestId('error'));
    expect(screen.getByTestId('error').textContent).toMatch(/INVENTORY_UNAVAILABLE/);
  });

  it('completes checkout and shows the payment outcome', async () => {
    mocked.createOrder.mockResolvedValue({
      id: 'ord-1', reservationId: 'res-1', state: 'PENDING_PAYMENT' as const,
      amountCents: 5000, currency: 'USD', createdAt: new Date().toISOString(),
    });
    mocked.payOrder.mockResolvedValue({
      orderId: 'ord-1', orderState: 'CONFIRMED', providerRef: 'ref-1',
      outcome: 'SUCCESS' as const, message: 'Payment captured',
    });
    const confirmed = { ...RESERVATION, state: 'CONFIRMED' as const };
    mocked.getReservation.mockResolvedValueOnce(RESERVATION).mockResolvedValueOnce(confirmed);

    await loginAndReserve();
    await userEvent.click(screen.getByRole('button', { name: /Pay \$50/i }));
    await waitFor(() => screen.getByTestId('charge-SUCCESS'));
    expect(screen.getByTestId('charge-SUCCESS')).toHaveTextContent('Payment captured');
  });
});
