-- M16 Task 4 — Gateway runtime least-privilege identity (MySQL 8.4).
--
-- Replace the placeholders before applying:
--   __M16_DATABASE__         acceptance database (e.g. m16accept)
--   __M16_GATEWAY_USER__     Gateway runtime user (MUST differ from the
--                            Backend migration identity MYSQL_USER)
--   __M16_GATEWAY_PASSWORD__ strong random password (never the local default
--                            change-me-local-only; never commit the value)
--
-- Provenance (real MySQL 8.4 evidence, M16 acceptance):
-- - Gateway runtime ownership (annotation mappers under
--   gateway/.../persistence + routing/RoutingPolicyMapper):
--     SELECT (+ SELECT ... FOR UPDATE) everywhere it reads;
--     INSERT/UPDATE only on budget_reservation, gateway_request,
--     gateway_route_attempt, gateway_usage_fact, gateway_usage_dimension.
-- - FOR UPDATE tables: budget + billing_period (lock only, never UPDATE),
--   budget_reservation, gateway_request, gateway_route_attempt,
--   pricing_version (via the 3-table lockLineage join).
-- - `LOCK TABLES` is a MySQL-privilege prerequisite for ANY locking read
--   (SELECT ... FOR UPDATE / LOCK IN SHARE MODE) even without an explicit
--   LOCK TABLES statement; table-level GRANT LOCK TABLES is rejected by
--   MySQL (ERROR 1144), so the minimum grant is database-level LOCK TABLES.
--   It enables locking reads only: writes stay denied because the identity
--   holds no table-level INSERT/UPDATE/DELETE on financial-truth tables
--   (proven by ERROR 1142 denials), and Gateway ships no LOCK TABLES
--   statement (enforced by GatewayNoExplicitTableLockTest).
-- - DELETE / DDL / GRANT OPTION are never granted.
--
-- Apply as a DBA/root identity AFTER Backend Flyway migrations (V1..V23)
-- have created the schema:
--   mysql -h <host> -u root -p <db> < m16-gateway-grants.sql

CREATE USER IF NOT EXISTS '__M16_GATEWAY_USER__'@'%'
  IDENTIFIED BY '__M16_GATEWAY_PASSWORD__';

-- Operational-state reads everywhere (catalogs, identities, budgets,
-- periods, pricing, routing, credentials metadata).
GRANT SELECT ON `__M16_DATABASE__`.* TO '__M16_GATEWAY_USER__'@'%';

-- Locking-read prerequisite (see provenance note above). Enables
-- SELECT ... FOR UPDATE only; grants no write and no DDL.
GRANT LOCK TABLES ON `__M16_DATABASE__`.* TO '__M16_GATEWAY_USER__'@'%';

-- Gateway-owned runtime writes (allowlist derived from actual mapper SQL).
GRANT SELECT, INSERT, UPDATE ON `__M16_DATABASE__`.`budget_reservation`
  TO '__M16_GATEWAY_USER__'@'%';
GRANT SELECT, INSERT, UPDATE ON `__M16_DATABASE__`.`gateway_request`
  TO '__M16_GATEWAY_USER__'@'%';
GRANT SELECT, INSERT, UPDATE ON `__M16_DATABASE__`.`gateway_route_attempt`
  TO '__M16_GATEWAY_USER__'@'%';
GRANT SELECT, INSERT, UPDATE ON `__M16_DATABASE__`.`gateway_usage_fact`
  TO '__M16_GATEWAY_USER__'@'%';
GRANT SELECT, INSERT, UPDATE ON `__M16_DATABASE__`.`gateway_usage_dimension`
  TO '__M16_GATEWAY_USER__'@'%';
