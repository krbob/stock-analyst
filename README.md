# Stock Analyst

![GitHub Actions Workflow Status](https://img.shields.io/github/actions/workflow/status/krbob/stock-analyst/ci-build.yml)

Stock analysis API providing technical indicators and fundamental data. Built with Kotlin/Ktor and Python/Flask, data sourced from Yahoo Finance via [yfinance](https://github.com/ranaroussi/yfinance).

## Architecture

Two-service Docker setup:

- **Kotlin API** (port 8080) — Ktor server, business logic, technical analysis via [TA4J](https://github.com/ta4j/ta4j)
- **Python backend** (port 8081, internal) — Flask wrapper around yfinance, serves historical prices and stock info

```
Client → Kotlin API (:8080) → Python backend (:8081) → Yahoo Finance
```

### Project structure

```
stock-analyst/
├── core/           Shared utilities (serialization, DI, time provider)
├── domain/         Business logic, models, use cases
├── src/            Ktor application (routes, HTTP client, config)
└── backend-yfinance/  Python/Flask data provider (Yahoo Finance)
```

## Usage

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

## API

### `GET /analysis/{symbol}`

Returns full analysis for a stock symbol.

| Parameter    | Type   | Description                                   |
|--------------|--------|-----------------------------------------------|
| `symbol`     | path   | Stock ticker (e.g., `AAPL`, `MSFT`, `GC=F`)  |
| `currency`   | query  | Optional target currency ISO code (e.g., `EUR`, `PLN`) |

```bash
curl http://localhost:8080/analysis/aapl
curl http://localhost:8080/analysis/aapl?currency=EUR
```

### `GET /price/{symbol}`

Returns lightweight price and gain data. Faster than `/analysis` — no technical indicators.

| Parameter    | Type   | Description                                   |
|--------------|--------|-----------------------------------------------|
| `symbol`     | path   | Stock ticker (e.g., `AAPL`, `MSFT`, `GC=F`)  |
| `currency`   | query  | Optional target currency ISO code (e.g., `EUR`, `PLN`) |

```bash
curl http://localhost:8080/price/aapl
```

#### Example response

```json
{
  "symbol": "aapl",
  "name": "Apple Inc.",
  "currency": "USD",
  "date": "2026-02-28",
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
  }
}
```

### `GET /compare?symbols=...`

Compares multiple stocks in a single request. Returns a list of full analysis objects.

| Parameter    | Type   | Description                                        |
|--------------|--------|----------------------------------------------------|
| `symbols`    | query  | Comma-separated tickers (max 10). Required.        |
| `conversion` | query  | Optional currency pair (e.g., `eur=x`)             |

```bash
curl "http://localhost:8080/compare?symbols=AAPL,MSFT,GOOG"
```

Each element in the response array has the same schema as the `/analysis/{symbol}` response.

### `GET /search/{query}`

Searches for stocks, ETFs and indices by name or ticker.

| Parameter | Type | Description                                      |
|-----------|------|--------------------------------------------------|
| `query`   | path | Search term (e.g., `apple`, `AAPL`, `s&p`). Max 50 chars. |

```bash
curl http://localhost:8080/search/apple
```

#### Example response

```json
[
  { "symbol": "AAPL", "name": "Apple Inc.", "exchange": "NMS", "quoteType": "EQUITY" },
  { "symbol": "APLE", "name": "Apple Hospitality REIT, Inc.", "exchange": "NYQ", "quoteType": "EQUITY" },
  { "symbol": "APC.DE", "name": "Apple Inc.", "exchange": "GER", "quoteType": "EQUITY" }
]
```

Results are filtered to equities, ETFs and indices. Returns an empty array when no matches are found.

### `GET /history/{symbol}`

Returns historical OHLCV price data for a stock symbol.

| Parameter  | Type  | Description                                         |
|------------|-------|-----------------------------------------------------|
| `symbol`   | path  | Stock ticker (e.g., `AAPL`, `MSFT`, `GC=F`)        |
| `period`   | query | Time range. Default: `1y`. Values: `1d`, `5d`, `1mo`, `3mo`, `6mo`, `1y`, `2y`, `5y`, `10y`, `ytd`, `max` |
| `interval` | query | Candle interval. Optional — defaults based on period (`5y`/`10y` → weekly, `max` → monthly, others → daily). Values: `1m`, `5m`, `15m`, `30m`, `1h`, `1d`, `1wk`, `1mo` |
| `indicators` | query | Comma-separated technical indicators to include. Optional. Values: `sma50`, `sma200`, `ema50`, `ema200`, `bb`, `rsi`, `macd` |
| `currency` | query | Target currency (ISO 4217, e.g. `EUR`, `PLN`). Converts OHLCV prices and indicator values using historical exchange rates. Optional — omit for native currency. |
| `dividends` | query | Set to `true` to include dividend data in weekly/monthly candles. For daily candles, dividends are always present. Optional — defaults to `false`. |

When `indicators` is provided, the backend automatically fetches extra historical data for indicator warmup (e.g., 200 extra bars for SMA200) and trims the result to the requested period.

```bash
curl http://localhost:8080/history/aapl
curl http://localhost:8080/history/aapl?period=5y
curl http://localhost:8080/history/aapl?period=5y&interval=1d
curl "http://localhost:8080/history/aapl?period=1y&indicators=sma50,sma200,rsi,macd"
curl "http://localhost:8080/history/aapl?period=1d&interval=5m"
curl "http://localhost:8080/history/aapl?period=1y&currency=EUR"
```

#### Example response

```json
{
  "symbol": "aapl",
  "name": "Apple Inc.",
  "currency": "USD",
  "period": "1y",
  "interval": "1d",
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

| Field      | Description                                          |
|------------|------------------------------------------------------|
| `period`   | The time range used for the query.                   |
| `interval` | The candle interval used (e.g., `1d`, `5m`, `1wk`). |
| `prices`   | Array of OHLCV data sorted by time ascending.        |
| `open`     | Opening price for the bar.                           |
| `close`    | Closing price for the bar.                           |
| `high`     | Highest price during the bar.                        |
| `low`      | Lowest price during the bar.                         |
| `volume`   | Number of shares traded.                             |
| `dividend` | Dividend paid on that date (0 if none).              |
| `timestamp`| Epoch seconds (UTC). Present only for intraday intervals. |
| `currency`   | Currency of the prices (native or converted). Present when known. |
| `indicators` | Object with requested indicator series. Omitted when `indicators` param is absent. |

**Intraday intervals** (`1m`, `5m`, `15m`, `30m`, `1h`) return bars with a `timestamp` field (epoch seconds). Data availability depends on the period — yfinance limits: `1m` up to 7 days, `5m`/`15m`/`30m` up to 60 days, `1h` up to 730 days. Intraday responses are cached for 30 seconds.

### `GET /analysis/{symbol}` — example response

```json
{
  "symbol": "aapl",
  "name": "Apple Inc.",
  "currency": "USD",
  "date": "2026-02-28",
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
  "rsi": {
    "daily": 55.23,
    "weekly": 60.17,
    "monthly": 65.44
  },
  "macd": {
    "macd": 1.54,
    "signal": 1.21,
    "histogram": 0.33
  },
  "bollingerBands": {
    "upper": 251.45,
    "middle": 244.80,
    "lower": 238.15
  },
  "movingAverages": {
    "sma50": 240.67,
    "sma200": 220.34,
    "ema50": 241.15,
    "ema200": 222.48
  },
  "atr": 3.47,
  "dividendYield": 0.004,
  "dividendGrowth": 0.042,
  "peRatio": 29.18,
  "pbRatio": 64.35,
  "eps": 6.09,
  "roe": 1.57,
  "marketCap": 3664221000000.0,
  "recommendation": "buy",
  "analystCount": 40,
  "fiftyTwoWeekHigh": 260.1,
  "fiftyTwoWeekLow": 164.08,
  "beta": 1.24,
  "sector": "Technology",
  "industry": "Consumer Electronics",
  "earningsDate": "2026-04-24"
}
```

## Response fields

### Price

| Field       | Description                              |
|-------------|------------------------------------------|
| `lastPrice` | Current market price (refreshed every 5 min) |
| `currency`  | ISO 4217 currency code (e.g., `USD`, `EUR`, `GBP`) |

### Gain

Percentage price change over a given period. A value of `0.05` means a 5% increase.

| Field        | Period      |
|--------------|-------------|
| `daily`      | 1 day       |
| `weekly`     | 1 week      |
| `monthly`    | 1 month     |
| `quarterly`  | 3 months    |
| `halfYearly` | 6 months    |
| `ytd`        | Year to date|
| `yearly`     | 1 year      |
| `fiveYear`   | 5 years     |

### RSI (Relative Strength Index)

Momentum oscillator on a 0–100 scale. Measures the speed and magnitude of price changes over a 14-period window.

- **> 70** — overbought, potential signal for a decline
- **< 30** — oversold, potential signal for a rise
- **40–60** — neutral range

Calculated on daily, weekly and monthly data.

### MACD (Moving Average Convergence Divergence)

Trend and momentum indicator based on the difference between two exponential moving averages (EMA 12 and EMA 26).

| Field       | Description                                                  |
|-------------|--------------------------------------------------------------|
| `macd`      | MACD line (EMA 12 - EMA 26). Positive = upward trend.       |
| `signal`    | Signal line (9-period EMA of the MACD line).                 |
| `histogram` | MACD minus signal. A sign change suggests a potential trend reversal. |

How to read it:
- **MACD crosses above signal** — bullish crossover (buy signal)
- **MACD crosses below signal** — bearish crossover (sell signal)
- **Rising histogram** — upward momentum is strengthening

### Bollinger Bands

Volatility bands based on a 20-day SMA and standard deviation (2x).

| Field    | Description                                              |
|----------|----------------------------------------------------------|
| `upper`  | Upper band (SMA + 2 * std dev). Dynamic resistance.      |
| `middle` | Middle line (20-day SMA).                                 |
| `lower`  | Lower band (SMA - 2 * std dev). Dynamic support.         |

How to read it:
- **Price near upper band** — potentially overbought
- **Price near lower band** — potentially oversold
- **Narrow bands** — low volatility, anticipates a move (squeeze)
- **Wide bands** — high volatility

### Moving Averages

Moving averages smooth out price noise and help identify trends.

| Field    | Description                                                 |
|----------|-------------------------------------------------------------|
| `sma50`  | 50-day simple moving average                                |
| `sma200` | 200-day simple moving average                               |
| `ema50`  | 50-day exponential moving average (reacts faster)           |
| `ema200` | 200-day exponential moving average                          |

How to read it:
- **SMA50 > SMA200** — Golden Cross, bullish trend signal
- **SMA50 < SMA200** — Death Cross, bearish trend signal
- **Price > SMA200** — long-term uptrend

### ATR (Average True Range)

A measure of price volatility (14-day). Does not indicate direction, only the scale of price movements.

- Higher value = greater volatility (and risk)
- Useful for setting stop-loss levels: e.g., 2x ATR below the current price

### Fundamental data

| Field                | Description                                              |
|----------------------|----------------------------------------------------------|
| `dividendYield`      | Annual dividend yield (0.005 = 0.5%)                     |
| `dividendGrowth`     | Year-over-year dividend growth (0.042 = +4.2%)           |
| `peRatio`            | Price/Earnings ratio. Lower = cheaper valuation.         |
| `pbRatio`            | Price/Book ratio.                                        |
| `eps`                | Earnings Per Share.                                      |
| `roe`                | Return on Equity.                                        |
| `marketCap`          | Market capitalization.                                   |
| `beta`               | Volatility vs market. 1.0 = same as market, >1 = more volatile. |
| `recommendation`     | Analyst consensus: `strong_buy`, `buy`, `hold`, `sell`, `strong_sell`. |
| `analystCount`       | Number of analysts covering the stock.                   |
| `fiftyTwoWeekHigh`   | Highest price in the last 52 weeks.                      |
| `fiftyTwoWeekLow`    | Lowest price in the last 52 weeks.                       |
| `sector`             | Sector (e.g., Technology).                               |
| `industry`           | Industry (e.g., Consumer Electronics).                   |
| `earningsDate`       | Next quarterly earnings report date.                     |

## Currency conversion

Adding `?currency=PLN` converts monetary values to the target currency. The API uses the stock's native currency (from the `currency` response field) to automatically resolve the correct exchange rate.

```bash
curl http://localhost:8080/price/aapl?currency=PLN
curl http://localhost:8080/analysis/vow3.de?currency=USD
curl "http://localhost:8080/history/aapl?period=1y&currency=EUR"
```

Converted fields (`/price` and `/analysis`):
- `lastPrice`, `eps`, `marketCap`, `fiftyTwoWeekHigh`, `fiftyTwoWeekLow` — at the current exchange rate
- `gain` — at historical exchange rates for the respective dates
- `dividendYield` — at historical exchange rates on dividend payment dates
- `macd`, `bollingerBands`, `movingAverages`, `atr` — at historical exchange rates

Converted fields (`/history`):
- OHLCV prices and dividends — at historical exchange rates for each date
- All indicator values (when `indicators` param is used) — at historical exchange rates

Not converted (dimensionless): `rsi`, `peRatio`, `pbRatio`, `roe`, `beta`.

The `currency` field in the response reflects the target currency when conversion is active, or the stock's native currency otherwise.

## Error codes

| Code | Reason                                                    |
|------|-----------------------------------------------------------|
| 400  | Invalid symbol format or too many symbols (max 10)        |
| 404  | Unknown symbol or no history available                    |
| 422  | Insufficient conversion data for date range               |
| 502  | Yahoo Finance backend error                               |

## Development

### Prerequisites

- JDK 25+
- Python 3.9+

### Running tests

```bash
# Kotlin
./gradlew test

# Python
pip install -r backend-yfinance/requirements-dev.txt
pytest backend-yfinance/test_app.py
```

## Tech stack

| Component          | Technology              |
|--------------------|-------------------------|
| API server         | Kotlin, Ktor, Koin      |
| Data provider      | Python, Flask, yfinance |
| Technical analysis | TA4J                    |
| Serialization      | kotlinx.serialization   |
| Testing            | JUnit, MockK, pytest    |
| CI/CD              | GitHub Actions          |
| Deployment         | Docker, GHCR            |
