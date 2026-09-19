# ADR-001: Modular monolith architecture

**Status:** Accepted

## Context

Synchros needs clear boundaries (catalog, inventory, reservation,
order, payment, events, notification, audit, analytics) but is a
single-team portfolio project with one deployment story.

## Decision

Build a **modular monolith**: one Spring Boot application, hard package
boundaries (`com.synchros.<module>`), dependencies pointing inward
(controller → service → repository), no service reaching into another
module's repository. Kafka is the only sanctioned cross-aggregate
reaction channel, and it never carries inventory ownership decisions.

## Consequences

- (+) One deployable, one transaction boundary for the reservation
  critical path — no distributed transactions ever needed.
- (+) Every "microservice benefit" (isolation, independent scaling later)
  is a refactor away: the module boundaries are the seams.
- (+) Explainable by one engineer in an interview.
- (−) Runs in one process; CPU-bound scaling means running the same JAR
  N times (which the stateless design allows).

We explicitly rejected 7 independently deployed services: the domain has
exactly one true consistency boundary (inventory), and splitting it
would force either distributed coordination or a coherence bug.
