# ADR-002: PostgreSQL as the authoritative inventory store

**Status:** Accepted

## Context

The core invariant — *never sell more units than exist* — must hold under
arbitrary concurrency and arbitrary component failures.

## Decision

PostgreSQL is the **only** source of truth for inventory ownership.
Every hold/confirm/release is a committed database transaction. The
atomic mutation is a single-statement conditional UPDATE; a CHECK
constraint (`0 <= available <= total`) is the final backstop, making
negative inventory unrepresentable at the storage layer.

## Consequences

- (+) One authority ⇒ no coherence protocol, no "which copy won" logic.
- (+) Constraints, unique keys, and atomic CAS are exactly the primitives
  this problem needs; no custom lock service to build or babysit.
- (+) Any crash leaves either a fully-committed hold or none — no
  in-between state can survive.
- (−) The hot row serializes contention: throughput ceiling = sequential
  updates on that row. Accepted: correctness first; scaling strategies
  (sharded pools, admission control) are documented in
  docs/INTERVIEW_GUIDE.md without changing the invariant.

Redis was explicitly rejected for this role (see ADR-004): a cache
losing edits on failover is a *lost update* — the original oversell bug
in new clothing.
