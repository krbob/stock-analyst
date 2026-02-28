# Stock Analyst

![GitHub Actions Workflow Status](https://img.shields.io/github/actions/workflow/status/krbob/stock-analyst/ci-build.yml)

Stock analysis API providing technical indicators and fundamental data. Built with Kotlin/Ktor and Python/Flask, data sourced from Yahoo Finance via [yfinance](https://github.com/ranaroussi/yfinance).

## Architecture

Two-service Docker setup:

- **Kotlin API** (port 7777) — Ktor server, business logic, technical analysis via [TA4J](https://github.com/ta4j/ta4j)
- **Python backend** (port 7776, internal) — Flask wrapper around yfinance, serves historical prices and stock info

```
Client → Kotlin API (:7777) → Python backend (:7776) → Yahoo Finance
```

### Project structure

```
stock-analyst/
├── core/           Shared utilities (serialization, DI, time provider)
├── domain/         Business logic, models, use cases
├── src/            Ktor application (routes, HTTP client, config)
└── backend/        Python/Flask data provider
```

## Installation

Requires Docker Compose.

```bash
git clone https://github.com/krbob/stock-analyst.git
cd stock-analyst
docker compose up -d
```

Or build from source:

```bash
docker compose -f docker-compose-build.yml up --build -d
```

## API

### `GET /analysis/{symbol}`

Returns full analysis for a stock symbol.

| Parameter    | Type   | Description                                   |
|--------------|--------|-----------------------------------------------|
| `symbol`     | path   | Stock ticker (e.g., `AAPL`, `MSFT`, `GC=F`)  |
| `conversion` | query  | Optional currency pair (e.g., `eur=x`)        |

```bash
curl http://localhost:7777/analysis/aapl
curl http://localhost:7777/analysis/aapl?conversion=eur=x
```

### Example response

```json
{
  "symbol": "aapl",
  "name": "Apple Inc.",
  "date": "2026-02-28",
  "lastPrice": 242.41,
  "gain": {
    "daily": 0.005,
    "weekly": 0.031,
    "monthly": 0.089,
    "quarterly": 0.102,
    "yearly": 0.283
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

### Gain

Percentage price change over a given period. A value of `0.05` means a 5% increase.

| Field       | Period      |
|-------------|-------------|
| `daily`     | 1 day       |
| `weekly`    | 1 week      |
| `monthly`   | 1 month     |
| `quarterly` | 3 months    |
| `yearly`    | 1 year      |

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

Adding `?conversion=eur=x` converts monetary values using the exchange rate from Yahoo Finance.

Converted fields:
- `lastPrice`, `eps`, `marketCap` — at the current exchange rate
- `gain` — at historical exchange rates for the respective dates
- `dividendYield` — at historical exchange rates on dividend payment dates

Not converted (dimensionless): `rsi`, `macd`, `bollingerBands`, `movingAverages`, `atr`, `peRatio`, `pbRatio`, `roe`, `beta`.

The `conversionName` field (e.g., `"EUR/USD"`) appears in the response when conversion is active.

### Currency symbols

The `conversion` parameter accepts a Yahoo Finance currency pair symbol:

| Example       | Currency pair |
|---------------|---------------|
| `eur=x`       | EUR/USD       |
| `gbp=x`       | GBP/USD       |
| `eurpln=x`    | EUR/PLN       |
| `usdpln=x`    | USD/PLN       |

## Error codes

| Code | Reason                                       |
|------|----------------------------------------------|
| 400  | Invalid symbol format                        |
| 404  | Unknown symbol or no history available        |
| 422  | Insufficient conversion data for date range   |
| 502  | Yahoo Finance backend error                   |

## Development

### Prerequisites

- JDK 25+
- Python 3.9+

### Running tests

```bash
# Kotlin
./gradlew test

# Python
pip install -r backend/requirements-dev.txt
pytest backend/test_app.py
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
