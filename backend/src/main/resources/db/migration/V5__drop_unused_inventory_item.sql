-- V5: remove the unit-level inventory_item table.
-- It was scaffolded for a future seat-accurate domain but no code path ever
-- read or wrote it (pooled inventory_pool + reservation state machine cover
-- the entire V1 flash-sale GA model). Dead schema = dead code; dropped.
-- If a seat-accurate domain is added later, it gets a fresh migration with
-- the per-unit hold semantics designed against the actual requirements.
DROP TABLE IF EXISTS inventory_item;
