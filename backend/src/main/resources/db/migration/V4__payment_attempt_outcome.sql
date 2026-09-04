-- V4: extend payment_attempt outcome vocabulary with LATE_CALLBACK —
-- the gateway outcome arriving after the order left PENDING_PAYMENT
-- (hold TTL lapsed first). Distinct from DUPLICATE_CALLBACK because the
-- charge was never applied at all and a real deployment would refund.
ALTER TABLE payment_attempt DROP CONSTRAINT payment_attempt_outcome_check;
ALTER TABLE payment_attempt ADD CONSTRAINT payment_attempt_outcome_check
    CHECK (outcome IN ('SUCCESS','FAILURE','TIMEOUT','DUPLICATE_CALLBACK','LATE_CALLBACK'));
