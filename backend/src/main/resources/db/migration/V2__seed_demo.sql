-- V2: seed demo data (venue, admin + demo users, one flash-sale event, inventory)
-- Passwords are bcrypt hashes of 'password' (demo only, never production).
-- Hash generated with Spring Security BCryptPasswordEncoder (strength 10).
INSERT INTO venue (name) VALUES ('Neon Dome Arena');

INSERT INTO fr_user (email, password_hash, role) VALUES
  ('admin@synchros.dev', '$2a$10$/xjLsBfOKcKAT2kSbuoA0e45YUjHpIhXvHBqTzatkR2IOqFvVkM8a', 'ADMIN'),
  ('alice@example.com',      '$2a$10$/xjLsBfOKcKAT2kSbuoA0e45YUjHpIhXvHBqTzatkR2IOqFvVkM8a', 'USER'),
  ('bob@example.com',        '$2a$10$/xjLsBfOKcKAT2kSbuoA0e45YUjHpIhXvHBqTzatkR2IOqFvVkM8a', 'USER');

-- Flash-sale event: 10 GA units in FLOOR section, 500 in BALCONY (pooled)
INSERT INTO fr_event (venue_id, name, description, starts_at, state) VALUES
  (1, 'Neon Pulse Live', 'High-demand launch concert — flash-sale demo',
   now() + interval '30 days', 'ON_SALE');

-- Pooled inventory for the two sections
INSERT INTO inventory_pool (event_id, section, total, available) VALUES
  (1, 'FLOOR', 10, 10),
  (1, 'BALCONY', 500, 500);

-- Also create discrete seat rows for FLOOR (1..10) to demo unit-level holds
INSERT INTO inventory_item (event_id, section, identifier)
SELECT 1, 'FLOOR', 'FL-' || lp
FROM generate_series(1, 10) AS lp;
