# Architecture

Stock Analyst separates public financial semantics from upstream acquisition. The
Kotlin service is the only API intended for consumers; the Python service is an
internal adapter around yfinance.

~~~text
                         public contract
Client  ----------------------------------------------+
                                                        |
                                                        v
                 +-----------------------------+   HTTP :8081
                 | Kotlin / Ktor API :8080     | ------------+
                 |                             |             |
                 | validation and typed errors |             v
                 | quote/history use cases     |   +----------------------+
                 | FX conversion               |   | Python adapter       |
                 | TA4J indicators              |   | Flask + Waitress     |
                 | provenance                   |   | yfinance integration |
                 +-----------------------------+   +----------+-----------+
                                                                  |
                                                                  v
                                                            Yahoo Finance
~~~

## Component responsibilities

### Kotlin API

The Kotlin service owns:

- canonical `/v1` routing and request validation;
- the generated-consumer contract published as OpenAPI;
- typed public errors and request correlation;
- quote, gain, yield and comparison semantics;
- history trimming, dividend fallback and split basis;
- historical FX conversion;
- technical indicators through TA4J;
- market-data provenance and freshness classification;
- public liveness, readiness and Prometheus metrics.

It must not expose yfinance-specific response objects directly. The domain models are
the normalization boundary.

### Python adapter

The adapter owns:

- yfinance calls for history, info and search;
- sanitization of non-finite upstream values;
- normalization of dividends, splits, timestamps and symbol identity;
- upstream error classification;
- successful-response caching;
- per-key single-flight, a global loader bulkhead and a global upstream circuit
  breaker;
- adapter health and bounded Prometheus metrics.

The adapter is reachable from the Kotlin service on port 8081. It is not a second
public API and its route shapes are allowed to evolve together with the Kotlin
backend client.

## Repository modules

| Path | Responsibility |
|---|---|
| `core/` | Shared serialization, dependency-injection and time abstractions |
| `domain/` | Models, provider interface, calculations and use cases |
| `src/` | Ktor application, routes, backend HTTP client and monitoring |
| `backend-yfinance/` | Python adapter, resilience primitives and tests |

The domain module depends on the provider interface rather than Flask or yfinance.
Koin wires the HTTP implementation at the application boundary.

## Main request flows

### Quote

The quote use case fetches instrument info and price history concurrently. It chooses
an effective terminal date, adds a spot point to an immutable calculation snapshot
when needed, fetches FX data when a target currency is requested, then calculates
gains and dividend metrics. Shorter history periods are tried when a newly listed
instrument lacks a useful long-term series. Because the provider has no buffered
five-year period, quote calculation fetches ten years and immediately trims it to a
14-day calculation buffer before the five-year target. Historical FX is an as-of
join with a four-day maximum age. Quote provenance reports current-price freshness
as the worse status of its instrument and conversion legs, separately from the
completeness and explicit limitations of derived gain analytics.

### History

The history use case fetches instrument info and the selected price interval
concurrently. It can also fetch:

- daily bars as a dividend fallback for weekly or monthly output;
- FX history for per-date conversion;
- an extended price window for indicator warmup.

Only after these inputs are combined does it trim the result to the requested
display range and calculate provenance.

### Latest indicators

The latest-indicator use case requests the configured history period, uses the same
currency and market-data rules as history, and returns the most recent valid values.
If that period cannot populate every requested indicator, provenance is `PARTIAL`.

### Compare

Up to ten quote use cases run concurrently. Symbol-specific failures are represented
inside the response. Rate-limit and availability rejections cancel the aggregate
operation so the service does not amplify upstream pressure.

### Search

Search is a thin normalized view of yfinance discovery. It intentionally retains all
Yahoo quote types; consumer-specific filtering belongs to the consumer.

## Resilience layers

The two services deliberately solve different duplication problems:

1. The Kotlin backend client coalesces identical in-flight HTTP requests. It does not
   maintain a completed-response cache.
2. The Python adapter checks its completed LRU/TTL cache, single-flights concurrent
   misses for the same key, then acquires a global loader permit for each unique key.
3. Inside the permit, a global circuit breaker classifies final Yahoo outcomes.

This ordering means same-key waiters share a loader permit, while different keys
consume separate permits. A cache hit can still be served while the upstream circuit
is open.

The Kotlin client retries only transport-level I/O failures for which no HTTP
response exists. Classified HTTP responses are not retried. Exact limits and timing
are documented in [Operations](operations.md).

## State and scaling

Neither service owns a domain database or message broker. Stock Analyst response
caches, in-flight maps, metrics and circuit state live in process memory and are lost
on restart. yfinance can additionally create disposable SQLite cookie and ticker-
timezone caches under `~/.cache/py-yfinance`; the container creates that writable
directory, but it is not canonical market data and is not shared between replicas.

Running multiple replicas is possible, but each replica has independent response and
yfinance implementation caches plus independent Yahoo rate-limit behavior.
Horizontal scaling therefore does not create a shared cache or rate-limit budget.

## Contract ownership

The canonical contract is
[`src/main/resources/openapi/stock-analyst-v1.json`](../src/main/resources/openapi/stock-analyst-v1.json).
The running API serves the same bundled bytes at `/openapi/v1.json`.

Consumer repositories can keep reviewed copies, but they are not updated
automatically. A behavior or contract change must therefore:

1. update and test the canonical document in this repository;
2. update each affected consumer copy intentionally;
3. record the reviewed source commit and contract hash in the ecosystem compatibility
   manifest;
4. roll out providers before consumers.

See [Deployment](deployment.md) for the current compatibility hand-off.

## Trust boundaries

Only port 8080 should be routed to API consumers. Port 8081 and the adapter's metrics
must remain on a private container network. The Kotlin `/metrics` endpoint should
also be restricted by the deployment layer; it is intentionally unauthenticated
inside the application.

The API performs no user authentication or authorization. A reverse proxy or private
network must provide any required access control, TLS and rate limiting.
