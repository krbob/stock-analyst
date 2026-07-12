# Stock Analyst

![GitHub Actions Workflow Status](https://img.shields.io/github/actions/workflow/status/krbob/stock-analyst/ci-build.yml)

Stock analysis API providing fundamental data, technical indicators, and historical prices. Built with Kotlin/Ktor and Python/Flask, with market data sourced from Yahoo Finance via [yfinance](https://github.com/ranaroussi/yfinance).

## Architecture

Two-service setup: a Kotlin API handles business logic, technical analysis ([TA4J](https://github.com/ta4j/ta4j)), and currency conversion, while a Python backend wraps yfinance to serve raw market data.

```
Client → Kotlin API (:8080) → Python backend (:8081) → Yahoo Finance
```

### Project structure

```
stock-analyst/
├── core/               Shared utilities (serialization, DI, time provider)
├── domain/             Business logic, models, use cases
├── src/                Ktor application (routes, HTTP client, config)
└── backend-yfinance/   Python/Flask data provider (Yahoo Finance)
```

## API Endpoints

The canonical API is versioned under `/v1`. The previous unversioned paths remain compatibility
aliases during migration. The bundled OpenAPI 3.0 contract is available at
`GET /openapi/v1.json` and in
`src/main/resources/openapi/stock-analyst-v1.json`; generate consumers from that document rather
than copying response types by hand.

### `GET /v1/quote/{symbol}`

Returns fundamental data, gains, and dividend metrics for a stock.

| Parameter  | Type  | Required | Description                                      |
|------------|-------|----------|--------------------------------------------------|
| `symbol`   | path  | yes      | Stock ticker (e.g., `AAPL`, `MSFT`, `GC=F`)     |
| `currency` | query | no       | Target currency ISO code (e.g., `EUR`, `PLN`)    |

```bash
curl http://localhost:8080/v1/quote/AAPL
curl http://localhost:8080/v1/quote/AAPL?currency=EUR
```

**Example response:**

```json
{
  "symbol": "AAPL",
  "name": "Apple Inc.",
  "currency": "USD",
  "date": "2026-03-12",
  "lastPrice": 242.41,
  "gain": {
    "daily": 0.005,
    "weekly": 0.031,
    "monthly": 0.089,
    "quarterly": 0.102,
    "halfYearly": 0.185,
    "ytd": 0.064,
    "yearly": 0.283,
    "fiveYear": 1.45
  },
  "peRatio": 29.18,
  "pbRatio": 64.35,
  "eps": 6.09,
  "roe": 1.57,
  "marketCap": 3664221000000.0,
  "beta": 1.24,
  "dividendYield": 0.004,
  "dividendGrowth": 0.042,
  "fiftyTwoWeekHigh": 260.1,
  "fiftyTwoWeekLow": 164.08,
  "sector": "Technology",
  "industry": "Consumer Electronics",
  "earningsDate": "2026-04-24",
  "recommendation": "buy",
  "analystCount": 40,
  "previousClose": 240.18,
  "provenance": {
    "source": "YAHOO_FINANCE",
    "retrievedAt": "2026-03-12T18:42:10Z",
    "marketTimestamp": "2026-03-12T16:00:00Z",
    "marketDate": "2026-03-12",
    "currency": "USD",
    "unitScale": 1.0,
    "adjustment": "SPLIT_ADJUSTED",
    "coverageFrom": "2021-03-12",
    "coverageTo": "2026-03-12",
    "status": "FRESH"
  }
}
```

`date` is the effective valuation date of `lastPrice`, not the API server's calendar date. It is
the latest applicable stock or FX market session. Every gain uses `lastPrice` and the same
effective spot FX rate as its terminal point; if cached history does not yet contain that session,
the endpoint adds it only to an immutable calculation snapshot. On weekends and market holidays
the snapshot remains anchored to the latest applicable market session.

---

### `GET /v1/indicators/{symbol}`

Returns the latest values of technical indicators for a stock. Computes indicators from historical data and returns only the most recent value of each.

| Parameter    | Type  | Required | Description                                                        |
|--------------|-------|----------|--------------------------------------------------------------------|
| `symbol`     | path  | yes      | Stock ticker (e.g., `AAPL`, `MSFT`)                               |
| `indicators` | query | no       | Comma-separated indicators: `rsi`, `macd`, `bb`, `sma50`, `sma200`, `ema50`, `ema200`. Unknown values return `400`. Omit for all. |
| `period`     | query | no       | History period for computation. Default: `1y`. Values: `1d`, `5d`, `1mo`, `3mo`, `6mo`, `1y`, `2y`, `5y`, `10y`, `ytd`, `max` |
| `interval`   | query | no       | Bar interval. Auto-selected if omitted. Values: `1m`, `5m`, `15m`, `30m`, `1h`, `1d`, `1wk`, `1mo`. Use `1wk` for weekly indicators, `1mo` for monthly. |
| `currency`   | query | no       | Target currency ISO code (e.g., `EUR`, `PLN`)                     |

```bash
curl http://localhost:8080/v1/indicators/AAPL
curl "http://localhost:8080/v1/indicators/AAPL?indicators=rsi,macd&currency=EUR"
curl "http://localhost:8080/v1/indicators/AAPL?indicators=rsi&period=5y&interval=1wk"   # weekly RSI
curl "http://localhost:8080/v1/indicators/AAPL?indicators=rsi&period=max&interval=1mo"  # monthly RSI
```

**Example response:**

```json
{
  "symbol": "AAPL",
  "date": "2026-03-12",
  "rsi": 55.2,
  "macd": {
    "macd": 1.54,
    "signal": 1.21,
    "histogram": 0.33
  },
  "bb": {
    "upper": 251.4,
    "middle": 244.8,
    "lower": 238.1
  },
  "sma50": 240.5,
  "sma200": 220.3,
  "ema50": 241.1,
  "ema200": 222.7
}
```

Only requested indicators are included in the response. When `indicators` is omitted, all are returned.

---

### `GET /v1/history/{symbol}`

Returns historical OHLCV price data with optional technical indicator series and dividends.

| Parameter    | Type  | Required | Description                                                        |
|--------------|-------|----------|--------------------------------------------------------------------|
| `symbol`     | path  | yes      | Stock ticker (e.g., `AAPL`, `MSFT`, `GC=F`)                      |
| `period`     | query | no       | Time range. Default: `1y`. Values: `1d`, `5d`, `1mo`, `3mo`, `6mo`, `1y`, `2y`, `5y`, `10y`, `ytd`, `max` |
| `interval`   | query | no       | Candle interval. Auto-selected if omitted (`5y`/`10y` → weekly, `max` → monthly, others → daily). Values: `1m`, `5m`, `15m`, `30m`, `1h`, `1d`, `1wk`, `1mo` |
| `indicators` | query | no       | Comma-separated: `sma50`, `sma200`, `ema50`, `ema200`, `bb`, `rsi`, `macd`. Unknown values return `400`. |
| `currency`   | query | no       | Target currency ISO code (e.g., `EUR`, `PLN`)                     |
| `dividends`  | query | no       | Set to `true` to include dividends in weekly/monthly candles. Daily candles always include dividends. Valid values: `true`, `false`. Default: `false` |
| `from`       | query | no       | Exact history start date in `YYYY-MM-DD`. Must be used together with `to`. |
| `to`         | query | no       | Exact history end date in `YYYY-MM-DD`. Must be used together with `from`. |

```bash
curl http://localhost:8080/v1/history/AAPL
curl "http://localhost:8080/v1/history/AAPL?period=1y&indicators=sma50,sma200,rsi,macd"
curl "http://localhost:8080/v1/history/AAPL?period=1y&currency=EUR&dividends=true"
curl "http://localhost:8080/v1/history/AAPL?period=1d&interval=5m"
curl "http://localhost:8080/v1/history/AAPL?from=2024-01-01&to=2024-06-30"
```

**Example response:**

```json
{
  "symbol": "AAPL",
  "name": "Apple Inc.",
  "currency": "USD",
  "period": "1y",
  "interval": "1d",
  "adjustment": "split-adjusted",
  "requestedFrom": "2024-01-01",
  "requestedTo": "2024-06-30",
  "prices": [
    {
      "date": "2025-03-03",
      "open": 242.31,
      "close": 242.41,
      "low": 240.15,
      "high": 243.50,
      "volume": 45000000,
      "dividend": 0.0
    }
  ],
  "indicators": {
    "sma50": [{ "date": "2025-03-03", "value": 240.5 }],
    "sma200": [{ "date": "2025-03-03", "value": 220.3 }],
    "bb": [{ "date": "2025-03-03", "upper": 251.4, "middle": 244.8, "lower": 238.1 }],
    "rsi": [{ "date": "2025-03-03", "value": 55.2 }],
    "macd": [{ "date": "2025-03-03", "macd": 1.54, "signal": 1.21, "histogram": 0.33 }]
  }
}
```

OHLC, volume and dividends use the latest split-adjusted share basis, while prices are not
adjusted for dividends. `splitRatio` is present only on a corporate-action candle and expresses
the new-to-old share ratio (for example, `10.0` for a 10-for-1 split). This keeps gains and
technical indicators continuous across splits without double-counting dividends.

yfinance repair also standardises histories quoted in subunits: GBp, ZAc and ILA candles and
dividends arrive in GBP, ZAR and ILS respectively. The service applies the `0.01` subunit scale
only to spot/info price fields, which yfinance still reports in the original exchange unit.

When `indicators` is provided, extra historical data is fetched for indicator warmup (e.g., 200 extra bars for SMA200) and trimmed to the requested period. When `from` and `to` are supplied, the response is trimmed to that exact date window and echoes it back via `requestedFrom` / `requestedTo`; the `period` field then reflects the internal fetch window chosen by the server. Intraday intervals (`1m`, `5m`, etc.) include a `timestamp` field with UTC epoch seconds alongside `date`. Intraday responses are cached for 30 seconds.

---

### `GET /v1/compare?symbols=...`

Compares multiple stocks in a single request. Returns partial results -- individual symbols can fail without failing the whole request.

| Parameter  | Type  | Required | Description                                         |
|------------|-------|----------|-----------------------------------------------------|
| `symbols`  | query | yes      | Comma-separated tickers, max 10 (e.g., `AAPL,MSFT`) |
| `currency` | query | no       | Target currency ISO code (e.g., `EUR`, `PLN`)       |

```bash
curl "http://localhost:8080/v1/compare?symbols=AAPL,MSFT,INVALID"
curl "http://localhost:8080/v1/compare?symbols=AAPL,MSFT&currency=EUR"
```

**Example response:**

```json
[
  {
    "symbol": "AAPL",
    "data": {
      "symbol": "AAPL",
      "name": "Apple Inc.",
      "currency": "USD",
      "date": "2026-03-12",
      "lastPrice": 242.41,
      "gain": { "daily": 0.005, "weekly": 0.031, "monthly": 0.089, "..." : "..." },
      "peRatio": 29.18,
      "dividendYield": 0.004,
      "..."  : "..."
    }
  },
  {
    "symbol": "MSFT",
    "data": { "..." : "..." }
  },
  {
    "symbol": "INVALID",
    "error": "Unknown symbol: INVALID"
  }
]
```

Each `data` object has the same schema as the `/v1/quote/{symbol}` response.

---

### `GET /v1/search/{query}`

Searches for stocks, ETFs, and indices by name or ticker. Results are cached for 5 minutes.

| Parameter | Type | Required | Description                                        |
|-----------|------|----------|----------------------------------------------------|
| `query`   | path | yes      | Search term (e.g., `apple`, `AAPL`). Max 50 chars. |

```bash
curl http://localhost:8080/v1/search/apple
```

**Example response:**

```json
[
  { "symbol": "AAPL", "name": "Apple Inc.", "exchange": "NMS", "quoteType": "EQUITY" },
  { "symbol": "APLE", "name": "Apple Hospitality REIT, Inc.", "exchange": "NYQ", "quoteType": "EQUITY" }
]
```

Results are filtered to equities, ETFs, and indices. Returns an empty array when no matches are found.

### Operational endpoints

- `GET /health` is the legacy liveness response.
- `GET /healthz` checks only that the Kotlin process can serve requests.
- `GET /readyz` checks the internal yfinance adapter with a one-second total deadline and returns
  `503` while that required dependency is unavailable.
- `GET /metrics` exposes Prometheus text metrics for bounded route templates, status codes and
  request latency. Health, readiness, metrics and OpenAPI traffic are excluded, and labels never
  contain symbols, query values or request IDs.

The internal Python adapter also exposes `GET :8081/metrics` on the Compose network. It reports
bounded endpoint latency/status, history and metadata cache outcomes/size, active single-flight
and bulkhead loaders, bulkhead/circuit rejections, the current circuit state and explicit state
transition counters (`failure_threshold`, `force_open`, `cooldown_elapsed`, `probe_success` and
`probe_failure`). These labels likewise never contain ticker symbols or search values.

Pull requests run a deterministic container smoke against liveness, readiness, OpenAPI, metrics
and typed error/request-ID contracts; it does not call Yahoo. The separate `Live Yahoo canary`
workflow runs on weekdays and on manual dispatch against the published `main` images. It validates
an AAPL quote/history, provenance semantics and an upstream 404, but is intentionally non-blocking
so an external outage cannot reject an otherwise reproducible build.

## Key Features

### Market-data provenance

Quote, history and latest-indicator responses include a required `provenance` object. It keeps
source and freshness metadata separate from the financial values and uses the same shape for all
three endpoints:

- `retrievedAt` is when this API assembled the response; `marketTimestamp` and `marketDate`
  describe the effective upstream market observation.
- `coverageFrom` and `coverageTo` describe the history actually used, which can be narrower than
  a requested range when currency-conversion history starts later.
- `currency`, `unitScale` and `adjustment` make the value basis explicit. Prices are returned in
  major currency units (`unitScale: 1.0`) on a split-adjusted basis.
- `status` is `FRESH`, `STALE` or `PARTIAL`; `ERROR` is reserved for future batch responses.
  `PARTIAL` takes precedence when the requested calculation cannot be fully populated, while
  `STALE` describes the market observation (not the retrieval). The maximum expected age follows
  its cadence: four days for quote/intraday/daily data, ten for weekly bars and forty for monthly
  bars. An upstream `marketTimestamp`, when available, takes precedence over its calendar date.

Consumers should render this metadata as supporting context, not infer freshness from the API
server clock or from a chart's last visible point.

### Currency conversion

Add `?currency=PLN` (or any ISO 4217 code) to convert monetary values using exchange rates.

```bash
curl http://localhost:8080/v1/quote/AAPL?currency=PLN
curl http://localhost:8080/v1/quote/VOW3.DE?currency=USD
curl "http://localhost:8080/v1/history/AAPL?period=1y&currency=EUR"
```

**Converted fields (`/v1/quote`):** `lastPrice`, `previousClose`, `eps`, `marketCap`, `fiftyTwoWeekHigh`, `fiftyTwoWeekLow` (current rate), `gain` (historical rates), `dividendYield`, `dividendGrowth` (historical rates on dividend dates).

**Converted fields (`/v1/history`):** OHLCV prices, dividends, and all indicator values -- each at historical exchange rates for the respective date.

**Not converted** (dimensionless): `peRatio`, `pbRatio`, `roe`, `beta`.

The `currency` field in the response reflects the target currency only after a successful conversion. If the source instrument does not expose its native currency or the FX series is unavailable, the API returns `422`.

### Technical indicators

Available via `/v1/indicators/{symbol}` (latest values) and `/v1/history/{symbol}?indicators=...` (time series):

| Indicator        | Key      | Output fields                            |
|------------------|----------|------------------------------------------|
| SMA 50           | `sma50`  | `value`                                  |
| SMA 200          | `sma200` | `value`                                  |
| EMA 50           | `ema50`  | `value`                                  |
| EMA 200          | `ema200` | `value`                                  |
| Bollinger Bands  | `bb`     | `upper`, `middle`, `lower` (20-day, 2 sigma) |
| RSI              | `rsi`    | `value` (14-period, 0-100 scale)         |
| MACD             | `macd`   | `macd`, `signal`, `histogram`            |

### Partial compare results

The `/v1/compare` endpoint fetches each symbol independently. If one symbol fails (e.g., unknown ticker), the others still return data. Failed symbols include an `error` string instead of `data`. An upstream rate limit aborts the whole comparison with `429`, because retrying individual entries would amplify the limit.

### Backend caching

The yfinance backend caches only successful responses in two thread-safe, access-order LRU/TTL
pools. Historical series (including FX series) use the history pool; smaller info and search
responses (including FX info) use the metadata pool. Each pool enforces both an entry limit and an
estimated retained-byte limit. The estimator walks the cached Python dataclasses and containers
without JSON serialisation. An entry larger than its pool budget is returned to the caller but is
not cached. Set either limit to `0` to disable that pool.

| Environment variable | Default | Purpose |
|----------------------|---------|---------|
| `YFINANCE_HISTORY_CACHE_MAX_BYTES` | `67108864` (64 MiB) | Maximum estimated bytes retained by history entries |
| `YFINANCE_HISTORY_CACHE_MAX_ENTRIES` | `512` | Maximum number of history entries |
| `YFINANCE_METADATA_CACHE_MAX_BYTES` | `8388608` (8 MiB) | Maximum estimated bytes retained by info/search entries |
| `YFINANCE_METADATA_CACHE_MAX_ENTRIES` | `2048` | Maximum number of info/search entries |
| `YFINANCE_BULKHEAD_MAX_ACTIVE_LOADERS` | `4` | Maximum concurrent unique-key yfinance loaders |
| `YFINANCE_BULKHEAD_ACQUIRE_TIMEOUT_MS` | `250` | Maximum wait for a loader permit before returning `503` |
| `YFINANCE_BULKHEAD_RETRY_AFTER_SECONDS` | `1` | `Retry-After` sent for local bulkhead saturation |
| `YFINANCE_CIRCUIT_BREAKER_FAILURE_THRESHOLD` | `4` | Consecutive upstream failures required to open the circuit |
| `YFINANCE_CIRCUIT_BREAKER_FAILURE_WINDOW_SECONDS` | `30` | Window in which consecutive failures accumulate |
| `YFINANCE_CIRCUIT_BREAKER_OPEN_SECONDS` | `30` | Cooldown before one half-open recovery probe |
| `YFINANCE_WAITRESS_THREADS` | `8` | HTTP worker threads; must exceed the loader limit |

TTL expiry uses a monotonic clock, and reads promote an entry in LRU order independently of its
expiry time. The Kotlin API coalesces identical in-flight
backend requests instead of maintaining a second response cache. The Python adapter also
single-flights overlapping misses for the same history/info/search key, even when its completed
cache is disabled; different keys load independently, and failures are never retained. The
per-process global bulkhead is applied after single-flight, so waiters for one key share a permit
while unique keys consume separate permits. Saturation is reported as retryable `503`, independently
of Yahoo's `429` rate limit. Waitress has more HTTP workers than loader permits (`8 > 4` by
default), allowing saturated requests to reach the `503` path and health checks to bypass the
bulkhead instead of all workers blocking inside yfinance. The `docker-compose.yml` file forwards
these settings from the shell or a local `.env` file. Inside each acquired bulkhead permit, one
global per-process circuit breaker tracks only final Yahoo rate-limit (`429`) and upstream (`502`)
failures. A provider rate limit opens it immediately for Yahoo's 60-second retry period; four
consecutive `502` failures within 30 seconds open it for 30 seconds. Healthy results and verified
`404` responses reset the series. While open it returns `503` with a dynamic `Retry-After`, then
permits exactly one half-open probe. Cache limits and the bulkhead timeout may be zero;
active-loader, retry-after, circuit-breaker and worker values must be positive integers, with
workers greater than loaders. The Kotlin API forwards every received HTTP status without an
immediate retry, preserving `429`/`503` and `Retry-After`; its two retries are reserved for
transport-level `IOException`s where the backend returned no response. Each Kotlin-to-Python
attempt has a six-second request/socket deadline (two seconds to connect) and retries wait a fixed
250 ms. Consequently, even the worst permitted three-attempt transport path is bounded at 18.5 s;
callers must use a strictly larger total deadline and must not retry classified HTTP responses.

## Supply chain and reproducible builds

All Kotlin modules declare the JDK 25 toolchain. Python development and production use 3.13.14
(`.python-version` and the digest-pinned runtime image), and the Gradle wrapper verifies the 9.6.1
distribution SHA-256. Every resolvable Gradle configuration is locked. After an intentional
dependency change, regenerate and commit the lock state rather than editing it manually:

```bash
./gradlew resolveAndLockAll --write-locks
git status --short -- '*gradle.lockfile'
```

Generate the aggregate production-runtime CycloneDX 1.6 SBOM with:

```bash
./gradlew cyclonedxBom
```

The ignored output is `build/reports/cyclonedx/stock-analyst.cdx.json`. It excludes test and build
dependencies, omits a random serial number and normalises its sole timestamp to the Unix epoch, so
identical source and locks produce identical bytes. The API JDK/JRE and Python base images are
pinned by multi-platform OCI digests. The API builder also keeps Gradle downloads in a BuildKit
cache mount; source changes no longer force the wrapper and dependencies to be fetched from zero.

## Error Codes

| Code | Reason                                               |
|------|------------------------------------------------------|
| 400  | Invalid symbol format, missing parameters, invalid indicator keys, invalid boolean flags, or too many symbols (max 10) |
| 404  | Unknown symbol or no history available               |
| 422  | Insufficient conversion data or conversion unavailable for the requested symbol/date range |
| 429  | Upstream rate limit; retry according to `Retry-After` |
| 502  | Upstream Yahoo Finance/backend error                 |
| 503  | Local data-backend bulkhead saturated or upstream circuit open; retry according to `Retry-After` |

## Running

### Docker

```bash
docker compose up -d
```

This starts both the Kotlin API (port 8080) and the Python backend (port 8081, internal).

For local evaluation with pre-built branch images:

```yaml
services:
  stock-analyst:
    image: ghcr.io/krbob/stock-analyst:main
    ports:
      - "8080:8080"
    depends_on:
      - stock-analyst-backend-yfinance
    environment:
      - BACKEND_URL=http://stock-analyst-backend-yfinance:8081
    restart: unless-stopped

  stock-analyst-backend-yfinance:
    image: ghcr.io/krbob/stock-analyst-backend-yfinance:main
    restart: unless-stopped
```

Production deployments must replace floating `main` tags with image digests from the tested
ecosystem compatibility manifest.

### Development

Requires JDK 25 because TA4J 0.22.x is compiled for Java 25 and Python 3.13.14 when the backend is
run directly. Docker uses the same language families and pinned runtime contents.

```bash
./gradlew run
```

### Tests

```bash
# Kotlin
./gradlew test

# Python
pip install -r backend-yfinance/requirements-dev.txt
pytest backend-yfinance/test_app.py
```

### Dependency updates

Renovate may automerge eligible routine dependency updates after CI, but every `yfinance` update
is held for manual upstream-contract review and for at least seven days after release. Before
merging it, run the Python adapter suite (including repair/subunit/split/error fixtures), build the
backend image, and dispatch the non-blocking `Live Yahoo canary` against the candidate image.

## Tech Stack

| Component          | Technology              |
|--------------------|-------------------------|
| API server         | Kotlin, Ktor, Koin      |
| Data provider      | Python, Flask, yfinance |
| Technical analysis | TA4J                    |
| Serialization      | kotlinx.serialization   |
| Testing            | JUnit, MockK, pytest    |
| CI/CD              | GitHub Actions          |
| Deployment         | Docker, GHCR            |
