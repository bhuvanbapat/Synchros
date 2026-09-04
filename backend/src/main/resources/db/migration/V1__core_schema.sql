-- FlashReserve V1: core schema
-- Conventions: timestamptz everywhere, text for enums (validated by CHECK),
-- generated identity BIGINT PKs with UUID public identifiers where exposed.

-- ============ USERS ============
CREATE TABLE fr_user (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id   UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    email       TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    role        TEXT NOT NULL DEFAULT 'USER' CHECK (role IN ('USER','ADMIN')),
    account_state TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (account_state IN ('ACTIVE','SUSPENDED')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============ CATALOG: venue / event / inventory ============
CREATE TABLE venue (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE fr_event (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id   UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    venue_id    BIGINT NOT NULL REFERENCES venue(id),
    name        TEXT NOT NULL,
    description TEXT,
    starts_at   TIMESTAMPTZ NOT NULL,
    on_sale_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    state       TEXT NOT NULL DEFAULT 'SCHEDULED'
                CHECK (state IN ('SCHEDULED','ON_SALE','SOLD_OUT','CONCLUDED','CANCELLED')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_fr_event_starts_at ON fr_event (starts_at);
CREATE INDEX idx_fr_event_state ON fr_event (state);

-- Inventory item: one reservable unit (e.g. a seat or a general-admission unit).
-- THE AUTHORITATIVE consistency boundary for oversell prevention.
CREATE TABLE inventory_item (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id   UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    event_id    BIGINT NOT NULL REFERENCES fr_event(id),
    section     TEXT NOT NULL,
    identifier  TEXT NOT NULL,          -- seat label or GA token
    state       TEXT NOT NULL DEFAULT 'AVAILABLE'
                CHECK (state IN ('AVAILABLE','HELD','SOLD','RELEASED')),
    version     BIGINT NOT NULL DEFAULT 0,   -- optimistic locking
    held_until  TIMESTAMPTZ,           -- set when HELD
    held_by_reservation BIGINT,        -- FK added after reservation table
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (event_id, section, identifier)
);
-- hot path: find available items for an event
CREATE INDEX idx_inventory_event_state ON inventory_item (event_id, state);
-- expiration job scan
CREATE INDEX idx_inventory_held_until ON inventory_item (held_until) WHERE state = 'HELD';

-- GA-style pooled inventory (counter + per-unit rows both supported; counters are
-- kept in a separate table so the conditional UPDATE is a single-row operation).
CREATE TABLE inventory_pool (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id   UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    event_id    BIGINT NOT NULL REFERENCES fr_event(id),
    section     TEXT NOT NULL,
    total       INT NOT NULL CHECK (total >= 0),
    available   INT NOT NULL CHECK (available >= 0 AND available <= total),
    version     BIGINT NOT NULL DEFAULT 0,
    UNIQUE (event_id, section)
);

-- ============ RESERVATIONS ============
CREATE TABLE reservation (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id   UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    user_id     BIGINT NOT NULL REFERENCES fr_user(id),
    event_id    BIGINT NOT NULL REFERENCES fr_event(id),
    -- single-item reservations in V1; items table allows future multi-item carts
    state       TEXT NOT NULL DEFAULT 'HELD'
                CHECK (state IN ('HELD','CONFIRMED','EXPIRED','CANCELLED','FAILED')),
    quantity    INT NOT NULL CHECK (quantity > 0),
    section     TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    hold_expires_at TIMESTAMPTZ NOT NULL,
    confirmed_at    TIMESTAMPTZ,
    expired_at      TIMESTAMPTZ,
    cancelled_at    TIMESTAMPTZ,
    version     BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_reservation_user ON reservation (user_id, created_at DESC);
CREATE INDEX idx_reservation_expire_scan ON reservation (hold_expires_at)
    WHERE state = 'HELD';
CREATE INDEX idx_reservation_event ON reservation (event_id);

-- link inventory to reservation (which unit the hold owns)
ALTER TABLE inventory_item
    ADD CONSTRAINT fk_inventory_hold_reservation
    FOREIGN KEY (held_by_reservation) REFERENCES reservation(id);

-- ============ ORDERS / PAYMENTS ============
CREATE TABLE fr_order (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id     UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    user_id       BIGINT NOT NULL REFERENCES fr_user(id),
    reservation_id BIGINT NOT NULL UNIQUE REFERENCES reservation(id),
    state         TEXT NOT NULL DEFAULT 'PENDING_PAYMENT'
                    CHECK (state IN ('PENDING_PAYMENT','CONFIRMED','FAILED','CANCELLED','EXPIRED')),
    amount_cents  INT NOT NULL CHECK (amount_cents >= 0),
    currency      TEXT NOT NULL DEFAULT 'USD',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmed_at  TIMESTAMPTZ,
    failed_at     TIMESTAMPTZ,
    version       BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_order_user ON fr_order (user_id, created_at DESC);

CREATE TABLE payment (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id     UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    order_id      BIGINT NOT NULL REFERENCES fr_order(id),
    state         TEXT NOT NULL DEFAULT 'INITIATED'
                    CHECK (state IN ('INITIATED','SUCCEEDED','FAILED','TIMED_OUT')),
    amount_cents  INT NOT NULL CHECK (amount_cents > 0),
    provider_ref  TEXT,                       -- mock gateway reference
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at  TIMESTAMPTZ
);
CREATE INDEX idx_payment_order ON payment (order_id);

CREATE TABLE payment_attempt (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id    BIGINT NOT NULL REFERENCES payment(id),
    outcome       TEXT NOT NULL
                    CHECK (outcome IN ('SUCCESS','FAILURE','TIMEOUT','DUPLICATE_CALLBACK')),
    provider_ref  TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_attempt_payment ON payment_attempt (payment_id);

-- ============ IDEMPOTENCY ============
-- Scope: (user, operation, key). Stores request fingerprint + cached response.
CREATE TABLE idempotency_key (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    idem_key      TEXT NOT NULL,
    user_id       BIGINT NOT NULL REFERENCES fr_user(id),
    operation     TEXT NOT NULL,               -- e.g. CREATE_RESERVATION
    request_hash  TEXT NOT NULL,               -- SHA-256 of canonical request body
    response_status INT,
    response_body  JSONB,
    state         TEXT NOT NULL DEFAULT 'IN_FLIGHT'
                    CHECK (state IN ('IN_FLIGHT','COMPLETED')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL,
    UNIQUE (user_id, operation, idem_key)
);
CREATE INDEX idx_idem_expiry ON idempotency_key (expires_at);

-- ============ OUTBOX ============
CREATE TABLE outbox_event (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id      UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    event_type    TEXT NOT NULL,
    aggregate_type TEXT NOT NULL,
    aggregate_id  TEXT NOT NULL,
    payload       JSONB NOT NULL,
    state         TEXT NOT NULL DEFAULT 'PENDING'
                    CHECK (state IN ('PENDING','PUBLISHED','FAILED','DEAD')),
    retry_count   INT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ
);
-- publisher polls pending (or retryable failed) in order
CREATE INDEX idx_outbox_pending ON outbox_event (created_at)
    WHERE state IN ('PENDING','FAILED');

-- ============ EVENT DEDUP (consumer-side) ============
CREATE TABLE processed_event (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id      UUID NOT NULL,
    consumer_group TEXT NOT NULL,
    processed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (event_id, consumer_group)
);

-- ============ DEAD LETTER ============
CREATE TABLE dead_letter (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id      UUID,
    event_type    TEXT,
    topic         TEXT,
    error         TEXT NOT NULL,
    payload       JSONB,
    attempt_count INT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============ AUDIT ============
CREATE TABLE audit_event (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor         TEXT NOT NULL,              -- 'user:123' | 'system:expiration' | 'admin:1'
    operation     TEXT NOT NULL,
    entity_type   TEXT NOT NULL,
    entity_id     TEXT NOT NULL,
    result        TEXT NOT NULL DEFAULT 'SUCCESS' CHECK (result IN ('SUCCESS','FAILURE')),
    request_id    TEXT,
    details       JSONB,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_entity ON audit_event (entity_type, entity_id, created_at DESC);
CREATE INDEX idx_audit_created ON audit_event (created_at DESC);

-- ============ NOTIFICATIONS ============
CREATE TABLE notification (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id     UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    user_id       BIGINT NOT NULL REFERENCES fr_user(id),
    kind          TEXT NOT NULL,
    body          TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_notification_user ON notification (user_id, created_at DESC);
