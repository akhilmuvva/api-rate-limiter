-- V4: Set default probe timeout to 10s (10000 ms) for all targets (prevents overlapping probes on 30s intervals)
--     and replace old raw email addresses in request_logs, bans, clients with 'user:legacy' / sanitized text.

ALTER TABLE monitored_targets ALTER COLUMN timeout_ms SET DEFAULT 10000;
UPDATE monitored_targets SET timeout_ms = 10000 WHERE timeout_ms <> 10000;

UPDATE request_logs
   SET client_id = 'user:legacy'
 WHERE client_id LIKE 'user:%@%' OR client_id LIKE '%@%';

UPDATE bans
   SET client_key = 'user:legacy'
 WHERE client_key LIKE 'user:%@%' OR client_key LIKE '%@%';

DELETE FROM clients WHERE id LIKE 'user:%@%' OR label LIKE 'user:%@%' OR id LIKE '%@%';
