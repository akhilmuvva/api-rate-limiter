# NT14 API Rate Limiter & Optimizer Platform

A complete, end-to-end API rate limiting, durable PostgreSQL telemetry, and synthetic API monitoring platform comprising:
1. **Kotlin/Ktor Gateway Backend (`:gateway_server`)**: Production-grade rate limiter implementing Token Bucket + Sliding Window algorithms, HikariCP + Flyway PostgreSQL persistence, bounded asynchronous batch logging, pooled HTTP/2 upstream proxy client, synthetic upstream probing (`TargetMonitor`), and RBAC-protected REST + WebSocket APIs.
2. **Android Jetpack Compose Client (`:app`)**: Live Dashboard, Upstream Monitoring tab with latency sparklines, keyset-paginated Logs (`real` vs `demo` filtering), aggregated Reports, merged `Rules` tab (Endpoints + Rate Limits), and masked client ban management (`iphash:<hmac>`).

---

## Durable PostgreSQL Schema (`V1__init.sql` – `V4__probe_timeout_10s_and_scrub_emails.sql`)

Migrations run automatically on startup via Flyway (`gateway_server/src/main/resources/db/migration/`) whenever `DATABASE_URL` is set:

| Table | Primary Key | Purpose & Retention |
|---|---|---|
| `request_logs` | `id BIGINT` (`ms << 16 \| seq`) | Every proxied/demo decision (`ts`, `client_id` masked IP or hashed user key `user:<16-hex>`, `client_ip_hash` HMAC-SHA256, `method`, `path`, `status`, `latency_ms`, `limiter_ms`, `upstream_ms` null on 429, `total_ms`, `decision`, `source` `real\|demo`, `upstream`). **Retention: 7 days**. |
| `metrics_1m` | `(minute, endpoint, source)` | Per-minute rollups (`requests`, `allowed`, `throttled`, `errors` [5xx only], `p50`, `p95`, `latency_sum_ms`, `limiter_p50`, `limiter_p95`, `upstream_p50`, `upstream_p95`, `total_p50`, `total_p95`). **Retention: 90 days**. |
| `clients` | `id TEXT` | Per-client lifetime counters (`label` masked IP or hashed user key `user:<16-hex>`, `source`, `first_seen`, `last_seen`, `total_requests`, `throttled_count`). |
| `bans` | `client_key TEXT` | Active IP/client bans (`iphash:<hmac>` or `user:<16-hex>`, `reason`, `created_at`, `expires_at`, `created_by`), restored on gateway startup. |
| `rules` | `endpoint_id TEXT` | Dynamic rate-limit rules (`limit_per_min`, `burst_limit`, `action`, `updated_at`), restored on startup. |
| `incidents` | `id TEXT` | Security & target-down incidents (`ts`, `type`, `severity`, `detail`, `target_id` `ON DELETE SET NULL`, `resolved_at`). Auto-resolved when a target recovers or is deleted, or via `PATCH /api/incidents/{id}/resolve`. |
| `monitored_targets` | `id BIGSERIAL` | Synthetic probe targets (`name`, `url`, `method`, `expected_status`, `interval_sec`, `timeout_ms` default `10000`, `enabled`, `source`, `synthetic`). Seeded with `https://polylance-fv-1-45wy.onrender.com/health` and `https://polylance-fv-1-45wy.onrender.com/api/jobs`. Synthetic test targets (`synthetic = true`) are hidden from `GET /api/targets/status` by default unless `?includeSynthetic=true`. |
| `probe_results` | `id BIGSERIAL` | Probe outcomes (`target_id`, `ts`, `up`, `status_code`, `latency_ms`, `error`, `transition` `up->down\|down->up`). **Retention: 30 days**. |

### Asynchronous Batch Writer, Privacy & Fast-Retry Probe Scheduling
- **Bounded Queue (`RequestLogWriter`)**: The request hot path only calls non-blocking `ArrayBlockingQueue.offer` (capacity `10,000`). A background coroutine coalesces bursts and flushes up to `1,000` entries per JDBC batch using `reWriteBatchedInserts=true`. If the queue ever fills, entries are dropped and counted (`PersistenceStatus.dropped`) so the request hot path never blocks.
- **IP & Identity Privacy (`IpPrivacy`)**: Raw client IPs and raw user email addresses are never stored in `request_logs` or `clients`, nor logged. `client_id` stores either a keyed HMAC user ID (`user:<16-hex>`) or a masked IP (`ip:203.0.113.x`), and `client_ip_hash` stores `HMAC-SHA256(ip, IP_HASH_SALT)` (`iphash:<24-hex>`) for correlation and banning. `IP_HASH_SALT` is required on startup with no default fallback in code.
- **Synthetic Probe Fast-Retry (`TargetMonitor`)**: Healthy targets poll on their configured `interval_sec` (default `30s`) with a `10s` (`10,000 ms`) timeout so slow probes never overlap the next interval. When a target is registered or invalidated (`nextDueAt = 0`), Probe #1 runs immediately (`t ≈ 0s`). Whenever a probe fails (`up == false`), `TargetMonitor` schedules the next retry at `min(intervalSec, failRetrySec)` where `failRetrySec = 10s` (`st.nextDueAt = now + 1000L * (if (up) t.intervalSec else minOf(t.intervalSec, failRetrySec))`). Consequently, a failing `30s`-interval target executes 3 consecutive failed probes at `t ≈ 0s`, `t ≈ 10s`, and `t ≈ 20s`, opening a `CRITICAL` `"Target Down"` incident (`failThreshold = 3`) in ~`21–23s` (well under the `90s` SLA) while keeping steady-state healthy polling at `30s`.

---

## Environment Variables (`:gateway_server`)

| Variable | Required | Description |
|---|---|---|
| `DATABASE_URL` | Production | PostgreSQL connection URL (`postgres://user:pass@host:5432/db` or `jdbc:postgresql://...`). Automatically adds `sslmode=require` for `*.render.com` hosts. |
| `IP_HASH_SALT` | **Yes (Fail-Fast)** | Secret salt for `HMAC-SHA256` client IP hashing. Read directly from env with **no default fallback**; gateway fails fast on startup if missing or blank. |
| `DEMO_TRAFFIC` | Optional (`false`) | Enables synthetic background traffic generator when `true`. Keep `false` in production so real metrics are unpolluted. |
| `JWT_SECRET` | Yes | Signing secret for `VIEWER` / `ADMIN` JWTs and WebSocket pairing tickets. |
| `ADMIN_EMAILS` | Optional | Comma-separated emails granted `ADMIN` role on Google Sign-In (`akpolylance@gmail.com`). |
| `GOOGLE_CLIENT_ID` | Yes | OAuth 2.0 Web Client ID used to verify Google ID tokens on `POST /api/auth/google`. |
| `POLYLANCE_BASE_URL` | Optional | Upstream base URL (defaults to `https://polylance-fv-1-45wy.onrender.com`). |
| `TRUSTED_PROXIES` | Optional | Comma-separated proxy IPs/CIDRs allowed to supply `X-Forwarded-For`. |

---

## How to View & Verify Data

### 1. Inspect Database via `dbQuery` or `psql`
Using the built-in Gradle SQL runner (no `psql` install required):
```powershell
$env:JAVA_HOME = "d:\Android\.jdk17"
$env:DATABASE_URL = "postgres://user:pass@host:5432/db"
.\gradlew.bat :gateway_server:dbQuery
```
Or run a custom SQL query:
```powershell
.\gradlew.bat :gateway_server:dbQuery -Psql="SELECT id, ts, client_id, client_ip_hash, path, status, limiter_ms, upstream_ms, total_ms, decision, source FROM request_logs ORDER BY id DESC LIMIT 10"
```
Or using `psql` directly with the Render External Database URL:
```bash
psql "postgres://user:pass@dpg-xxxx.oregon-postgres.render.com/nt14_gateway?sslmode=require" \
  -c "SELECT id, ts, status, limiter_ms, upstream_ms, total_ms, decision FROM request_logs ORDER BY id DESC LIMIT 10;"
```

### 2. Query REST Endpoints
```powershell
# 1. Lightweight health check (no DB query, ideal for external 5-minute pingers)
curl.exe -s "https://api-rate-limiter-gateway-qgfy.onrender.com/health-light"

# 2. Full health & persistence status (includes errorRate, throttleRate, limiter/upstream/total percentiles)
curl.exe -s "https://api-rate-limiter-gateway-qgfy.onrender.com/health"

# 3. Keyset-paginated logs (filterable by source=real|demo, status=200|429|5xx, client, from, to)
curl.exe -s -H "Authorization: Bearer $TOKEN" "https://api-rate-limiter-gateway-qgfy.onrender.com/api/logs?limit=20&source=real"

# 4. Time-series rollups from metrics_1m (range=1h|24h|7d)
curl.exe -s -H "Authorization: Bearer $TOKEN" "https://api-rate-limiter-gateway-qgfy.onrender.com/api/metrics?range=1h&source=real"

# 5. Aggregated traffic report from PostgreSQL
curl.exe -s -H "Authorization: Bearer $TOKEN" "https://api-rate-limiter-gateway-qgfy.onrender.com/api/reports?range=24h&source=real"

# 6. Monitored upstream targets & latency sparklines (excludes synthetic test targets by default)
curl.exe -s -H "Authorization: Bearer $TOKEN" "https://api-rate-limiter-gateway-qgfy.onrender.com/api/targets/status"

# 7. Resolve an incident (ADMIN)
curl.exe -s -X PATCH -H "Authorization: Bearer $ADMIN_TOKEN" "https://api-rate-limiter-gateway-qgfy.onrender.com/api/incidents/inc_12345678/resolve"
```

### 3. Run Automated Unit & Integration Tests
```powershell
$env:JAVA_HOME = "d:\Android\.jdk17"
.\gradlew.bat :gateway_server:test :app:testDebugUnitTest
```

---

## Free-Tier Caveats (Render Web Service & PostgreSQL)

1. **Cold Starts (~30–60s spin-up & initial JIT/TLS warmup)**:
   - Render Free Web Services spin down after **15 minutes of inbound inactivity**. The first request after idle waits for container boot (~30–50s), and the first upstream call establishes the pooled HTTP/2 TLS session.
   - Both the gateway (`https://api-rate-limiter-gateway-qgfy.onrender.com`) and the upstream (`https://polylance-fv-1-45wy.onrender.com`) run on Render Free tier; once warm, pooled HTTP/2 keep-alive connections reduce `upstream_ms` to ~35–75 ms and `limiter_ms` to `< 1 ms`.
2. **External Pinger Setup (`/health-light` every 5 minutes)**:
   - Use `GET https://api-rate-limiter-gateway-qgfy.onrender.com/health-light` for external uptime monitors (**UptimeRobot** or **cron-job.org**) on a **5-minute interval**.
   - `/health-light` returns `{"status":"UP","uptimeMs":"..."}` without touching PostgreSQL, keeping the gateway container warm without generating database query load.
3. **Render Free PostgreSQL 90-Day Expiry**:
   - Render Free PostgreSQL instances (`nt14-gateway-db`) expire **90 days after creation** (after which data is deleted unless upgraded to a paid plan or exported/recreated via Blueprint).
   - Check the expiration date in the [Render Dashboard](https://dashboard.render.com/) under `nt14-gateway-db` and export data via `/api/export?format=csv` or `pg_dump` before expiry.

See [`gateway_server/README.md`](gateway_server/README.md) for full endpoint specifications and architecture details.
