-- V7: publisher claim lease for multi-instance safety.
-- With SKIP LOCKED alone, a publisher that dies mid-send (after the row
-- lock released, before the PUBLISHED flip commits) would leave its batch
-- PENDING for the next poll — fine (consumers dedup), but a slow publisher
-- instance could also re-fetch rows another instance is actively sending.
-- The lease makes claims explicit: a publisher sets leased_until when it
-- picks a batch; other instances skip leased rows until the lease lapses.
-- A crashed publisher's lease simply expires (default 60s) and the row
-- returns to the publishable pool. Correctness never depended on the lease
-- (consumer dedup remains the final guarantee); the lease removes wasted
-- duplicate sends between replicas.
ALTER TABLE outbox_event ADD COLUMN leased_until TIMESTAMPTZ;
CREATE INDEX idx_outbox_publishable ON outbox_event (created_at)
    WHERE state = 'PENDING' AND leased_until IS NULL;
