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

### `GET /quote/{symbol}`

Returns fundamental data, gains, and dividend metrics for a stock.

| Parameter  | Type  | Required | Description                                      |
|------------|-------|----------|--------------------------------------------------|
| `symbol`   | path  | yes      | Stock ticker (e.g., `AAPL`, `MSFT`, `GC=F`)     |
| `currency` | query | no       | Target currency ISO code (e.g., `EUR`, `PLN`)    |

```bash
curl http://localhost:8080/quote/AAPL
curl http://localhost:8080/quote/AAPL?currency=EUR
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
  "previousClose": 240.18
}
```

---

### `GET /indicators/{symbol}`

Returns the latest values of technical indicators for a stock. Computes indicators from historical data and returns only the most recent value of each.

| Parameter    | Type  | Required | Description                                                        |
|--------------|-------|----------|--------------------------------------------------------------------|
| `symbol`     | path  | yes      | Stock ticker (e.g., `AAPL`, `MSFT`)                               |
| `indicators` | query | no       | Comma-separated indicators: `rsi`, `macd`, `bb`, `sma50`, `sma200`, `ema50`, `ema200`. Unknown values return `400`. Omit for all. |
| `period`     | query | no       | History period for computation. Default: `1y`. Values: `1d`, `5d`, `1mo`, `3mo`, `6mo`, `1y`, `2y`, `5y`, `10y`, `ytd`, `max` |
| `interval`   | query | no       | Bar interval. Auto-selected if omitted. Values: `1m`, `5m`, `15m`, `30m`, `1h`, `1d`, `1wk`, `1mo`. Use `1wk` for weekly indicators, `1mo` for monthly. |
| `currency`   | query | no       | Target currency ISO code (e.g., `EUR`, `PLN`)                     |

```bash
curl http://localhost:8080/indicators/AAPL
curl "http://localhost:8080/indicators/AAPL?indicators=rsi,macd&currency=EUR"
curl "http://localhost:8080/indicators/AAPL?indicators=rsi&period=5y&interval=1wk"   # weekly RSI
curl "http://localhost:8080/indicators/AAPL?indicators=rsi&period=max&interval=1mo"  # monthly RSI
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

### `GET /history/{symbol}`

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
curl http://localhost:8080/history/AAPL
curl "http://localhost:8080/history/AAPL?period=1y&indicators=sma50,sma200,rsi,macd"
curl "http://localhost:8080/history/AAPL?period=1y&currency=EUR&dividends=true"
curl "http://localhost:8080/history/AAPL?period=1d&interval=5m"
curl "http://localhost:8080/history/AAPL?from=2024-01-01&to=2024-06-30"
```

**Example response:**

```json
{
  "symbol": "AAPL",
  "name": "Apple Inc.",
  "currency": "USD",
  "period": "1y",
  "interval": "1d",
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

When `indicators` is provided, extra historical data is fetched for indicator warmup (e.g., 200 extra bars for SMA200) and trimmed to the requested period. When `from` and `to` are supplied, the response is trimmed to that exact date window and echoes it back via `requestedFrom` / `requestedTo`; the `period` field then reflects the internal fetch window chosen by the server. Intraday intervals (`1m`, `5m`, etc.) include a `timestamp` field with UTC epoch seconds alongside `date`. Intraday responses are cached for 30 seconds.

---

### `GET /compare?symbols=...`

Compares multiple stocks in a single request. Returns partial results -- individual symbols can fail without failing the whole request.

| Parameter  | Type  | Required | Description                                         |
|------------|-------|----------|-----------------------------------------------------|
| `symbols`  | query | yes      | Comma-separated tickers, max 10 (e.g., `AAPL,MSFT`) |
| `currency` | query | no       | Target currency ISO code (e.g., `EUR`, `PLN`)       |

```bash
curl "http://localhost:8080/compare?symbols=AAPL,MSFT,INVALID"
curl "http://localhost:8080/compare?symbols=AAPL,MSFT&currency=EUR"
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

Each `data` object has the same schema as the `/quote/{symbol}` response.

---

### `GET /search/{query}`

Searches for stocks, ETFs, and indices by name or ticker. Results are cached for 5 minutes.

| Parameter | Type | Required | Description                                        |
|-----------|------|----------|----------------------------------------------------|
| `query`   | path | yes      | Search term (e.g., `apple`, `AAPL`). Max 50 chars. |

```bash
curl http://localhost:8080/search/apple
```

**Example response:**

```json
[
  { "symbol": "AAPL", "name": "Apple Inc.", "exchange": "NMS", "quoteType": "EQUITY" },
  { "symbol": "APLE", "name": "Apple Hospitality REIT, Inc.", "exchange": "NYQ", "quoteType": "EQUITY" }
]
```

Results are filtered to equities, ETFs, and indices. Returns an empty array when no matches are found.

## Key Features

### Currency conversion

Add `?currency=PLN` (or any ISO 4217 code) to convert monetary values using exchange rates.

```bash
curl http://localhost:8080/quote/AAPL?currency=PLN
curl http://localhost:8080/quote/VOW3.DE?currency=USD
curl "http://localhost:8080/history/AAPL?period=1y&currency=EUR"
```

**Converted fields (`/quote`):** `lastPrice`, `previousClose`, `eps`, `marketCap`, `fiftyTwoWeekHigh`, `fiftyTwoWeekLow` (current rate), `gain` (historical rates), `dividendYield`, `dividendGrowth` (historical rates on dividend dates).

**Converted fields (`/history`):** OHLCV prices, dividends, and all indicator values -- each at historical exchange rates for the respective date.

**Not converted** (dimensionless): `peRatio`, `pbRatio`, `roe`, `beta`.

The `currency` field in the response reflects the target currency only after a successful conversion. If the source instrument does not expose its native currency or the FX series is unavailable, the API returns `422`.

### Technical indicators

Available via `/indicators/{symbol}` (latest values) and `/history/{symbol}?indicators=...` (time series):

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

The `/compare` endpoint fetches each symbol independently. If one symbol fails (e.g., unknown ticker), the others still return data. Failed symbols include an `error` string instead of `data`.

### Search caching

Search results are cached in-memory for 5 minutes (up to 1000 entries) to reduce Yahoo Finance API calls.

## Error Codes

| Code | Reason                                               |
|------|------------------------------------------------------|
| 400  | Invalid symbol format, missing parameters, invalid indicator keys, invalid boolean flags, or too many symbols (max 10) |
| 404  | Unknown symbol or no history available               |
| 422  | Insufficient conversion data or conversion unavailable for the requested symbol/date range |
| 502  | Upstream Yahoo Finance/backend error                 |

## Running

### Docker

```bash
docker compose up -d
```

This starts both the Kotlin API (port 8080) and the Python backend (port 8081, internal).

For production with pre-built images:

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

### Development

Requires the Python backend to be running (either via Docker or directly).

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
