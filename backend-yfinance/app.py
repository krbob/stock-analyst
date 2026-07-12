import logging
import math
import threading
import time
import traceback
from dataclasses import asdict, dataclass

import pandas as pd
import yfinance as yf
from flask import Flask, g, jsonify, request

app = Flask(__name__)

logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s")
logger = logging.getLogger(__name__)

VALID_PERIODS = {"1d", "5d", "1mo", "3mo", "6mo", "1y", "2y", "5y", "10y", "ytd", "max"}
VALID_INTERVALS = {"1m", "5m", "15m", "30m", "1h", "1d", "1wk", "1mo"}
INTRADAY_INTERVALS = {"1m", "2m", "5m", "15m", "30m", "60m", "90m", "1h"}

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

INTRADAY_CACHE_SECONDS = 30

INFO_CACHE_SECONDS = 300
SEARCH_CACHE_SECONDS = 300
MAX_CACHE_ENTRIES = 2048


_data_cache = {}
_data_cache_lock = threading.Lock()


class ApiError(Exception):
    def __init__(self, message, status_code):
        super().__init__(message)
        self.message = message
        self.status_code = status_code


class UpstreamDataError(ApiError):
    def __init__(self, message="Failed to fetch data from upstream provider"):
        super().__init__(message, 502)


def _cache_cleanup(now=None):
    current_time = now or time.time()
    expired_keys = [key for key, (_value, expiry) in _data_cache.items() if current_time >= expiry]
    for key in expired_keys:
        _data_cache.pop(key, None)

    overflow = len(_data_cache) - MAX_CACHE_ENTRIES
    if overflow > 0:
        for key, _entry in sorted(_data_cache.items(), key=lambda item: item[1][1])[:overflow]:
            _data_cache.pop(key, None)


def _cache_get(key):
    with _data_cache_lock:
        _cache_cleanup()
        entry = _data_cache.get(key)
        if entry and time.time() < entry[1]:
            return entry[0]
        if entry:
            _data_cache.pop(key, None)
        return None


def _cache_set(key, value, ttl):
    with _data_cache_lock:
        _cache_cleanup()
        _data_cache[key] = (value, time.time() + ttl)
        _cache_cleanup()


@dataclass
class HistoricalPrice:
    date: str
    open: float
    close: float
    low: float
    high: float
    volume: int
    dividend: float
    timestamp: int = None
    splitRatio: float = None


@dataclass
class SearchResult:
    symbol: str
    name: str
    exchange: str
    quoteType: str


@dataclass
class BasicInfo:
    name: str
    price: float
    currency: str
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
    previous_close: float
    market_date: str


def get_history(symbol, period, interval="1d"):
    cached = _cache_get(f"history:{symbol}:{period}:{interval}")
    if cached is not None:
        return cached

    ticker = yf.Ticker(symbol)
    try:
        # Yahoo normally returns OHLC, volume and dividends already expressed on the latest
        # split basis, even when dividend auto-adjustment is disabled. `repair=True` makes
        # yfinance use the Stock Splits actions to repair missing or double split adjustments.
        # Keep `auto_adjust=False`: enabling it would additionally adjust for dividends and
        # would make the explicit dividend stream unsuitable for yield/total-return logic.
        history = ticker.history(
            period=period,
            interval=interval,
            auto_adjust=False,
            actions=True,
            repair=True,
        )
    except Exception:
        logger.warning("Failed to fetch history for %s (%s)", symbol, period, exc_info=True)
        raise UpstreamDataError()
    try:
        dividends = ticker.dividends
    except Exception:
        dividends = pd.Series(dtype=float)

    intraday = interval in INTRADAY_INTERVALS
    dividends_by_date = _dividends_by_date(dividends)

    result = []
    for index, row in history.iterrows():
        if math.isnan(row["Close"]) or math.isnan(row["Open"]):
            continue
        date = index.strftime("%Y-%m-%d")
        price = HistoricalPrice(
            date=date,
            open=row["Open"],
            close=row["Close"],
            low=row["Low"],
            high=row["High"],
            volume=_finite_int(row.get("Volume")),
            dividend=_resolve_dividend(row, date, dividends_by_date),
            splitRatio=_resolve_split_ratio(row),
        )
        if intraday:
            utc_index = index.tz_localize("UTC") if index.tzinfo is None else index.tz_convert("UTC")
            price.timestamp = int(utc_index.timestamp())
        result.append(price)

    if result:
        ttl = INTRADAY_CACHE_SECONDS if intraday else HISTORY_CACHE_SECONDS.get(period, 60)
        _cache_set(f"history:{symbol}:{period}:{interval}", result, ttl)
    return result


def _finite_float(value):
    try:
        result = float(value)
    except (TypeError, ValueError):
        return None
    return result if math.isfinite(result) else None


def _finite_int(value, default=0):
    value = _finite_float(value)
    return int(value) if value is not None else default


def _date_key(index):
    try:
        return index.strftime("%Y-%m-%d")
    except AttributeError:
        return pd.Timestamp(index).strftime("%Y-%m-%d")


def _dividends_by_date(dividends):
    result = {}
    if dividends is None:
        return result
    try:
        items = dividends.items()
    except AttributeError:
        return result
    for index, amount in items:
        dividend = _finite_float(amount)
        if dividend is None:
            continue
        date = _date_key(index)
        result[date] = result.get(date, 0.0) + dividend
    return result


def _resolve_dividend(row, date, dividends_by_date):
    row_dividend = _finite_float(row.get("Dividends"))
    if row_dividend is not None and row_dividend != 0.0:
        return row_dividend
    fallback = dividends_by_date.get(date)
    if fallback is not None:
        return fallback
    return row_dividend if row_dividend is not None else 0.0


def _resolve_split_ratio(row):
    split_ratio = _finite_float(row.get("Stock Splits"))
    return split_ratio if split_ratio is not None and split_ratio > 0.0 else None


def get_basic_info(symbol):
    cached = _cache_get(f"info:{symbol}")
    if cached is not None:
        return cached

    try:
        info = yf.Ticker(symbol).info
    except Exception:
        logger.warning("Failed to fetch info for %s", symbol, exc_info=True)
        raise UpstreamDataError()
    if not info:
        return None

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

    result = BasicInfo(
        name=info.get("longName") or info.get("shortName"),
        price=info.get("regularMarketPrice") or info.get("currentPrice"),
        currency=info.get("currency"),
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
        previous_close=_resolve_previous_close(info),
        market_date=_resolve_market_date(info),
    )

    _cache_set(f"info:{symbol}", result, INFO_CACHE_SECONDS)
    return result


def _resolve_previous_close(info):
    """Return previous close, but if the market hasn't opened today,
    previous_close == price (both refer to the same last session)."""
    from datetime import datetime, timezone

    price = info.get("regularMarketPrice") or info.get("currentPrice")
    prev = info.get("previousClose") or info.get("regularMarketPreviousClose")
    market_time = info.get("regularMarketTime")
    if market_time and price and prev:
        market_date = datetime.fromtimestamp(market_time, tz=timezone.utc).date()
        today = datetime.now(tz=timezone.utc).date()
        if market_date < today:
            return price
    return prev


def _resolve_market_date(info):
    market_time = info.get("regularMarketTime")
    if market_time is None:
        return None

    from datetime import datetime, timezone
    from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

    exchange_timezone = info.get("exchangeTimezoneName")
    try:
        timezone_info = ZoneInfo(exchange_timezone) if exchange_timezone else timezone.utc
    except (TypeError, ValueError, ZoneInfoNotFoundError):
        timezone_info = timezone.utc

    try:
        return datetime.fromtimestamp(market_time, tz=timezone_info).date().isoformat()
    except (OverflowError, OSError, TypeError, ValueError):
        return None


def search_tickers(query):
    cached = _cache_get(f"search:{query}")
    if cached is not None:
        return cached

    try:
        results = yf.Search(query, max_results=20).quotes
    except Exception:
        logger.warning("Failed to search for %s", query, exc_info=True)
        raise UpstreamDataError()

    filtered = [
        SearchResult(
            symbol=q["symbol"],
            name=q.get("shortname") or q.get("longname") or q["symbol"],
            exchange=q.get("exchange", ""),
            quoteType=q.get("quoteType", ""),
        )
        for q in results
    ]

    _cache_set(f"search:{query}", filtered, SEARCH_CACHE_SECONDS)
    return filtered


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


@app.errorhandler(ApiError)
def handle_api_error(e):
    return jsonify({"error": e.message}), e.status_code


@app.route("/health")
def health_endpoint():
    return jsonify({"status": "ok"})


def _serialize_price(price):
    d = asdict(price)
    return {k: v for k, v in d.items() if v is not None}


@app.route("/history/<symbol>/<period>")
def history_endpoint(symbol, period):
    if period not in VALID_PERIODS:
        return jsonify({"error": f"Invalid period: {period}"}), 400

    interval = request.args.get("interval", "1d")
    if interval not in VALID_INTERVALS:
        return jsonify({"error": f"Invalid interval: {interval}. Valid values: {', '.join(sorted(VALID_INTERVALS))}"}), 400

    result = get_history(symbol, period, interval)
    response = jsonify([_serialize_price(p) for p in result])
    intraday = interval in INTRADAY_INTERVALS
    max_age = INTRADAY_CACHE_SECONDS if intraday else HISTORY_CACHE_SECONDS.get(period, 60)
    response.headers["Cache-Control"] = f"public, max-age={max_age}"
    return response


@app.route("/info/<symbol>")
def info_endpoint(symbol):
    result = get_basic_info(symbol)
    if result is None:
        return jsonify({"error": f"Symbol not found: {symbol}"}), 404
    response = jsonify(asdict(result))
    response.headers["Cache-Control"] = f"public, max-age={INFO_CACHE_SECONDS}"
    return response


@app.route("/search/<query>")
def search_endpoint(query):
    data = search_tickers(query)
    response = jsonify([asdict(r) for r in data])
    response.headers["Cache-Control"] = f"public, max-age={SEARCH_CACHE_SECONDS}"
    return response


if __name__ == "__main__":
    from waitress import serve

    serve(app, host="0.0.0.0", port=8081)
