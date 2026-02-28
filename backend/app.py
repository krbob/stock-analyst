import logging
import time
import traceback
from dataclasses import asdict, dataclass

import yfinance as yf
from flask import Flask, g, jsonify, request

app = Flask(__name__)

logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s")
logger = logging.getLogger(__name__)

VALID_PERIODS = {"1d", "5d", "1mo", "3mo", "6mo", "1y", "2y", "5y", "10y", "ytd", "max"}

HISTORY_CACHE_SECONDS = {
    "1d": 120,
    "5d": 300,
    "1mo": 3600,
    "3mo": 3600,
    "6mo": 7200,
    "1y": 14400,
    "2y": 43200,
    "5y": 86400,
    "10y": 86400,
    "ytd": 3600,
    "max": 86400,
}

INFO_CACHE_SECONDS = 300

_ticker_cache = {}
_TICKER_TTL = 300


def get_ticker(symbol):
    now = time.time()
    cached = _ticker_cache.get(symbol)
    if cached and (now - cached[1]) < _TICKER_TTL:
        return cached[0]
    ticker = yf.Ticker(symbol)
    _ticker_cache[symbol] = (ticker, now)
    return ticker


@dataclass
class HistoricalPrice:
    date: str
    open: float
    close: float
    low: float
    high: float
    volume: int
    dividend: float


@dataclass
class BasicInfo:
    name: str
    price: float
    pe_ratio: float
    pb_ratio: float
    eps: float
    roe: float
    market_cap: float
    recommendation: str
    analyst_count: int
    fifty_two_week_high: float
    fifty_two_week_low: float
    beta: float
    sector: str
    industry: str
    earnings_date: str
    dividend_rate: float
    trailing_annual_dividend_rate: float


def get_history(symbol, period):
    ticker = get_ticker(symbol)
    history = ticker.history(period=period, auto_adjust=False)
    dividends = ticker.dividends
    for index, row in history.iterrows():
        yield HistoricalPrice(
            date=index.strftime("%Y-%m-%d"),
            open=row["Open"],
            close=row["Close"],
            low=row["Low"],
            high=row["High"],
            volume=int(row["Volume"]),
            dividend=dividends.loc[index] if index in dividends.index else 0.0,
        )


def get_basic_info(symbol):
    info = get_ticker(symbol).info
    earnings = info.get("earningsDate")
    if isinstance(earnings, list) and earnings:
        earnings = earnings[0]
    earnings_str = None
    if earnings is not None:
        try:
            from datetime import datetime

            earnings_str = datetime.fromtimestamp(earnings).strftime("%Y-%m-%d")
        except (TypeError, ValueError, OSError):
            earnings_str = str(earnings) if earnings else None

    return BasicInfo(
        name=info.get("longName") or info.get("shortName"),
        price=info.get("regularMarketPrice") or info.get("currentPrice"),
        pe_ratio=info.get("forwardPE"),
        pb_ratio=info.get("priceToBook"),
        eps=info.get("trailingEps"),
        roe=info.get("returnOnEquity"),
        market_cap=info.get("marketCap"),
        recommendation=info.get("recommendationKey"),
        analyst_count=info.get("numberOfAnalystOpinions"),
        fifty_two_week_high=info.get("fiftyTwoWeekHigh"),
        fifty_two_week_low=info.get("fiftyTwoWeekLow"),
        beta=info.get("beta"),
        sector=info.get("sector"),
        industry=info.get("industry"),
        earnings_date=earnings_str,
        dividend_rate=info.get("dividendRate"),
        trailing_annual_dividend_rate=info.get("trailingAnnualDividendRate"),
    )


@app.before_request
def start_timer():
    g.start_time = time.time()


@app.after_request
def log_request_info(response):
    if hasattr(g, "start_time"):
        duration_ms = int((time.time() - g.start_time) * 1000)
        logger.info("%s %s %s %dms", request.method, request.path, response.status, duration_ms)
    return response


@app.errorhandler(Exception)
def handle_exception(e):
    logger.error("Exception: %s\n%s", e, traceback.format_exc())
    return jsonify({"error": "An internal error occurred"}), 500


@app.route("/history/<symbol>/<period>")
def history_endpoint(symbol, period):
    if period not in VALID_PERIODS:
        return jsonify({"error": f"Invalid period: {period}"}), 400

    response = jsonify([asdict(day) for day in get_history(symbol, period)])
    max_age = HISTORY_CACHE_SECONDS.get(period, 60)
    response.headers["Cache-Control"] = f"public, max-age={max_age}"
    return response


@app.route("/info/<symbol>")
def info_endpoint(symbol):
    response = jsonify(asdict(get_basic_info(symbol)))
    response.headers["Cache-Control"] = f"public, max-age={INFO_CACHE_SECONDS}"
    return response


if __name__ == "__main__":
    from waitress import serve

    serve(app, host="0.0.0.0", port=7776)
