import logging
import math
import os
import time
import traceback
from dataclasses import asdict, dataclass

import pandas as pd
import yfinance as yf
from bulkhead import BulkheadSaturatedError, LoaderBulkhead
from circuit_breaker import CircuitBreaker, CircuitOpenError, CircuitOutcome, CircuitState
from flask import Flask, Response, g, jsonify, request
from flask.json.provider import DefaultJSONProvider
from memory_cache import ByteBoundedTTLCache
from metrics import AdapterMetrics
from singleflight import SingleFlight
from werkzeug.exceptions import HTTPException
from yfinance.exceptions import YFPricesMissingError, YFRateLimitError, YFTzMissingError


class StandardJSONProvider(DefaultJSONProvider):
    """Emit RFC-compliant JSON and fail closed if sanitisation misses a non-finite number."""

    def dumps(self, obj, **kwargs):
        kwargs["allow_nan"] = False
        return super().dumps(obj, **kwargs)


class StandardJSONFlask(Flask):
    json_provider_class = StandardJSONProvider


app = StandardJSONFlask(__name__)

logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s")
logger = logging.getLogger(__name__)

VALID_PERIODS = {"1d", "5d", "1mo", "3mo", "6mo", "1y", "2y", "5y", "10y", "ytd", "max"}
VALID_INTERVALS = {"1m", "5m", "15m", "30m", "1h", "1d", "1wk", "1mo"}
INTRADAY_INTERVALS = {"1m", "2m", "5m", "15m", "30m", "60m", "90m", "1h"}
METRIC_METHODS = {"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"}
OPERATIONAL_PATHS = {"/health", "/metrics"}

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
RATE_LIMIT_RETRY_AFTER_SECONDS = 60
SYMBOL_IDENTITY_FIELDS = ("symbol", "shortName", "longName", "quoteType", "exchange")

DEFAULT_HISTORY_CACHE_MAX_BYTES = 64 * 1024 * 1024
DEFAULT_HISTORY_CACHE_MAX_ENTRIES = 512
DEFAULT_METADATA_CACHE_MAX_BYTES = 8 * 1024 * 1024
DEFAULT_METADATA_CACHE_MAX_ENTRIES = 2048
DEFAULT_BULKHEAD_MAX_ACTIVE_LOADERS = 4
DEFAULT_BULKHEAD_ACQUIRE_TIMEOUT_MS = 250
DEFAULT_BULKHEAD_RETRY_AFTER_SECONDS = 1
DEFAULT_WAITRESS_THREADS = 8
DEFAULT_CIRCUIT_BREAKER_FAILURE_THRESHOLD = 4
DEFAULT_CIRCUIT_BREAKER_FAILURE_WINDOW_SECONDS = 30
DEFAULT_CIRCUIT_BREAKER_OPEN_SECONDS = 30


def _non_negative_env_int(name, default):
    raw_value = os.getenv(name)
    if raw_value is None:
        return default
    try:
        value = int(raw_value)
    except ValueError as error:
        raise RuntimeError(f"{name} must be a non-negative integer") from error
    if value < 0:
        raise RuntimeError(f"{name} must be a non-negative integer")
    return value


def _positive_env_int(name, default):
    value = _non_negative_env_int(name, default)
    if value == 0:
        raise RuntimeError(f"{name} must be a positive integer")
    return value


HISTORY_CACHE_MAX_BYTES = _non_negative_env_int(
    "YFINANCE_HISTORY_CACHE_MAX_BYTES", DEFAULT_HISTORY_CACHE_MAX_BYTES
)
HISTORY_CACHE_MAX_ENTRIES = _non_negative_env_int(
    "YFINANCE_HISTORY_CACHE_MAX_ENTRIES", DEFAULT_HISTORY_CACHE_MAX_ENTRIES
)
METADATA_CACHE_MAX_BYTES = _non_negative_env_int(
    "YFINANCE_METADATA_CACHE_MAX_BYTES", DEFAULT_METADATA_CACHE_MAX_BYTES
)
METADATA_CACHE_MAX_ENTRIES = _non_negative_env_int(
    "YFINANCE_METADATA_CACHE_MAX_ENTRIES", DEFAULT_METADATA_CACHE_MAX_ENTRIES
)
BULKHEAD_MAX_ACTIVE_LOADERS = _positive_env_int(
    "YFINANCE_BULKHEAD_MAX_ACTIVE_LOADERS", DEFAULT_BULKHEAD_MAX_ACTIVE_LOADERS
)
BULKHEAD_ACQUIRE_TIMEOUT_MS = _non_negative_env_int(
    "YFINANCE_BULKHEAD_ACQUIRE_TIMEOUT_MS", DEFAULT_BULKHEAD_ACQUIRE_TIMEOUT_MS
)
BULKHEAD_RETRY_AFTER_SECONDS = _positive_env_int(
    "YFINANCE_BULKHEAD_RETRY_AFTER_SECONDS", DEFAULT_BULKHEAD_RETRY_AFTER_SECONDS
)
CIRCUIT_BREAKER_FAILURE_THRESHOLD = _positive_env_int(
    "YFINANCE_CIRCUIT_BREAKER_FAILURE_THRESHOLD",
    DEFAULT_CIRCUIT_BREAKER_FAILURE_THRESHOLD,
)
CIRCUIT_BREAKER_FAILURE_WINDOW_SECONDS = _positive_env_int(
    "YFINANCE_CIRCUIT_BREAKER_FAILURE_WINDOW_SECONDS",
    DEFAULT_CIRCUIT_BREAKER_FAILURE_WINDOW_SECONDS,
)
CIRCUIT_BREAKER_OPEN_SECONDS = _positive_env_int(
    "YFINANCE_CIRCUIT_BREAKER_OPEN_SECONDS", DEFAULT_CIRCUIT_BREAKER_OPEN_SECONDS
)
WAITRESS_THREADS = _positive_env_int("YFINANCE_WAITRESS_THREADS", DEFAULT_WAITRESS_THREADS)
if WAITRESS_THREADS <= BULKHEAD_MAX_ACTIVE_LOADERS:
    raise RuntimeError(
        "YFINANCE_WAITRESS_THREADS must be greater than YFINANCE_BULKHEAD_MAX_ACTIVE_LOADERS"
    )


_history_cache = ByteBoundedTTLCache(HISTORY_CACHE_MAX_BYTES, HISTORY_CACHE_MAX_ENTRIES)
_metadata_cache = ByteBoundedTTLCache(METADATA_CACHE_MAX_BYTES, METADATA_CACHE_MAX_ENTRIES)
_single_flight = SingleFlight()
_metrics = AdapterMetrics()
_loader_bulkhead = LoaderBulkhead(
    BULKHEAD_MAX_ACTIVE_LOADERS,
    acquire_timeout_seconds=BULKHEAD_ACQUIRE_TIMEOUT_MS / 1000,
)
_upstream_circuit = CircuitBreaker(
    failure_threshold=CIRCUIT_BREAKER_FAILURE_THRESHOLD,
    failure_window_seconds=CIRCUIT_BREAKER_FAILURE_WINDOW_SECONDS,
    open_seconds=CIRCUIT_BREAKER_OPEN_SECONDS,
    forced_open_seconds=RATE_LIMIT_RETRY_AFTER_SECONDS,
    on_transition=_metrics.record_circuit_transition,
)


class ApiError(Exception):
    def __init__(self, message, status_code, headers=None):
        super().__init__(message)
        self.message = message
        self.status_code = status_code
        self.headers = headers or {}


class UpstreamDataError(ApiError):
    def __init__(self, message="Failed to fetch data from upstream provider"):
        super().__init__(message, 502)


class UpstreamRateLimitError(ApiError):
    def __init__(self):
        super().__init__(
            "Upstream provider rate limit exceeded",
            429,
            headers={"Retry-After": str(RATE_LIMIT_RETRY_AFTER_SECONDS)},
        )


class BackendBusyError(ApiError):
    def __init__(self):
        super().__init__(
            "Data backend is busy; retry shortly",
            503,
            headers={"Retry-After": str(BULKHEAD_RETRY_AFTER_SECONDS)},
        )


class UpstreamCircuitOpenError(ApiError):
    def __init__(self, retry_after_seconds):
        super().__init__(
            "Upstream provider is temporarily unavailable",
            503,
            headers={"Retry-After": str(retry_after_seconds)},
        )


class SymbolNotFoundError(ApiError):
    def __init__(self, symbol):
        super().__init__(f"Symbol not found: {symbol}", 404)


def _raise_classified_upstream_error(error, symbol=None):
    if isinstance(error, YFRateLimitError):
        raise UpstreamRateLimitError() from error
    if symbol is not None and isinstance(error, YFTzMissingError):
        raise SymbolNotFoundError(symbol) from error
    raise UpstreamDataError() from error


def _classify_circuit_error(error):
    if isinstance(error, UpstreamRateLimitError):
        return CircuitOutcome.FORCE_OPEN
    if isinstance(error, UpstreamDataError):
        return CircuitOutcome.FAILURE
    if isinstance(error, SymbolNotFoundError):
        return CircuitOutcome.HEALTHY
    return CircuitOutcome.NEUTRAL


def _has_symbol_identity(info):
    return isinstance(info, dict) and any(info.get(field) for field in SYMBOL_IDENTITY_FIELDS)


def _empty_history_for_known_symbol(ticker, symbol):
    try:
        info = ticker.info
    except Exception as error:
        _raise_classified_upstream_error(error, symbol)
    if not _has_symbol_identity(info):
        raise SymbolNotFoundError(symbol)
    return []


def _cache_get(key):
    cache = _cache_for_key(key)
    cache_name = "history" if key.startswith("history:") else "metadata"
    try:
        value = cache.get(key)
    except Exception:
        _metrics.record_cache_lookup(cache_name, "error")
        logger.warning("Cache read failed for %s; treating it as a miss", key, exc_info=True)
        return None
    _metrics.record_cache_lookup(cache_name, "hit" if value is not None else "miss")
    return value


def _cache_set(key, value, ttl):
    try:
        return _cache_for_key(key).set(key, value, ttl)
    except Exception:
        logger.warning("Cache write failed for %s; returning uncached data", key, exc_info=True)
        return False


def _cache_for_key(key):
    return _history_cache if key.startswith("history:") else _metadata_cache


def _coalesced_cached_load(key, loader):
    cached = _cache_get(key)
    if cached is not None:
        return cached

    def load_after_second_cache_check():
        cached_after_join = _cache_get(key)
        if cached_after_join is not None:
            return cached_after_join
        try:
            return _loader_bulkhead.call(
                lambda: _upstream_circuit.call(loader, _classify_circuit_error)
            )
        except BulkheadSaturatedError as error:
            _metrics.record_bulkhead_rejection()
            raise BackendBusyError() from error
        except CircuitOpenError as error:
            _metrics.record_circuit_rejection()
            raise UpstreamCircuitOpenError(error.retry_after_seconds) from error

    return _single_flight.call(key, load_after_second_cache_check)


@dataclass(frozen=True)
class HistoricalPrice:
    date: str
    open: float
    close: float
    low: float
    high: float
    volume: int
    dividend: float
    timestamp: int | None = None
    splitRatio: float | None = None


@dataclass(frozen=True)
class SearchResult:
    symbol: str
    name: str
    exchange: str
    quoteType: str


@dataclass(frozen=True)
class BasicInfo:
    name: str | None
    price: float | None
    currency: str | None
    pe_ratio: float | None
    pb_ratio: float | None
    eps: float | None
    roe: float | None
    market_cap: float | None
    recommendation: str | None
    analyst_count: int | None
    fifty_two_week_high: float | None
    fifty_two_week_low: float | None
    beta: float | None
    sector: str | None
    industry: str | None
    earnings_date: str | None
    dividend_rate: float | None
    trailing_annual_dividend_rate: float | None
    previous_close: float | None
    market_date: str | None
    market_timestamp: int | None


def get_history(symbol, period, interval="1d"):
    key = f"history:{symbol}:{period}:{interval}"
    return _coalesced_cached_load(key, lambda: _load_history(symbol, period, interval, key))


def _load_history(symbol, period, interval, cache_key):
    ticker = yf.Ticker(symbol)
    try:
        # Yahoo normally returns OHLC, volume and dividends already expressed on the latest
        # split basis, even when dividend auto-adjustment is disabled. `repair=True` makes
        # yfinance use the Stock Splits actions to repair missing or double split adjustments.
        # The same repair pipeline standardises GBp/ZAc/ILA history (including dividends) to
        # GBP/ZAR/ILS. Downstream consumers must therefore scale only info-derived spot fields.
        # Keep `auto_adjust=False`: enabling it would additionally adjust for dividends and
        # would make the explicit dividend stream unsuitable for yield/total-return logic.
        history = ticker.history(
            period=period,
            interval=interval,
            auto_adjust=False,
            actions=True,
            repair=True,
            raise_errors=True,
        )
    except YFPricesMissingError:
        logger.info("No prices returned for %s (%s); verifying symbol identity", symbol, period)
        return _empty_history_for_known_symbol(ticker, symbol)
    except Exception as error:
        logger.warning("Failed to fetch history for %s (%s)", symbol, period, exc_info=True)
        _raise_classified_upstream_error(error, symbol)
    try:
        dividends = ticker.dividends
    except YFRateLimitError as error:
        logger.warning("Rate limited while fetching dividends for %s", symbol)
        _raise_classified_upstream_error(error, symbol)
    except Exception:
        logger.warning("Failed to fetch dividend fallback for %s", symbol, exc_info=True)
        dividends = pd.Series(dtype=float)

    intraday = interval in INTRADAY_INTERVALS
    dividends_by_date = _dividends_by_date(dividends)

    result = []
    for index, row in history.iterrows():
        date = index.strftime("%Y-%m-%d")
        open_price = _finite_float(row.get("Open"))
        close_price = _finite_float(row.get("Close"))
        low_price = _finite_float(row.get("Low"))
        high_price = _finite_float(row.get("High"))
        if any(value is None for value in (open_price, close_price, low_price, high_price)):
            logger.warning("Skipping history row with non-finite OHLC for %s on %s", symbol, date)
            continue
        timestamp = None
        if intraday:
            utc_index = index.tz_localize("UTC") if index.tzinfo is None else index.tz_convert("UTC")
            timestamp = int(utc_index.timestamp())
        price = HistoricalPrice(
            date=date,
            open=open_price,
            close=close_price,
            low=low_price,
            high=high_price,
            volume=_finite_int(row.get("Volume")),
            dividend=_resolve_dividend(row, date, dividends_by_date),
            timestamp=timestamp,
            splitRatio=_resolve_split_ratio(row),
        )
        result.append(price)

    if result:
        ttl = INTRADAY_CACHE_SECONDS if intraday else HISTORY_CACHE_SECONDS.get(period, 60)
        _cache_set(cache_key, result, ttl)
    return result


def _finite_float(value):
    try:
        result = float(value)
    except (OverflowError, TypeError, ValueError):
        return None
    return result if math.isfinite(result) else None


def _finite_int(value, default=0):
    value = _finite_float(value)
    return int(value) if value is not None else default


def _first_finite_float(*values):
    for value in values:
        result = _finite_float(value)
        if result is not None:
            return result
    return None


def _optional_string(value):
    if value is None:
        return None
    try:
        if bool(pd.isna(value)):
            return None
    except (TypeError, ValueError):
        pass
    try:
        return str(value)
    except (TypeError, ValueError):
        return None


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
    key = f"info:{symbol}"
    return _coalesced_cached_load(key, lambda: _load_basic_info(symbol, key))


def _load_basic_info(symbol, cache_key):
    try:
        info = yf.Ticker(symbol).info
    except Exception as error:
        logger.warning("Failed to fetch info for %s", symbol, exc_info=True)
        _raise_classified_upstream_error(error, symbol)
    if not _has_symbol_identity(info):
        return None

    result = BasicInfo(
        name=_optional_string(info.get("longName")) or _optional_string(info.get("shortName")),
        price=_first_finite_float(info.get("regularMarketPrice"), info.get("currentPrice")),
        currency=_optional_string(info.get("currency")),
        pe_ratio=_finite_float(info.get("forwardPE")),
        pb_ratio=_finite_float(info.get("priceToBook")),
        eps=_finite_float(info.get("trailingEps")),
        roe=_finite_float(info.get("returnOnEquity")),
        market_cap=_finite_float(info.get("marketCap")),
        recommendation=_optional_string(info.get("recommendationKey")),
        analyst_count=_finite_int(info.get("numberOfAnalystOpinions"), default=None),
        fifty_two_week_high=_finite_float(info.get("fiftyTwoWeekHigh")),
        fifty_two_week_low=_finite_float(info.get("fiftyTwoWeekLow")),
        beta=_finite_float(info.get("beta")),
        sector=_optional_string(info.get("sector")),
        industry=_optional_string(info.get("industry")),
        earnings_date=_resolve_earnings_date(info.get("earningsDate")),
        dividend_rate=_finite_float(info.get("dividendRate")),
        trailing_annual_dividend_rate=_finite_float(info.get("trailingAnnualDividendRate")),
        previous_close=_resolve_previous_close(info),
        market_date=_resolve_market_date(info),
        market_timestamp=_finite_int(info.get("regularMarketTime"), default=None),
    )

    _cache_set(cache_key, result, INFO_CACHE_SECONDS)
    return result


def _resolve_previous_close(info):
    """Return previous close, but if the market hasn't opened today,
    previous_close == price (both refer to the same last session)."""
    from datetime import datetime, timezone

    price = _first_finite_float(info.get("regularMarketPrice"), info.get("currentPrice"))
    prev = _first_finite_float(info.get("previousClose"), info.get("regularMarketPreviousClose"))
    market_time = _finite_float(info.get("regularMarketTime"))
    if market_time is not None and price is not None and prev is not None:
        try:
            market_date = datetime.fromtimestamp(market_time, tz=timezone.utc).date()
            today = datetime.now(tz=timezone.utc).date()
            if market_date < today:
                return price
        except (OverflowError, OSError, ValueError):
            pass
    return prev


def _resolve_earnings_date(earnings):
    from datetime import date, datetime

    if isinstance(earnings, (list, tuple)):
        earnings = earnings[0] if earnings else None
    if earnings is None:
        return None
    if isinstance(earnings, datetime):
        return earnings.date().isoformat()
    if isinstance(earnings, date):
        return earnings.isoformat()

    timestamp = _finite_float(earnings)
    if timestamp is not None:
        try:
            return datetime.fromtimestamp(timestamp).strftime("%Y-%m-%d")
        except (OverflowError, OSError, ValueError):
            return None
    return _optional_string(earnings)


def _resolve_market_date(info):
    market_time = _finite_float(info.get("regularMarketTime"))
    if market_time is None:
        return None

    from datetime import datetime, timezone
    from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

    exchange_timezone = _optional_string(info.get("exchangeTimezoneName"))
    try:
        timezone_info = ZoneInfo(exchange_timezone) if exchange_timezone else timezone.utc
    except (TypeError, ValueError, ZoneInfoNotFoundError):
        timezone_info = timezone.utc

    try:
        return datetime.fromtimestamp(market_time, tz=timezone_info).date().isoformat()
    except (OverflowError, OSError, TypeError, ValueError):
        return None


def search_tickers(query):
    key = f"search:{query}"
    return _coalesced_cached_load(key, lambda: _load_search_results(query, key))


def _load_search_results(query, cache_key):
    try:
        results = yf.Search(query, max_results=20).quotes
    except Exception as error:
        logger.warning("Failed to search for %s", query, exc_info=True)
        _raise_classified_upstream_error(error)

    filtered = [
        SearchResult(
            symbol=q["symbol"],
            name=q.get("shortname") or q.get("longname") or q["symbol"],
            exchange=q.get("exchange", ""),
            quoteType=q.get("quoteType", ""),
        )
        for q in results
    ]

    _cache_set(cache_key, filtered, SEARCH_CACHE_SECONDS)
    return filtered


@app.before_request
def start_timer():
    g.start_time = time.monotonic()


@app.after_request
def log_request_info(response):
    if hasattr(g, "start_time"):
        duration_seconds = max(0.0, time.monotonic() - g.start_time)
        if request.path not in OPERATIONAL_PATHS:
            route = _normalized_metric_route(request.path)
            method = request.method if request.method in METRIC_METHODS else "OTHER"
            _metrics.record_http(method, route, response.status_code, duration_seconds)
            logger.info(
                "%s %s %s %dms",
                request.method,
                request.path,
                response.status,
                int(duration_seconds * 1000),
            )
    return response


@app.errorhandler(Exception)
def handle_exception(e):
    logger.error("Exception: %s\n%s", e, traceback.format_exc())
    return jsonify({"error": "An internal error occurred"}), 500


@app.errorhandler(HTTPException)
def handle_http_exception(e):
    return jsonify({"error": e.name}), e.code


@app.errorhandler(ApiError)
def handle_api_error(e):
    response = jsonify({"error": e.message})
    for header, value in e.headers.items():
        response.headers[header] = value
    return response, e.status_code


@app.route("/health")
def health_endpoint():
    return jsonify({"status": "ok"})


@app.route("/metrics")
def metrics_endpoint():
    return Response(
        _metrics.render() + _render_runtime_gauges(),
        content_type="text/plain; version=0.0.4; charset=utf-8",
    )


def _normalized_metric_route(path):
    segments = path.strip("/").split("/")
    if len(segments) == 3 and segments[0] == "history":
        return "/history/{symbol}/{period}"
    if len(segments) == 2 and segments[0] == "info":
        return "/info/{symbol}"
    if len(segments) == 2 and segments[0] == "search":
        return "/search/{query}"
    return "unmatched"


def _render_runtime_gauges():
    circuit_state = _upstream_circuit.state
    lines = [
        "# HELP stock_analyst_yfinance_cache_entries Current cache entries.",
        "# TYPE stock_analyst_yfinance_cache_entries gauge",
        f'stock_analyst_yfinance_cache_entries{{cache="history"}} {len(_history_cache)}',
        f'stock_analyst_yfinance_cache_entries{{cache="metadata"}} {len(_metadata_cache)}',
        "# HELP stock_analyst_yfinance_cache_bytes Estimated retained cache bytes.",
        "# TYPE stock_analyst_yfinance_cache_bytes gauge",
        f'stock_analyst_yfinance_cache_bytes{{cache="history"}} {_history_cache.total_bytes}',
        f'stock_analyst_yfinance_cache_bytes{{cache="metadata"}} {_metadata_cache.total_bytes}',
        "# HELP stock_analyst_yfinance_bulkhead_active Active unique upstream loaders.",
        "# TYPE stock_analyst_yfinance_bulkhead_active gauge",
        f"stock_analyst_yfinance_bulkhead_active {_loader_bulkhead.active_count}",
        "# HELP stock_analyst_yfinance_bulkhead_limit Maximum active unique upstream loaders.",
        "# TYPE stock_analyst_yfinance_bulkhead_limit gauge",
        f"stock_analyst_yfinance_bulkhead_limit {_loader_bulkhead.max_active}",
        "# HELP stock_analyst_yfinance_singleflight_active Active coalesced keys.",
        "# TYPE stock_analyst_yfinance_singleflight_active gauge",
        f"stock_analyst_yfinance_singleflight_active {_single_flight.active_count}",
        "# HELP stock_analyst_yfinance_circuit_state Current circuit state as a one-hot gauge.",
        "# TYPE stock_analyst_yfinance_circuit_state gauge",
    ]
    lines.extend(
        f'stock_analyst_yfinance_circuit_state{{state="{state.value}"}} '
        f"{1 if state == circuit_state else 0}"
        for state in CircuitState
    )
    lines.extend(
        (
            "# HELP stock_analyst_yfinance_circuit_failures Current failures in the rolling window.",
            "# TYPE stock_analyst_yfinance_circuit_failures gauge",
            f"stock_analyst_yfinance_circuit_failures {_upstream_circuit.failure_count}",
        )
    )
    return "\n".join(lines) + "\n"


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


def run_server():
    from waitress import serve

    serve(app, host="0.0.0.0", port=8081, threads=WAITRESS_THREADS)


if __name__ == "__main__":
    run_server()
