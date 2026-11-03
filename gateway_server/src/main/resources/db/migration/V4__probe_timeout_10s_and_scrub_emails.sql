-- V4: Set default probe timeout to 10s (10000 ms) for all targets (prevents overlapping probes on 30s intervals)
--     and replace raw email addresses in request_logs.client_id / clients with hashed user identifiers.

ALTER TABLE monitored_targets ALTER COLUMN timeout_ms SET DEFAULT 10000;
UPDATE monitored_targets SET timeout_ms = 10000 WHERE timeout_ms <> 10000;

UPDATE request_logs
   SET client_id = 'user:' || substr(md5(lower(substr(client_id, 6))), 1, 16)
 WHERE client_id LIKE 'user:%@%';

DELETE FROM clients WHERE id LIKE 'user:%@%' OR label LIKE 'user:%@%';
