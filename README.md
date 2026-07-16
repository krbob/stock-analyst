# Stock Analyst

[![CI Build Pipeline](https://img.shields.io/github/actions/workflow/status/krbob/stock-analyst/ci-build.yml?branch=main&label=CI)](https://github.com/krbob/stock-analyst/actions/workflows/ci-build.yml)

Stock Analyst is a two-service market-data API. A Kotlin/Ktor service owns the public
contract, validation, currency conversion and technical analysis; an internal Python
adapter obtains raw data from Yahoo Finance through
[yfinance](https://github.com/ranaroussi/yfinance).

Yahoo Finance is an unofficial upstream dependency. Treat the data as best effort and
verify it independently before making financial decisions.

## Architecture

~~~text
Client -> Kotlin API (:8080) -> Python/yfinance adapter (:8081) -> Yahoo Finance
~~~

- `core/` — serialization, dependency injection and time abstractions.
- `domain/` — market-data models, calculations and use cases.
- `src/` — Ktor routes, the backend client and operational endpoints.
- `backend-yfinance/` — the internal Flask/Waitress adapter.

See [Architecture](docs/architecture.md) for component boundaries and request flows.

## Quick start

Docker with the Compose plugin is the shortest path to a complete local stack:

~~~bash
docker compose up --build --detach --wait

curl --fail http://localhost:8080/healthz
curl --fail http://localhost:8080/readyz
curl --fail http://localhost:8080/openapi/v1.json
~~~

`/readyz` becomes healthy only when the Kotlin API can reach the internal adapter.
Stop the stack with:

~~~bash
docker compose down
~~~

## API

The canonical API is versioned under `/v1`:

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/v1/quote/{stock}` | Normalized quote, gains and fundamentals |
| `GET` | `/v1/history/{stock}` | OHLCV history, dividends and optional indicator series |
| `GET` | `/v1/indicators/{stock}` | Latest requested technical indicators |
| `GET` | `/v1/compare` | Partial comparison of up to ten query-string symbols |
| `GET` | `/v1/search/{query}` | Yahoo Finance ticker discovery |

For example:

~~~bash
curl "http://localhost:8080/v1/quote/AAPL?currency=PLN"
curl "http://localhost:8080/v1/history/AAPL?from=2024-01-01&to=2024-06-30"
~~~

The bundled [OpenAPI 3.0 contract](src/main/resources/openapi/stock-analyst-v1.json)
is the source of truth for parameters, response schemas and typed errors. A running
service exposes the same document at `GET /openapi/v1.json`. Generate consumers from
that contract instead of copying response types from this README.

Unversioned market-data routes remain temporary compatibility aliases. New consumers
must use `/v1`.

The non-obvious data rules — effective valuation dates, provenance, exact history
ranges, split adjustment, the `dividends` flag and FX conversion — are documented in
[API semantics](docs/api-semantics.md).

## Operations

The API exposes:

- `GET /healthz` for process liveness;
- `GET /readyz` for readiness including the yfinance adapter;
- `GET /metrics` for bounded Prometheus metrics;
- `GET /health` as a legacy liveness alias.

The Python adapter also exposes `/health` and `/metrics` on its internal network.
Configuration, cache TTLs, retry budgets, circuit-breaker behavior and troubleshooting
are covered by the [Operations guide](docs/operations.md).

Production deployments should use immutable image digests and staged compatibility
gates. See [Deployment](docs/deployment.md); do not treat the moving `main` tags
published by CI and used by canaries as release identifiers. Local Compose defaults
to the separate `:local` images.

## Development

Native development requires JDK 25 and Python 3.13.14. Running `./gradlew run` starts
only the Kotlin service, so the Python adapter must already be listening on port 8081
or `BACKEND_URL` must point to another instance.

Setup, test, lockfile, SBOM and dependency-update commands are in
[Development](docs/development.md).

The primary checks are:

~~~bash
python3 scripts/validate-docs.py
./gradlew test detekt

python3.13 -m venv .venv
.venv/bin/python -m pip install --require-hashes -r backend-yfinance/requirements-dev.lock
(cd backend-yfinance && ../.venv/bin/python -m pytest test_app.py)
~~~

## Documentation

- [API semantics](docs/api-semantics.md)
- [Architecture](docs/architecture.md)
- [Operations](docs/operations.md)
- [Development](docs/development.md)
- [Deployment](docs/deployment.md)

The implementation uses Kotlin, Ktor, Koin,
[TA4J](https://github.com/ta4j/ta4j), Python, Flask, Waitress and yfinance.
