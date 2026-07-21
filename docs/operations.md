# Operations

This guide describes the runtime behavior of both Stock Analyst services. The
repository Compose file is intended for local and integration use; production
networking and image promotion are covered separately in
[Deployment](deployment.md).

## Runtime topology

| Service | Port | Exposure |
|---|---:|---|
| Kotlin API | 8080 | Consumer-facing through a trusted reverse proxy |
| Python/yfinance adapter | 8081 | Internal container network only |

The adapter has no authentication. Never expose port 8081 directly to an untrusted
network.

## Health and operational endpoints

### Kotlin API

| Path | Success | Meaning |
|---|---|---|
| `/health` | `200`, text `ok` | Legacy liveness alias |
| `/healthz` | `200`, `{"status":"UP"}` | The process can serve requests |
| `/readyz` | `200`, `{"status":"UP"}` | Adapter `/health` succeeded within one second |
| `/readyz` | `503`, `{"status":"DOWN"}` | Required adapter unavailable or too slow |
| `/metrics` | `200`, Prometheus text | Bounded API request metrics |
| `/openapi/v1.json` | `200`, JSON | Contract bundled into this application image |

Liveness deliberately does not depend on Yahoo or the adapter. Use `/healthz` for a
container liveness probe and `/readyz` for readiness and rollout gates.

### Python adapter

| Path | Success | Meaning |
|---|---|---|
| `/health` | `200`, `{"status":"ok"}` | Waitress/Flask can serve requests |
| `/metrics` | `200`, Prometheus text | Adapter, cache and resilience metrics |

Adapter health does not call Yahoo and bypasses the loader bulkhead and circuit
breaker. It verifies process availability, not fresh upstream market data.

## Configuration

### Kotlin API

| Variable | Default | Purpose |
|---|---|---|
| `BACKEND_URL` | `http://localhost:8081` | Base URL of the internal Python adapter |

Backend request budgets are compile-time service policy rather than environment
variables. The default and budgets are defined in
[`BackendProviderModule.kt`](../src/main/kotlin/net/bobinski/stockanalyst/BackendProviderModule.kt).
See [Retries and deadlines](#retries-and-deadlines).

The repository Compose stack overrides `BACKEND_URL` with
`http://stock-analyst-backend-yfinance:8081`, using its private service DNS name.

### Python adapter

| Variable | Default | Constraints and purpose |
|---|---:|---|
| `YFINANCE_HISTORY_CACHE_MAX_BYTES` | `67108864` | Non-negative; estimated retained bytes for history and FX history |
| `YFINANCE_HISTORY_CACHE_MAX_ENTRIES` | `512` | Non-negative; history entry limit |
| `YFINANCE_METADATA_CACHE_MAX_BYTES` | `8388608` | Non-negative; estimated retained bytes for info, FX info and search |
| `YFINANCE_METADATA_CACHE_MAX_ENTRIES` | `2048` | Non-negative; metadata entry limit |
| `YFINANCE_BULKHEAD_MAX_ACTIVE_LOADERS` | `4` | Positive; concurrent unique-key Yahoo loaders |
| `YFINANCE_BULKHEAD_ACQUIRE_TIMEOUT_MS` | `250` | Non-negative; wait for a loader permit |
| `YFINANCE_BULKHEAD_RETRY_AFTER_SECONDS` | `1` | Positive; `Retry-After` for local saturation |
| `YFINANCE_CIRCUIT_BREAKER_FAILURE_THRESHOLD` | `4` | Positive; consecutive upstream failures before open |
| `YFINANCE_CIRCUIT_BREAKER_FAILURE_WINDOW_SECONDS` | `30` | Positive; accumulation window |
| `YFINANCE_CIRCUIT_BREAKER_OPEN_SECONDS` | `30` | Positive; cooldown before a half-open probe |
| `YFINANCE_WAITRESS_THREADS` | `8` | Positive and strictly greater than the loader limit |

Setting either byte or entry limit to `0` disables completed-response caching for
that pool. A bulkhead acquire timeout of `0` makes permit acquisition non-blocking.

Yahoo rate limits use a fixed 60-second retry period. This value is not currently
environment-configurable. Adapter defaults and validation are implemented in
[`backend-yfinance/app.py`](../backend-yfinance/app.py).

The checked-in [`docker-compose.yml`](../docker-compose.yml) forwards all adapter
variables from the shell or a local `.env` file and supplies the defaults above.

## Cache behavior

The adapter has two thread-safe access-order LRU/TTL pools:

- history and FX history;
- instrument info, FX info and search.

Both the entry count and estimated retained bytes are bounded. An entry larger than
its pool budget is returned to the caller but is not cached. TTL expiry uses a
monotonic clock, and a successful read promotes the entry in LRU order.

Only completed successful loads are retained. Empty history and missing info are not
cached; an empty but successful search result is cached. Failures are never retained.
Values are copied at the cache boundary so a caller cannot mutate the retained
entry.

### TTLs

An intraday history interval always uses 30 seconds, regardless of requested period.
Non-intraday history uses:

| Period | TTL |
|---|---:|
| `1d` | 2 minutes |
| `5d` | 5 minutes |
| `1mo`, `3mo`, `ytd` | 1 hour |
| `6mo` | 2 hours |
| `1y` | 4 hours |
| `2y` | 12 hours |
| `5y`, `10y`, `max` | 24 hours |

Instrument info and search each use five minutes.

These are internal adapter TTLs. Although adapter responses include
`Cache-Control`, the Kotlin service deserializes them and does not forward that
header. Consumers must not treat these values as a public HTTP cache contract.

## Single-flight, bulkhead and circuit breaker

For each adapter request:

1. the completed cache is checked;
2. concurrent misses for the same key join one single-flight operation;
3. the leader checks the cache again;
4. the unique-key load acquires one global bulkhead permit;
5. the load executes through one process-wide Yahoo circuit breaker.

Same-key waiters therefore share one permit. Different keys consume separate
permits. A completed cache hit bypasses an open circuit.

When no permit is available within the configured timeout, the adapter returns
`503` with the local bulkhead `Retry-After`. Waitress must have more HTTP workers
than loader permits so health checks and saturation responses can still be served.

The circuit counts final Yahoo/upstream failures only:

- a Yahoo `429` opens it immediately for the fixed 60-second provider retry period;
- by default, four consecutive `502` outcomes within 30 seconds open it for
  30 seconds;
- a verified missing symbol and a healthy result reset the failure series;
- while open, calls return `503` with a dynamic `Retry-After`;
- after cooldown exactly one half-open recovery probe is allowed.

## Retries and deadlines

The Kotlin-to-Python HTTP client has:

| Budget | Value |
|---|---:|
| Connect timeout | 2 seconds |
| Request timeout per attempt | 6 seconds |
| Socket timeout per attempt | 6 seconds |
| Additional transport retries | 2 |
| Delay between attempts | 250 ms |
| Maximum three-attempt path | 18.5 seconds |

Only transport-level `IOException` failures for which no HTTP response exists are
retried. Cancellation and every classified HTTP response are not retried.

Callers that place a total deadline around a market-data request must allow more than
18.5 seconds if they intend to permit the complete transport-retry path. They should
respect `Retry-After` rather than immediately retrying public `429` or `503`
responses.

## Metrics

The Kotlin `/metrics` endpoint exposes request count and latency histograms with:

- bounded method labels;
- normalized route templates;
- status codes;
- no symbols, search queries, request IDs or arbitrary paths.

Health, readiness, metrics and OpenAPI traffic are excluded.

The adapter `/metrics` endpoint additionally reports:

- bounded endpoint count and latency;
- cache hit, miss and error outcomes;
- retained entry and estimated-byte gauges;
- active single-flight keys and active/maximum bulkhead loaders;
- bulkhead and circuit rejections;
- current circuit state and failure count;
- circuit transition counters with bounded reason labels.

Neither endpoint authenticates scrapes. Restrict both with the container network,
reverse proxy or monitoring-network policy.

In a reverse-proxy deployment, Traefik can expose the Kotlin API under
`https://stocks.example.invalid/api` and strip `/api` before forwarding. Client-facing
`/api/metrics` is intentionally denied. Container-local paths remain the unprefixed
paths documented above.

## Diagnostics

Start with service state and bounded logs:

~~~bash
docker compose ps
docker compose logs --tail=200 stock-analyst
docker compose logs --tail=200 stock-analyst-backend-yfinance
~~~

Then distinguish the failure layer:

~~~bash
curl --fail http://localhost:8080/healthz
curl --fail http://localhost:8080/readyz
docker compose exec stock-analyst-backend-yfinance \
  curl --fail http://localhost:8081/health
~~~

- liveness fails: inspect the corresponding process/container;
- API liveness passes but readiness fails: inspect adapter reachability and
  `BACKEND_URL`;
- readiness passes but a market call returns `429`: Yahoo rate limit, respect
  `Retry-After`;
- `503` with `Retry-After`: local bulkhead saturation or open upstream circuit;
- `502`: adapter communication, response deserialization or an upstream failure that
  was not an expected missing symbol;
- `422`: required currency or historical conversion data was unavailable.

Use request IDs to correlate a public typed error with Kotlin logs. Metric labels
deliberately omit ticker/query cardinality. Normal request logs can still contain
the actual request path, so apply suitable log access and retention controls.

## Live upstream validation

Pull requests use deterministic fixtures and container smoke tests and do not call
Yahoo. The separate scheduled
[`Live Yahoo canary`](../.github/workflows/live-canary.yml) runs on weekdays against
published `main` images. It checks an AAPL quote, bounded and maximum history,
provenance, and a typed missing-symbol response. A failed canary is reported as a
failed workflow run, but it is not a job in the reproducible build pipeline.

An unsuccessful live canary indicates upstream or integration risk and should be
investigated, but it does not invalidate a reproducible build by itself.
