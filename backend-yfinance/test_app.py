import json
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from datetime import datetime, timezone
from unittest.mock import MagicMock, PropertyMock, patch

import numpy as np
import pandas as pd
import pytest

from app import (
    ApiError,
    BULKHEAD_MAX_ACTIVE_LOADERS,
    BULKHEAD_RETRY_AFTER_SECONDS,
    HistoricalPrice,
    RATE_LIMIT_RETRY_AFTER_SECONDS,
    SEARCH_CACHE_SECONDS,
    SymbolNotFoundError,
    UpstreamDataError,
    UpstreamRateLimitError,
    WAITRESS_THREADS,
    _classify_circuit_error,
    _history_cache,
    _loader_bulkhead,
    _metadata_cache,
    _metrics,
    _single_flight,
    _upstream_circuit,
    app,
    get_basic_info,
    get_history,
    run_server,
    search_tickers,
)
from bulkhead import LoaderBulkhead
from circuit_breaker import CircuitBreaker, CircuitOpenError, CircuitState
from memory_cache import ByteBoundedTTLCache, estimate_cache_entry_bytes
from metrics import AdapterMetrics
from yfinance.exceptions import YFPricesMissingError, YFRateLimitError, YFTzMissingError


@pytest.fixture
def client():
    app.config["TESTING"] = True
    with app.test_client() as c:
        yield c


@pytest.fixture(autouse=True)
def clear_data_cache():
    assert _single_flight.active_count == 0
    assert _loader_bulkhead.active_count == 0
    _upstream_circuit.reset()
    _metrics.reset()
    _history_cache.clear()
    _metadata_cache.clear()
    yield
    _history_cache.clear()
    _metadata_cache.clear()
    assert _single_flight.active_count == 0
    assert _loader_bulkhead.active_count == 0
    _upstream_circuit.reset()


@pytest.fixture
def mock_ticker():
    patcher = patch("app.yf.Ticker")
    mock_class = patcher.start()
    instance = mock_class.return_value

    _sentinel = object()

    def configure(history_df=None, dividends=None, info=_sentinel):
        if history_df is not None:
            instance.history.return_value = history_df
        type(instance).dividends = PropertyMock(
            return_value=dividends if dividends is not None else pd.Series(dtype=float)
        )
        if info is not _sentinel:
            type(instance).info = PropertyMock(return_value=info)
        return instance

    yield configure
    patcher.stop()


def _sample_history(date="2024-06-15"):
    index = pd.DatetimeIndex([pd.Timestamp(date)])
    return pd.DataFrame(
        {"Open": [100.0], "Close": [101.0], "Low": [99.0], "High": [102.0], "Volume": [1000]},
        index=index,
    )


def _standard_json(response):
    def reject_constant(value):
        pytest.fail(f"Non-standard JSON numeric constant emitted: {value}")

    return json.loads(response.get_data(as_text=True), parse_constant=reject_constant)


class FakeClock:
    def __init__(self):
        self.now = 0.0

    def __call__(self):
        return self.now

    def advance(self, seconds):
        self.now += seconds


class BlockingUpstream:
    def __init__(self, value=None, error=None):
        self.value = value
        self.error = error
        self.started = threading.Event()
        self.release = threading.Event()
        self._lock = threading.Lock()
        self.calls = 0

    def __call__(self, *_args, **_kwargs):
        with self._lock:
            self.calls += 1
        self.started.set()
        if not self.release.wait(timeout=5):
            raise TimeoutError("Test did not release blocked upstream call")
        if self.error is not None:
            raise self.error
        return self.value


class FatalLoaderError(BaseException):
    pass


def _raise_error(error):
    raise error


def _run_joined_calls(call, key, blocker, count=6, before_release=None):
    with ThreadPoolExecutor(max_workers=count) as executor:
        futures = [executor.submit(call) for _ in range(count)]
        try:
            assert blocker.started.wait(timeout=5)
            assert _single_flight.wait_for_participants(key, count, timeout=5)
            if before_release is not None:
                before_release()
        finally:
            blocker.release.set()
        return [future.result(timeout=5) for future in futures]


@pytest.mark.parametrize("value", [float("nan"), float("inf"), float("-inf")])
def test_flask_json_provider_rejects_non_standard_numbers(value):
    with pytest.raises(ValueError):
        app.json.dumps({"value": value})


def _split_adjusted_history(index=None):
    """A repaired 10:1 split fixture expressed entirely on the post-split share basis.

    Before repair, the first row would contain OHLC around 1,000, volume 10,000 and a
    dividend of 0.40. yfinance's split repair changes those to roughly 100, 100,000 and
    0.04 respectively, without applying dividend/total-return adjustment to prices.
    """
    if index is None:
        index = pd.DatetimeIndex([
            pd.Timestamp("2024-06-07"),
            pd.Timestamp("2024-06-10"),
            pd.Timestamp("2024-06-11"),
        ])
    return pd.DataFrame(
        {
            "Open": [99.0, 100.0, 101.0],
            "Close": [100.0, 101.0, 102.0],
            "Low": [98.0, 99.0, 100.0],
            "High": [101.0, 102.0, 103.0],
            "Volume": [100_000, 120_000, 110_000],
            "Dividends": [0.04, 0.0, 0.01],
            "Stock Splits": [0.0, 10.0, 0.0],
        },
        index=index,
    )


class TestHistoryEndpoint:
    def test_returns_prices(self, client, mock_ticker):
        index = pd.DatetimeIndex([pd.Timestamp("2024-06-15")])
        dividends = pd.Series([0.5], index=index)
        mock_ticker(history_df=_sample_history(), dividends=dividends)

        response = client.get("/history/AAPL/1y")

        assert response.status_code == 200
        data = response.get_json()
        assert len(data) == 1
        assert data[0]["date"] == "2024-06-15"
        assert data[0]["close"] == 101.0
        assert data[0]["dividend"] == 0.5

    def test_requests_history_with_actions(self, client, mock_ticker):
        ticker = mock_ticker(history_df=_sample_history())

        response = client.get("/history/AAPL/1y")

        assert response.status_code == 200
        ticker.history.assert_called_once_with(
            period="1y",
            interval="1d",
            auto_adjust=False,
            actions=True,
            repair=True,
            raise_errors=True,
        )

    def test_preserves_repaired_10_for_1_split_basis_without_double_adjustment(self, client, mock_ticker):
        ticker = mock_ticker(history_df=_split_adjusted_history())

        response = client.get("/history/NVDA/1y")

        assert response.status_code == 200
        data = response.get_json()
        assert data[0] == {
            "date": "2024-06-07",
            "open": 99.0,
            "close": 100.0,
            "low": 98.0,
            "high": 101.0,
            "volume": 100_000,
            "dividend": 0.04,
        }
        assert data[1]["splitRatio"] == 10.0
        assert data[1]["close"] == 101.0
        assert data[1]["volume"] == 120_000
        assert data[2]["dividend"] == 0.01
        ticker.history.assert_called_once_with(
            period="1y",
            interval="1d",
            auto_adjust=False,
            actions=True,
            repair=True,
            raise_errors=True,
        )

    def test_keeps_history_without_splits_unchanged(self, client, mock_ticker):
        history = _sample_history()
        history["Dividends"] = [0.25]
        history["Stock Splits"] = [0.0]
        mock_ticker(history_df=history)

        response = client.get("/history/AAPL/1y")

        assert response.status_code == 200
        assert response.get_json() == [{
            "date": "2024-06-15",
            "open": 100.0,
            "close": 101.0,
            "low": 99.0,
            "high": 102.0,
            "volume": 1000,
            "dividend": 0.25,
        }]

    def test_emits_repaired_subunit_history_in_major_currency_without_second_scale(self, client, mock_ticker):
        # yfinance repair metadata for this frame is currency=GBP, currencyRepaired=True even
        # though the separate info payload still reports GBp and a spot price around 1023.6.
        history = pd.DataFrame(
            {
                "Open": [10.20, 10.21],
                "Close": [10.212, 10.236],
                "Low": [10.19, 10.20],
                "High": [10.22, 10.24],
                "Volume": [1_000_000, 1_100_000],
                "Dividends": [0.04, 0.0],
                "Stock Splits": [0.0, 0.0],
            },
            index=pd.DatetimeIndex([pd.Timestamp("2024-06-14"), pd.Timestamp("2024-06-15")]),
        )
        ticker = mock_ticker(history_df=history)
        ticker.history_metadata = {"currency": "GBP", "currencyRepaired": True}

        response = client.get("/history/ISF.L/1y")

        assert response.status_code == 200
        data = response.get_json()
        assert [row["close"] for row in data] == [10.212, 10.236]
        assert data[0]["dividend"] == 0.04
        ticker.history.assert_called_once_with(
            period="1y",
            interval="1d",
            auto_adjust=False,
            actions=True,
            repair=True,
            raise_errors=True,
        )

    @pytest.mark.parametrize("interval", ["1wk", "1mo"])
    def test_weekly_and_monthly_keep_repaired_split_basis(self, client, mock_ticker, interval):
        mock_ticker(history_df=_split_adjusted_history())

        response = client.get(f"/history/NVDA/1y?interval={interval}")

        assert response.status_code == 200
        data = response.get_json()
        assert [row["close"] for row in data] == [100.0, 101.0, 102.0]
        assert [row["volume"] for row in data] == [100_000, 120_000, 110_000]
        assert data[1]["splitRatio"] == 10.0

    def test_intraday_keeps_provider_split_basis_and_event_timestamp(self, client, mock_ticker):
        index = pd.DatetimeIndex([
            pd.Timestamp("2024-06-07 15:55:00", tz="America/New_York"),
            pd.Timestamp("2024-06-10 09:30:00", tz="America/New_York"),
            pd.Timestamp("2024-06-10 09:35:00", tz="America/New_York"),
        ])
        ticker = mock_ticker(history_df=_split_adjusted_history(index))

        response = client.get("/history/NVDA/5d?interval=5m")

        assert response.status_code == 200
        data = response.get_json()
        assert [row["close"] for row in data] == [100.0, 101.0, 102.0]
        assert data[1]["splitRatio"] == 10.0
        assert data[1]["timestamp"] == int(index[1].tz_convert("UTC").timestamp())
        ticker.history.assert_called_once_with(
            period="5d",
            interval="5m",
            auto_adjust=False,
            actions=True,
            repair=True,
            raise_errors=True,
        )

    def test_uses_dividends_from_history_actions_column(self, client, mock_ticker):
        index = pd.DatetimeIndex([pd.Timestamp("2024-06-15", tz="America/New_York")])
        history = pd.DataFrame(
            {
                "Open": [100.0],
                "Close": [101.0],
                "Low": [99.0],
                "High": [102.0],
                "Volume": [1000],
                "Dividends": [0.27],
            },
            index=index,
        )
        mock_ticker(history_df=history, dividends=pd.Series(dtype=float))

        response = client.get("/history/AAPL/1y")

        assert response.status_code == 200
        assert response.get_json()[0]["dividend"] == 0.27

    def test_matches_dividend_fallback_by_calendar_date(self, client, mock_ticker):
        history_index = pd.DatetimeIndex([pd.Timestamp("2024-06-15", tz="America/New_York")])
        dividend_index = pd.DatetimeIndex([pd.Timestamp("2024-06-15", tz="UTC")])
        history = pd.DataFrame(
            {"Open": [100.0], "Close": [101.0], "Low": [99.0], "High": [102.0], "Volume": [1000]},
            index=history_index,
        )
        dividends = pd.Series([0.5], index=dividend_index)
        mock_ticker(history_df=history, dividends=dividends)

        response = client.get("/history/AAPL/1y")

        assert response.status_code == 200
        assert response.get_json()[0]["dividend"] == 0.5

    def test_zero_dividend_when_none_on_date(self, client, mock_ticker):
        mock_ticker(history_df=_sample_history())

        response = client.get("/history/AAPL/1y")

        assert response.status_code == 200
        assert response.get_json()[0]["dividend"] == 0.0

    def test_invalid_period_returns_400(self, client):
        response = client.get("/history/AAPL/invalid")

        assert response.status_code == 400
        assert "Invalid period" in response.get_json()["error"]

    def test_returns_empty_history_as_empty_list(self, client, mock_ticker):
        empty_df = pd.DataFrame(
            {"Open": [], "Close": [], "Low": [], "High": [], "Volume": []},
            index=pd.DatetimeIndex([]),
        )
        mock_ticker(history_df=empty_df)

        response = client.get("/history/AAPL/1y")

        assert response.status_code == 200
        assert response.get_json() == []

    def test_nan_volume_defaults_to_zero(self, client, mock_ticker):
        history = pd.DataFrame(
            {"Open": [100.0], "Close": [101.0], "Low": [99.0], "High": [102.0], "Volume": [float("nan")]},
            index=pd.DatetimeIndex([pd.Timestamp("2024-06-15")]),
        )
        mock_ticker(history_df=history)

        response = client.get("/history/AAPL/1y")

        assert response.status_code == 200
        assert response.get_json()[0]["volume"] == 0

    @pytest.mark.parametrize(
        ("field", "invalid_value"),
        [
            ("Open", float("nan")),
            ("Close", float("inf")),
            ("Low", float("nan")),
            ("High", float("-inf")),
        ],
    )
    def test_drops_rows_with_non_finite_required_ohlc(self, client, mock_ticker, field, invalid_value):
        history = _sample_history()
        history[field] = [invalid_value]
        mock_ticker(history_df=history)

        response = client.get("/history/AAPL/1y")

        assert response.status_code == 200
        assert _standard_json(response) == []

    def test_sanitizes_optional_history_values_and_numpy_scalars(self, client, mock_ticker):
        history = pd.DataFrame(
            {
                "Open": [np.float32(100.0), np.float32(101.0)],
                "Close": [np.float64(100.5), np.float64(101.5)],
                "Low": [np.float32(99.5), np.float32(100.5)],
                "High": [np.float64(101.0), np.float64(102.0)],
                "Volume": [float("inf"), np.int64(1_234)],
                "Dividends": [float("nan"), np.float32(0.25)],
                "Stock Splits": [float("inf"), np.float64(10.0)],
            },
            index=pd.DatetimeIndex([pd.Timestamp("2024-06-14"), pd.Timestamp("2024-06-15")]),
        )
        mock_ticker(history_df=history)

        response = client.get("/history/AAPL/1y")

        assert response.status_code == 200
        data = _standard_json(response)
        assert data[0]["volume"] == 0
        assert data[0]["dividend"] == 0.0
        assert "splitRatio" not in data[0]
        assert data[1]["volume"] == 1_234
        assert data[1]["dividend"] == 0.25
        assert data[1]["splitRatio"] == 10.0

    def test_yfinance_error_returns_502_instead_of_empty_history(self, client, mock_ticker):
        ticker = mock_ticker()
        ticker.history.side_effect = Exception("API secret details")

        response = client.get("/history/AAPL/1y")

        assert response.status_code == 502
        assert response.get_json()["error"] == "Failed to fetch data from upstream provider"

    def test_history_rate_limit_remains_retryable(self, client, mock_ticker):
        ticker = mock_ticker()
        ticker.history.side_effect = YFRateLimitError()

        response = client.get("/history/AAPL/1y")

        assert response.status_code == 429
        assert response.headers["Retry-After"] == str(RATE_LIMIT_RETRY_AFTER_SECONDS)
        assert response.get_json()["error"] == "Upstream provider rate limit exceeded"

    def test_dividend_fallback_rate_limit_remains_retryable(self, client, mock_ticker):
        ticker = mock_ticker(history_df=_sample_history())
        type(ticker).dividends = PropertyMock(side_effect=YFRateLimitError())

        response = client.get("/history/AAPL/1y")

        assert response.status_code == 429
        assert response.headers["Retry-After"] == str(RATE_LIMIT_RETRY_AFTER_SECONDS)

    def test_missing_timezone_is_the_only_history_exception_mapped_to_not_found(self, client, mock_ticker):
        ticker = mock_ticker()
        ticker.history.side_effect = YFTzMissingError("INVALID")

        response = client.get("/history/INVALID/1y")

        assert response.status_code == 404
        assert response.get_json()["error"] == "Symbol not found: INVALID"

    def test_prices_missing_for_known_symbol_is_legal_empty_history(self, client, mock_ticker):
        ticker = mock_ticker(info={"longName": "Apple Inc."})
        ticker.history.side_effect = YFPricesMissingError("AAPL", "for requested range")

        response = client.get("/history/AAPL/1y")

        assert response.status_code == 200
        assert response.get_json() == []

    def test_prices_missing_for_unidentified_symbol_maps_to_not_found(self, client, mock_ticker):
        ticker = mock_ticker(info={"trailingPegRatio": None})
        ticker.history.side_effect = YFPricesMissingError("INVALID", "for requested range")

        response = client.get("/history/INVALID/1y")

        assert response.status_code == 404

    def test_prices_missing_verification_failure_is_not_unknown_symbol(self, client, mock_ticker):
        ticker = mock_ticker()
        ticker.history.side_effect = YFPricesMissingError("AAPL", "for requested range")
        type(ticker).info = PropertyMock(side_effect=RuntimeError("HTTP Error 403: Forbidden"))

        response = client.get("/history/AAPL/1y")

        assert response.status_code == 502

    def test_upstream_403_is_failure_not_unknown_symbol(self, client, mock_ticker):
        ticker = mock_ticker()
        ticker.history.side_effect = RuntimeError("HTTP Error 403: Forbidden")

        response = client.get("/history/AAPL/1y")

        assert response.status_code == 502

    def test_intraday_includes_timestamp(self, client, mock_ticker):
        ts = pd.Timestamp("2024-06-15 09:30:00", tz="America/New_York")
        index = pd.DatetimeIndex([ts])
        df = pd.DataFrame(
            {"Open": [100.0], "Close": [101.0], "Low": [99.0], "High": [102.0], "Volume": [1000]},
            index=index,
        )
        mock_ticker(history_df=df)

        response = client.get("/history/AAPL/1d?interval=5m")

        assert response.status_code == 200
        data = response.get_json()
        assert len(data) == 1
        assert data[0]["date"] == "2024-06-15"
        assert "timestamp" in data[0]
        assert isinstance(data[0]["timestamp"], int)

    def test_intraday_timestamp_uses_exchange_local_time(self, client, mock_ticker):
        ts = pd.Timestamp("2024-06-15 09:30:00", tz="America/New_York")
        index = pd.DatetimeIndex([ts])
        df = pd.DataFrame(
            {"Open": [100.0], "Close": [101.0], "Low": [99.0], "High": [102.0], "Volume": [1000]},
            index=index,
        )
        mock_ticker(history_df=df)

        response = client.get("/history/AAPL/1d?interval=5m")

        data = response.get_json()
        expected = int(datetime(2024, 6, 15, 13, 30, tzinfo=timezone.utc).timestamp())
        assert data[0]["timestamp"] == expected

    def test_daily_excludes_timestamp(self, client, mock_ticker):
        mock_ticker(history_df=_sample_history())

        response = client.get("/history/AAPL/1y")

        assert response.status_code == 200
        data = response.get_json()
        assert "timestamp" not in data[0]

    def test_intraday_interval_accepted(self, client, mock_ticker):
        mock_ticker(history_df=_sample_history())

        for interval in ["1m", "5m", "15m", "30m", "1h"]:
            response = client.get(f"/history/AAPL/1d?interval={interval}")
            assert response.status_code == 200, f"interval {interval} should be valid"

    def test_intraday_cache_is_short(self, client, mock_ticker):
        mock_ticker(history_df=_sample_history())

        response = client.get("/history/AAPL/1d?interval=5m")

        assert response.headers["Cache-Control"] == "public, max-age=30"


class TestInfoEndpoint:
    def test_returns_basic_info(self, client, mock_ticker):
        mock_ticker(info={
            "longName": "Apple Inc.",
            "regularMarketPrice": 195.0,
            "currency": "USD",
            "forwardPE": 30.0,
            "priceToBook": 45.0,
            "trailingEps": 6.5,
            "returnOnEquity": 1.5,
            "marketCap": 3_000_000_000,
            "recommendationKey": "buy",
            "numberOfAnalystOpinions": 40,
            "fiftyTwoWeekHigh": 210.0,
            "fiftyTwoWeekLow": 150.0,
            "beta": 1.2,
            "sector": "Technology",
            "industry": "Consumer Electronics",
            "dividendRate": 1.0,
            "trailingAnnualDividendRate": 0.96,
            "previousClose": 193.5,
            "regularMarketTime": int(time.time()),
        })

        response = client.get("/info/AAPL")

        assert response.status_code == 200
        data = response.get_json()
        assert data["name"] == "Apple Inc."
        assert data["price"] == 195.0
        assert data["currency"] == "USD"
        assert data["pe_ratio"] == 30.0
        assert data["market_cap"] == 3_000_000_000
        assert data["recommendation"] == "buy"
        assert data["analyst_count"] == 40
        assert data["fifty_two_week_high"] == 210.0
        assert data["fifty_two_week_low"] == 150.0
        assert data["beta"] == 1.2
        assert data["sector"] == "Technology"
        assert data["industry"] == "Consumer Electronics"
        assert data["dividend_rate"] == 1.0
        assert data["trailing_annual_dividend_rate"] == 0.96
        assert data["previous_close"] == 193.5

    def test_reports_market_date_in_exchange_timezone(self, client, mock_ticker):
        market_time = int(datetime(2024, 6, 14, 23, 30, tzinfo=timezone.utc).timestamp())
        mock_ticker(info={
            "longName": "Tokyo listing",
            "regularMarketPrice": 100.0,
            "regularMarketTime": market_time,
            "exchangeTimezoneName": "Asia/Tokyo",
        })

        response = client.get("/info/TEST")

        assert response.status_code == 200
        assert response.get_json()["market_date"] == "2024-06-15"

    def test_returns_null_market_date_when_market_timestamp_is_invalid(self, client, mock_ticker):
        mock_ticker(info={
            "longName": "Test",
            "regularMarketTime": "not-a-timestamp",
        })

        response = client.get("/info/TEST")

        assert response.status_code == 200
        assert response.get_json()["market_date"] is None

    def test_previous_close_equals_price_when_market_not_open(self, client, mock_ticker):
        yesterday = int(time.time()) - 86400
        mock_ticker(info={
            "longName": "Test",
            "regularMarketPrice": 167.08,
            "previousClose": 168.14,
            "regularMarketTime": yesterday,
            "currency": "USD",
        })

        response = client.get("/info/TEST")

        assert response.status_code == 200
        data = response.get_json()
        assert data["previous_close"] == 167.08

    def test_falls_back_to_short_name(self, client, mock_ticker):
        mock_ticker(info={"shortName": "AAPL"})

        response = client.get("/info/AAPL")

        assert response.status_code == 200
        assert response.get_json()["name"] == "AAPL"

    def test_missing_fields_return_none(self, client, mock_ticker):
        mock_ticker(info={"longName": "Test Corp"})

        response = client.get("/info/TEST")

        assert response.status_code == 200
        data = response.get_json()
        assert data["name"] == "Test Corp"
        assert data["price"] is None
        assert data["currency"] is None
        assert data["pe_ratio"] is None
        assert data["eps"] is None
        assert data["recommendation"] is None
        assert data["sector"] is None
        assert data["beta"] is None

    def test_sanitizes_non_finite_info_fields_and_numpy_scalars(self, client, mock_ticker):
        mock_ticker(info={
            "longName": np.str_("NumPy Corp"),
            "regularMarketPrice": np.float64(float("nan")),
            "currentPrice": np.float32(195.5),
            "currency": np.str_("USD"),
            "forwardPE": np.float64(float("nan")),
            "priceToBook": np.float64(float("inf")),
            "trailingEps": np.float64(float("-inf")),
            "returnOnEquity": np.float32(0.25),
            "marketCap": np.int64(3_000_000_000),
            "recommendationKey": np.str_("buy"),
            "numberOfAnalystOpinions": np.int64(12),
            "fiftyTwoWeekHigh": np.float32(210.0),
            "fiftyTwoWeekLow": np.float64(150.0),
            "beta": np.float64(float("nan")),
            "sector": np.str_("Technology"),
            "industry": np.str_("Software"),
            "earningsDate": np.float64(float("nan")),
            "dividendRate": np.float32(1.25),
            "trailingAnnualDividendRate": np.float64(float("inf")),
            "previousClose": np.float64(193.5),
            "regularMarketTime": np.int64(time.time()),
        })

        response = client.get("/info/NUMPY")

        assert response.status_code == 200
        data = _standard_json(response)
        assert data["name"] == "NumPy Corp"
        assert data["price"] == 195.5
        assert data["currency"] == "USD"
        assert data["pe_ratio"] is None
        assert data["pb_ratio"] is None
        assert data["eps"] is None
        assert data["roe"] == 0.25
        assert data["market_cap"] == 3_000_000_000.0
        assert data["analyst_count"] == 12
        assert data["fifty_two_week_high"] == 210.0
        assert data["fifty_two_week_low"] == 150.0
        assert data["beta"] is None
        assert data["earnings_date"] is None
        assert data["dividend_rate"] == 1.25
        assert data["trailing_annual_dividend_rate"] is None
        assert data["previous_close"] == 193.5

    def test_none_info_returns_404(self, client, mock_ticker):
        mock_ticker(info=None)

        response = client.get("/info/INVALID")

        assert response.status_code == 404

    def test_empty_info_returns_404_for_missing_symbol(self, client, mock_ticker):
        mock_ticker(info={})

        response = client.get("/info/INVALID")

        assert response.status_code == 404

    def test_info_without_identity_fields_returns_404(self, client, mock_ticker):
        mock_ticker(info={"trailingPegRatio": None})

        response = client.get("/info/INVALID")

        assert response.status_code == 404

    def test_info_rate_limit_remains_retryable(self, client, mock_ticker):
        ticker = mock_ticker()
        type(ticker).info = PropertyMock(side_effect=YFRateLimitError())

        response = client.get("/info/AAPL")

        assert response.status_code == 429
        assert response.headers["Retry-After"] == str(RATE_LIMIT_RETRY_AFTER_SECONDS)

    def test_info_missing_timezone_maps_to_not_found(self, client, mock_ticker):
        ticker = mock_ticker()
        type(ticker).info = PropertyMock(side_effect=YFTzMissingError("INVALID"))

        response = client.get("/info/INVALID")

        assert response.status_code == 404

    def test_yfinance_error_returns_404(self, client, mock_ticker):
        ticker = mock_ticker()
        type(ticker).info = PropertyMock(side_effect=Exception("Internal details"))

        response = client.get("/info/AAPL")

        assert response.status_code == 502
        assert "Internal details" not in str(response.get_json())


class TestCacheHeaders:
    def test_info_cache_5_minutes(self, client, mock_ticker):
        mock_ticker(info={"longName": "Test"})

        response = client.get("/info/TEST")

        assert response.headers["Cache-Control"] == "public, max-age=300"

    def test_history_5y_cache_24_hours(self, client, mock_ticker):
        mock_ticker(history_df=_sample_history())

        response = client.get("/history/AAPL/5y")

        assert response.headers["Cache-Control"] == "public, max-age=86400"

    def test_history_1d_cache_2_minutes(self, client, mock_ticker):
        mock_ticker(history_df=_sample_history())

        response = client.get("/history/AAPL/1d")

        assert response.headers["Cache-Control"] == "public, max-age=120"

    def test_history_1y_cache_4_hours(self, client, mock_ticker):
        mock_ticker(history_df=_sample_history())

        response = client.get("/history/AAPL/1y")

        assert response.headers["Cache-Control"] == "public, max-age=14400"


class TestByteBoundedTTLCache:
    @staticmethod
    def sized_cache(max_bytes, max_entries, clock):
        return ByteBoundedTTLCache(
            max_bytes,
            max_entries,
            clock=clock,
            size_of=lambda _key, value: value[0],
        )

    def test_evicts_by_access_order_not_by_shortest_ttl(self):
        clock = FakeClock()
        cache = self.sized_cache(100, 2, clock)
        cache.set("short-lived", (10, "short"), ttl=5)
        cache.set("long-lived", (10, "long"), ttl=500)

        assert cache.get("short-lived") == (10, "short")
        cache.set("new", (10, "new"), ttl=100)

        assert cache.keys_lru_to_mru == ("short-lived", "new")
        assert cache.get("long-lived") is None

    def test_expires_entries_at_ttl_boundary_and_reclaims_budget(self):
        clock = FakeClock()
        cache = self.sized_cache(100, 10, clock)
        cache.set("quote", (25, "value"), ttl=5)

        clock.advance(5)

        assert cache.get("quote") is None
        assert len(cache) == 0
        assert cache.total_bytes == 0

    def test_evicts_lru_entries_until_byte_budget_is_met(self):
        clock = FakeClock()
        cache = self.sized_cache(25, 10, clock)
        cache.set("a", (10, "a"), ttl=100)
        cache.set("b", (10, "b"), ttl=100)
        cache.get("a")

        assert cache.set("c", (10, "c"), ttl=100)

        assert cache.keys_lru_to_mru == ("a", "c")
        assert cache.total_bytes == 20
        assert cache.get("b") is None

    def test_rejects_oversized_entry_and_removes_stale_value_for_same_key(self):
        payload = ["x" * 1024]
        entry_size = estimate_cache_entry_bytes("large", payload)
        cache = ByteBoundedTTLCache(entry_size - 1, 10)

        assert not cache.set("large", payload, ttl=100)
        assert cache.get("large") is None
        assert cache.total_bytes == 0

        sized_cache = self.sized_cache(20, 10, FakeClock())
        assert sized_cache.set("same", (10, "old"), ttl=100)
        assert not sized_cache.set("same", (21, "new"), ttl=100)
        assert sized_cache.get("same") is None

    def test_copies_mutable_containers_on_write_and_read(self):
        cache = ByteBoundedTTLCache(1_000, 10, size_of=lambda _key, _value: 10)
        source = ["original"]
        cache.set("list", source, ttl=100)
        source.append("source mutation")

        cached = cache.get("list")
        cached.append("consumer mutation")

        assert cache.get("list") == ["original"]

    def test_default_estimator_walks_history_dataclasses_without_json_serialization(self):
        price = HistoricalPrice(
            date="2024-06-15",
            open=100.0,
            close=101.0,
            low=99.0,
            high=102.0,
            volume=1_000,
            dividend=0.25,
        )
        second_price = replace(price, date="2024-06-16", close=102.0)
        with patch("json.dumps", side_effect=AssertionError("JSON serialization is not allowed")):
            one_price = estimate_cache_entry_bytes("history:AAPL", [price])
            two_prices = estimate_cache_entry_bytes("history:AAPL", [price, second_price])

        assert two_prices > one_price


class TestCircuitBreaker:
    @staticmethod
    def breaker(clock, threshold=4, window=30, open_seconds=30):
        return CircuitBreaker(
            failure_threshold=threshold,
            failure_window_seconds=window,
            open_seconds=open_seconds,
            forced_open_seconds=RATE_LIMIT_RETRY_AFTER_SECONDS,
            clock=clock,
        )

    @staticmethod
    def fail(circuit, error):
        with pytest.raises(type(error)):
            circuit.call(lambda: _raise_error(error), _classify_circuit_error)

    def test_opens_after_threshold_502_failures_and_reports_dynamic_retry_after(self):
        clock = FakeClock()
        circuit = self.breaker(clock)

        for _ in range(3):
            self.fail(circuit, UpstreamDataError())
        assert circuit.state == CircuitState.CLOSED
        assert circuit.failure_count == 3

        self.fail(circuit, UpstreamDataError())

        assert circuit.state == CircuitState.OPEN
        with pytest.raises(CircuitOpenError) as opened:
            circuit.call(
                lambda: pytest.fail("Open circuit called its loader"),
                _classify_circuit_error,
            )
        assert opened.value.retry_after_seconds == 30

        clock.advance(11)
        with pytest.raises(CircuitOpenError) as later:
            circuit.call(
                lambda: pytest.fail("Open circuit called its loader"),
                _classify_circuit_error,
            )
        assert later.value.retry_after_seconds == 19

    def test_rate_limit_forces_immediate_open_for_provider_retry_period(self):
        clock = FakeClock()
        circuit = self.breaker(clock)

        with pytest.raises(UpstreamRateLimitError) as original:
            circuit.call(lambda: _raise_error(UpstreamRateLimitError()), _classify_circuit_error)

        assert original.value.status_code == 429
        assert circuit.state == CircuitState.OPEN
        with pytest.raises(CircuitOpenError) as opened:
            circuit.call(
                lambda: pytest.fail("Open circuit called its loader"),
                _classify_circuit_error,
            )
        assert opened.value.retry_after_seconds == RATE_LIMIT_RETRY_AFTER_SECONDS

        clock.advance(17)
        with pytest.raises(CircuitOpenError) as later:
            circuit.call(
                lambda: pytest.fail("Open circuit called its loader"),
                _classify_circuit_error,
            )
        assert later.value.retry_after_seconds == RATE_LIMIT_RETRY_AFTER_SECONDS - 17

    def test_failure_window_expiry_and_healthy_result_reset_the_series(self):
        clock = FakeClock()
        circuit = self.breaker(clock)

        for _ in range(3):
            self.fail(circuit, UpstreamDataError())
        clock.advance(30)
        self.fail(circuit, UpstreamDataError())
        assert circuit.failure_count == 1

        self.fail(circuit, UpstreamDataError())
        assert circuit.call(lambda: "healthy", _classify_circuit_error) == "healthy"
        assert circuit.failure_count == 0

        self.fail(circuit, UpstreamDataError())
        self.fail(circuit, UpstreamDataError())
        self.fail(circuit, SymbolNotFoundError("INVALID"))
        assert circuit.failure_count == 0

        for _ in range(3):
            self.fail(circuit, UpstreamDataError())
        assert circuit.state == CircuitState.CLOSED

    def test_half_open_allows_one_concurrent_probe_and_success_closes(self):
        clock = FakeClock()
        circuit = self.breaker(clock, threshold=1)
        self.fail(circuit, UpstreamDataError())
        clock.advance(30)
        probe = BlockingUpstream(value="recovered")

        with ThreadPoolExecutor(max_workers=6) as executor:
            probe_future = executor.submit(circuit.call, probe, _classify_circuit_error)
            assert probe.started.wait(timeout=5)
            assert circuit.state == CircuitState.HALF_OPEN

            def rejected_call():
                try:
                    circuit.call(
                        lambda: pytest.fail("Second half-open probe ran"),
                        _classify_circuit_error,
                    )
                except CircuitOpenError as error:
                    return error.retry_after_seconds
                pytest.fail("Concurrent half-open call was not rejected")

            rejected = [executor.submit(rejected_call) for _ in range(5)]
            assert [future.result(timeout=5) for future in rejected] == [1] * 5
            probe.release.set()
            assert probe_future.result(timeout=5) == "recovered"

        assert probe.calls == 1
        assert circuit.state == CircuitState.CLOSED

    def test_verified_404_half_open_probe_closes_circuit_but_keeps_404(self):
        clock = FakeClock()
        circuit = self.breaker(clock, threshold=1)
        self.fail(circuit, UpstreamDataError())
        clock.advance(30)

        with pytest.raises(SymbolNotFoundError) as missing:
            circuit.call(
                lambda: _raise_error(SymbolNotFoundError("INVALID")),
                _classify_circuit_error,
            )

        assert missing.value.status_code == 404
        assert circuit.state == CircuitState.CLOSED

    @pytest.mark.parametrize(
        ("probe_error", "expected_retry_after"),
        [
            (UpstreamRateLimitError(), RATE_LIMIT_RETRY_AFTER_SECONDS),
            (UpstreamDataError(), 10),
        ],
    )
    def test_failed_half_open_probe_reopens_circuit(self, probe_error, expected_retry_after):
        clock = FakeClock()
        circuit = self.breaker(clock, threshold=1, open_seconds=10)
        self.fail(circuit, UpstreamDataError())
        clock.advance(10)

        self.fail(circuit, probe_error)

        assert circuit.state == CircuitState.OPEN
        with pytest.raises(CircuitOpenError) as opened:
            circuit.call(
                lambda: pytest.fail("Reopened circuit called loader"),
                _classify_circuit_error,
            )
        assert opened.value.retry_after_seconds == expected_retry_after

    @pytest.mark.parametrize("probe_error", [RuntimeError("neutral"), FatalLoaderError("fatal")])
    def test_base_exception_always_releases_half_open_probe(self, probe_error):
        clock = FakeClock()
        circuit = self.breaker(clock, threshold=1, open_seconds=10)
        self.fail(circuit, UpstreamDataError())
        clock.advance(10)

        self.fail(circuit, probe_error)

        assert circuit.state == CircuitState.OPEN
        with pytest.raises(CircuitOpenError) as opened:
            circuit.call(
                lambda: pytest.fail("Inconclusive probe left circuit usable"),
                _classify_circuit_error,
            )
        assert opened.value.retry_after_seconds == 10

    def test_stale_success_cannot_close_circuit_opened_by_newer_completions(self):
        clock = FakeClock()
        circuit = self.breaker(clock, threshold=2)
        stale_success = BlockingUpstream(value="late success")

        with ThreadPoolExecutor(max_workers=1) as executor:
            stale_future = executor.submit(
                circuit.call,
                stale_success,
                _classify_circuit_error,
            )
            assert stale_success.started.wait(timeout=5)

            self.fail(circuit, UpstreamDataError())
            self.fail(circuit, UpstreamDataError())
            opened_generation = circuit.generation
            assert circuit.state == CircuitState.OPEN

            stale_success.release.set()
            assert stale_future.result(timeout=5) == "late success"

        assert circuit.state == CircuitState.OPEN
        assert circuit.generation == opened_generation

    def test_stale_rate_limit_completion_restarts_the_forced_open_period(self):
        clock = FakeClock()
        circuit = self.breaker(clock, threshold=1)
        late_rate_limit = BlockingUpstream(error=UpstreamRateLimitError())

        with ThreadPoolExecutor(max_workers=1) as executor:
            late_future = executor.submit(
                circuit.call,
                late_rate_limit,
                _classify_circuit_error,
            )
            assert late_rate_limit.started.wait(timeout=5)

            self.fail(circuit, UpstreamDataError())
            clock.advance(20)
            late_rate_limit.release.set()
            with pytest.raises(UpstreamRateLimitError):
                late_future.result(timeout=5)

        with pytest.raises(CircuitOpenError) as opened:
            circuit.call(lambda: pytest.fail("Open circuit called loader"), _classify_circuit_error)
        assert opened.value.retry_after_seconds == RATE_LIMIT_RETRY_AFTER_SECONDS

    def test_reports_bounded_state_transition_reasons(self):
        clock = FakeClock()
        metrics = AdapterMetrics()
        circuit = CircuitBreaker(
            failure_threshold=1,
            failure_window_seconds=30,
            open_seconds=10,
            clock=clock,
            on_transition=metrics.record_circuit_transition,
        )

        self.fail(circuit, UpstreamDataError())
        clock.advance(10)
        assert circuit.call(lambda: "recovered", _classify_circuit_error) == "recovered"

        rendered = metrics.render()
        assert (
            'stock_analyst_yfinance_circuit_transitions_total'
            '{from_state="closed",to_state="open",reason="failure_threshold"} 1'
        ) in rendered
        assert (
            'stock_analyst_yfinance_circuit_transitions_total'
            '{from_state="open",to_state="half_open",reason="cooldown_elapsed"} 1'
        ) in rendered
        assert (
            'stock_analyst_yfinance_circuit_transitions_total'
            '{from_state="half_open",to_state="closed",reason="probe_success"} 1'
        ) in rendered


class TestSingleFlight:
    @pytest.mark.parametrize("symbol", ["AAPL", "GBPPLN=X"])
    def test_coalesces_identical_info_and_fx_loads(self, symbol):
        blocker = BlockingUpstream(value={"longName": symbol, "regularMarketPrice": 100.0})
        with patch("app.yf.Ticker") as ticker_class:
            ticker = ticker_class.return_value
            type(ticker).info = PropertyMock(side_effect=blocker)

            results = _run_joined_calls(
                lambda: get_basic_info(symbol),
                f"info:{symbol}",
                blocker,
            )

        assert blocker.calls == 1
        assert ticker_class.call_count == 1
        assert [result.name for result in results] == [symbol] * len(results)

    def test_coalesces_identical_history_loads(self):
        blocker = BlockingUpstream(value=_sample_history())
        with patch("app.yf.Ticker") as ticker_class:
            ticker = ticker_class.return_value
            ticker.history.side_effect = blocker
            type(ticker).dividends = PropertyMock(return_value=pd.Series(dtype=float))

            results = _run_joined_calls(
                lambda: get_history("AAPL", "1y"),
                "history:AAPL:1y:1d",
                blocker,
            )

        assert blocker.calls == 1
        assert ticker_class.call_count == 1
        assert all(len(result) == 1 for result in results)
        assert len({id(result) for result in results}) == len(results)

    def test_coalesces_identical_search_loads(self):
        search_result = MagicMock()
        search_result.quotes = [{
            "symbol": "AAPL",
            "shortname": "Apple Inc.",
            "exchange": "NMS",
            "quoteType": "EQUITY",
        }]
        blocker = BlockingUpstream(value=search_result)
        with patch("app.yf.Search", side_effect=blocker) as search:
            results = _run_joined_calls(
                lambda: search_tickers("apple"),
                "search:apple",
                blocker,
            )

        assert blocker.calls == 1
        assert search.call_count == 1
        assert [[item.symbol for item in result] for result in results] == [["AAPL"]] * len(results)

    @pytest.mark.parametrize(
        ("upstream_error", "expected_status", "expected_retry_after"),
        [
            (YFTzMissingError("INVALID"), 404, None),
            (YFRateLimitError(), 429, str(RATE_LIMIT_RETRY_AFTER_SECONDS)),
            (RuntimeError("upstream failed"), 502, None),
        ],
    )
    def test_shares_classified_failure_and_allows_recovery(
        self,
        upstream_error,
        expected_status,
        expected_retry_after,
    ):
        clock = FakeClock()
        circuit = CircuitBreaker(
            failure_threshold=4,
            failure_window_seconds=30,
            open_seconds=30,
            forced_open_seconds=RATE_LIMIT_RETRY_AFTER_SECONDS,
            clock=clock,
        )
        blocker = BlockingUpstream(error=upstream_error)

        def classified_outcome():
            try:
                get_basic_info("RECOVERY")
            except ApiError as error:
                return error.status_code, error.headers.get("Retry-After")
            pytest.fail("Expected classified upstream failure")

        with (
            patch("app._upstream_circuit", circuit),
            patch("app.yf.Ticker") as ticker_class,
        ):
            ticker = ticker_class.return_value
            type(ticker).info = PropertyMock(side_effect=blocker)

            outcomes = _run_joined_calls(
                classified_outcome,
                "info:RECOVERY",
                blocker,
            )
            assert outcomes == [(expected_status, expected_retry_after)] * len(outcomes)
            assert blocker.calls == 1
            assert _single_flight.active_count == 0
            if expected_status == 429:
                assert circuit.state == CircuitState.OPEN
                with pytest.raises(ApiError) as rejected:
                    get_basic_info("BLOCKED")
                assert rejected.value.status_code == 503
                assert rejected.value.headers["Retry-After"] == str(
                    RATE_LIMIT_RETRY_AFTER_SECONDS
                )
                assert ticker_class.call_count == 1
                clock.advance(RATE_LIMIT_RETRY_AFTER_SECONDS)
            elif expected_status == 502:
                assert circuit.state == CircuitState.CLOSED
                assert circuit.failure_count == 1
            else:
                assert circuit.state == CircuitState.CLOSED
                assert circuit.failure_count == 0

            blocker.error = None
            blocker.value = {"longName": "Recovered"}
            recovered = get_basic_info("RECOVERY")

        assert recovered.name == "Recovered"
        assert blocker.calls == 2
        assert ticker_class.call_count == 2
        assert circuit.state == CircuitState.CLOSED

    def test_different_keys_load_in_parallel_without_holding_registry_lock(self):
        symbols = ("AAPL", "MSFT")
        started = {symbol: threading.Event() for symbol in symbols}
        release = threading.Event()

        def ticker_for(symbol):
            ticker = MagicMock()

            def load_info():
                started[symbol].set()
                if not release.wait(timeout=5):
                    raise TimeoutError("Test did not release upstream calls")
                return {"longName": symbol}

            type(ticker).info = PropertyMock(side_effect=load_info)
            return ticker

        with patch("app.yf.Ticker", side_effect=ticker_for):
            with ThreadPoolExecutor(max_workers=2) as executor:
                futures = [executor.submit(get_basic_info, symbol) for symbol in symbols]
                try:
                    assert all(event.wait(timeout=5) for event in started.values())
                finally:
                    release.set()
                results = [future.result(timeout=5) for future in futures]

        assert [result.name for result in results] == list(symbols)

    def test_coalesces_active_load_when_completed_cache_is_disabled(self):
        blocker = BlockingUpstream(value={"longName": "No cache"})
        with (
            patch.object(_metadata_cache, "max_bytes", 0),
            patch("app.yf.Ticker") as ticker_class,
        ):
            ticker = ticker_class.return_value
            type(ticker).info = PropertyMock(side_effect=blocker)

            results = _run_joined_calls(
                lambda: get_basic_info("NOCACHE"),
                "info:NOCACHE",
                blocker,
            )
            assert [result.name for result in results] == ["No cache"] * len(results)
            assert blocker.calls == 1
            assert not _metadata_cache.contains("info:NOCACHE")

            sequential_retry = get_basic_info("NOCACHE")

        assert sequential_retry.name == "No cache"
        assert blocker.calls == 2
        assert ticker_class.call_count == 2


class TestCircuitBreakerIntegration:
    def test_is_global_across_operation_keys_and_recovers_with_one_probe(self):
        clock = FakeClock()
        circuit = CircuitBreaker(
            failure_threshold=2,
            failure_window_seconds=30,
            open_seconds=10,
            forced_open_seconds=RATE_LIMIT_RETRY_AFTER_SECONDS,
            clock=clock,
        )
        failing_ticker = MagicMock()
        type(failing_ticker).info = PropertyMock(side_effect=RuntimeError("info failed"))
        recovered_ticker = MagicMock()
        type(recovered_ticker).info = PropertyMock(return_value={"longName": "Recovered"})

        with (
            patch("app._upstream_circuit", circuit),
            patch("app.yf.Ticker", side_effect=[failing_ticker, recovered_ticker]) as ticker_class,
            patch("app.yf.Search", side_effect=RuntimeError("search failed")) as search,
        ):
            with pytest.raises(UpstreamDataError):
                get_basic_info("AAPL")
            with pytest.raises(UpstreamDataError):
                search_tickers("apple")

            assert circuit.state == CircuitState.OPEN
            assert ticker_class.call_count == 1
            assert search.call_count == 1

            with pytest.raises(ApiError) as rejected:
                get_basic_info("MSFT")
            assert rejected.value.status_code == 503
            assert rejected.value.headers["Retry-After"] == "10"
            assert ticker_class.call_count == 1

            with app.test_client() as test_client:
                health = test_client.get("/health")
            assert health.status_code == 200

            clock.advance(10)
            recovered = get_basic_info("RECOVERED")

        assert recovered.name == "Recovered"
        assert ticker_class.call_count == 2
        assert circuit.state == CircuitState.CLOSED
        assert circuit.failure_count == 0

    def test_completed_cache_bypasses_an_open_circuit(self):
        clock = FakeClock()
        circuit = CircuitBreaker(
            failure_threshold=1,
            failure_window_seconds=30,
            open_seconds=30,
            forced_open_seconds=RATE_LIMIT_RETRY_AFTER_SECONDS,
            clock=clock,
        )

        with (
            patch("app._upstream_circuit", circuit),
            patch("app.yf.Ticker") as ticker_class,
        ):
            ticker = ticker_class.return_value
            type(ticker).info = PropertyMock(return_value={"longName": "Cached"})
            populated = get_basic_info("AAPL")

            with pytest.raises(UpstreamDataError):
                circuit.call(
                    lambda: _raise_error(UpstreamDataError()),
                    _classify_circuit_error,
                )
            assert circuit.state == CircuitState.OPEN

            cached = get_basic_info("AAPL")

        assert populated.name == "Cached"
        assert cached.name == "Cached"
        assert ticker_class.call_count == 1
        assert circuit.state == CircuitState.OPEN


class TestLoaderBulkhead:
    def test_http_worker_pool_has_headroom_above_loader_limit(self):
        assert WAITRESS_THREADS > BULKHEAD_MAX_ACTIVE_LOADERS

    def test_server_uses_configured_worker_pool(self):
        with patch("waitress.serve") as serve:
            run_server()

        serve.assert_called_once_with(app, host="0.0.0.0", port=8081, threads=WAITRESS_THREADS)

    def test_limits_unique_keys_and_returns_retryable_503_while_health_stays_available(self):
        bulkhead = LoaderBulkhead(max_active=2, acquire_timeout_seconds=0.01)
        circuit = CircuitBreaker(
            failure_threshold=1,
            failure_window_seconds=30,
            open_seconds=30,
            forced_open_seconds=RATE_LIMIT_RETRY_AFTER_SECONDS,
        )
        info_blocker = BlockingUpstream(value={"longName": "AAPL"})
        history_blocker = BlockingUpstream(value=_sample_history())
        blockers = (info_blocker, history_blocker)

        def ticker_for(symbol):
            ticker = MagicMock()
            if symbol == "AAPL":
                type(ticker).info = PropertyMock(side_effect=info_blocker)
            elif symbol == "MSFT":
                ticker.history.side_effect = history_blocker
                type(ticker).dividends = PropertyMock(return_value=pd.Series(dtype=float))
            else:
                raise AssertionError(f"Unexpected ticker load: {symbol}")
            return ticker

        with (
            patch("app._loader_bulkhead", bulkhead),
            patch("app._upstream_circuit", circuit),
            patch("app.yf.Ticker", side_effect=ticker_for) as ticker_class,
            patch("app.yf.Search") as search,
            ThreadPoolExecutor(max_workers=2) as executor,
        ):
            futures = [
                executor.submit(get_basic_info, "AAPL"),
                executor.submit(get_history, "MSFT", "1y"),
            ]
            try:
                assert all(blocker.started.wait(timeout=5) for blocker in blockers)
                assert bulkhead.active_count == 2

                with app.test_client() as test_client:
                    health = test_client.get("/health")
                    saturated = test_client.get("/search/google")

                assert health.status_code == 200
                assert saturated.status_code == 503
                assert saturated.headers["Retry-After"] == str(BULKHEAD_RETRY_AFTER_SECONDS)
                assert saturated.get_json()["error"] == "Data backend is busy; retry shortly"
                assert search.call_count == 0
                assert ticker_class.call_count == 2
                assert circuit.state == CircuitState.CLOSED
                assert circuit.failure_count == 0
            finally:
                for blocker in blockers:
                    blocker.release.set()
            results = [future.result(timeout=5) for future in futures]

        assert results[0].name == "AAPL"
        assert len(results[1]) == 1
        assert bulkhead.active_count == 0
        assert circuit.state == CircuitState.CLOSED
        assert circuit.failure_count == 0

    def test_same_key_single_flight_uses_one_bulkhead_permit(self):
        bulkhead = LoaderBulkhead(max_active=1, acquire_timeout_seconds=0)
        blocker = BlockingUpstream(value={"longName": "Apple Inc."})
        observed_active_counts = []
        with (
            patch("app._loader_bulkhead", bulkhead),
            patch("app.yf.Ticker") as ticker_class,
        ):
            ticker = ticker_class.return_value
            type(ticker).info = PropertyMock(side_effect=blocker)

            results = _run_joined_calls(
                lambda: get_basic_info("AAPL"),
                "info:AAPL",
                blocker,
                before_release=lambda: observed_active_counts.append(bulkhead.active_count),
            )

        assert observed_active_counts == [1]
        assert blocker.calls == 1
        assert ticker_class.call_count == 1
        assert [result.name for result in results] == ["Apple Inc."] * len(results)
        assert bulkhead.active_count == 0

    @pytest.mark.parametrize(
        ("error", "expected_status"),
        [
            (None, None),
            (SymbolNotFoundError("INVALID"), 404),
            (UpstreamRateLimitError(), 429),
            (UpstreamDataError(), 502),
            (FatalLoaderError("fatal"), None),
        ],
    )
    def test_releases_permit_after_success_classified_errors_and_base_exception(
        self,
        error,
        expected_status,
    ):
        bulkhead = LoaderBulkhead(max_active=1, acquire_timeout_seconds=0)

        if error is None:
            assert bulkhead.call(lambda: "ok") == "ok"
        else:
            def fail():
                raise error

            with pytest.raises(type(error)) as raised:
                bulkhead.call(fail)
            if expected_status is not None:
                assert raised.value.status_code == expected_status

        assert bulkhead.active_count == 0
        assert bulkhead.call(lambda: "recovered") == "recovered"
        assert bulkhead.active_count == 0


class TestDataCache:
    def test_info_serves_from_cache(self, client, mock_ticker):
        mock_ticker(info={"longName": "Apple Inc."})

        client.get("/info/AAPL")
        client.get("/info/AAPL")

        assert _metadata_cache.contains("info:AAPL")
        assert not _history_cache.contains("info:AAPL")

    def test_history_serves_from_cache(self, client):
        with patch("app.yf.Ticker") as mock_class:
            instance = mock_class.return_value
            instance.history.return_value = _sample_history()
            type(instance).dividends = PropertyMock(return_value=pd.Series(dtype=float))

            client.get("/history/AAPL/1y")
            client.get("/history/AAPL/1y")

            assert mock_class.call_count == 1
            assert _history_cache.contains("history:AAPL:1y:1d")
            assert not _metadata_cache.contains("history:AAPL:1y:1d")

    def test_does_not_cache_errors(self, client):
        with patch("app.yf.Ticker") as mock_class:
            bad = MagicMock()
            good = MagicMock()
            type(bad).info = PropertyMock(side_effect=Exception("network error"))
            type(good).info = PropertyMock(return_value={"longName": "Apple Inc."})
            mock_class.side_effect = [bad, good]

            r1 = client.get("/info/AAPL")
            r2 = client.get("/info/AAPL")

            assert r1.status_code == 502
            assert r2.status_code == 200
            assert r2.get_json()["name"] == "Apple Inc."

    def test_cache_failure_does_not_mask_successful_upstream_data(self, client, mock_ticker):
        mock_ticker(info={"longName": "Apple Inc."})

        with (
            patch.object(_metadata_cache, "get", side_effect=RuntimeError("read failed")),
            patch.object(_metadata_cache, "set", side_effect=RuntimeError("write failed")),
        ):
            response = client.get("/info/AAPL")

        assert response.status_code == 200
        assert response.get_json()["name"] == "Apple Inc."


class TestHealthEndpoint:
    def test_health_returns_ok(self, client):
        response = client.get("/health")

        assert response.status_code == 200
        assert response.get_json() == {"status": "ok"}


class TestMetricsEndpoint:
    def test_exposes_bounded_http_cache_and_runtime_metrics(self, client, mock_ticker):
        mock_ticker(info={"longName": "Apple Inc."})

        assert client.get("/info/AAPL").status_code == 200
        assert client.get("/info/AAPL").status_code == 200
        assert client.get("/not-a-route").status_code == 404
        assert client.get("/health").status_code == 200
        response = client.get("/metrics")
        body = response.get_data(as_text=True)

        assert response.status_code == 200
        assert response.content_type == "text/plain; version=0.0.4; charset=utf-8"
        assert (
            'stock_analyst_yfinance_http_requests_total'
            '{method="GET",route="/info/{symbol}",status="200"} 2'
        ) in body
        assert (
            'stock_analyst_yfinance_http_requests_total'
            '{method="GET",route="unmatched",status="404"} 1'
        ) in body
        assert (
            'stock_analyst_yfinance_cache_lookups_total'
            '{cache="metadata",result="hit"} 1'
        ) in body
        assert 'stock_analyst_yfinance_cache_entries{cache="metadata"} 1' in body
        assert 'stock_analyst_yfinance_circuit_state{state="closed"} 1' in body
        assert "AAPL" not in body
        assert "/health" not in body


class TestSearchEndpoint:
    def test_returns_all_results(self, client):
        mock_quotes = [
            {"symbol": "AAPL", "shortname": "Apple Inc.", "exchange": "NMS", "quoteType": "EQUITY"},
            {"symbol": "AAPL240621C00200000", "shortname": "AAPL Call", "exchange": "OPR", "quoteType": "OPTION"},
            {"symbol": "SPY", "shortname": "SPDR S&P 500", "exchange": "PCX", "quoteType": "ETF"},
            {"symbol": "^GSPC", "shortname": "S&P 500", "exchange": "SNP", "quoteType": "INDEX"},
            {"symbol": "EURUSD=X", "shortname": "EUR/USD", "exchange": "CCY", "quoteType": "CURRENCY"},
        ]
        with patch("app.yf.Search") as mock_search:
            mock_search.return_value.quotes = mock_quotes

            response = client.get("/search/apple")

            assert response.status_code == 200
            data = response.get_json()
            assert len(data) == 5
            symbols = [r["symbol"] for r in data]
            assert "AAPL" in symbols
            assert "SPY" in symbols
            assert "^GSPC" in symbols
            assert "AAPL240621C00200000" in symbols
            assert "EURUSD=X" in symbols

    def test_returns_502_on_error(self, client):
        with patch("app.yf.Search", side_effect=Exception("API error")):
            response = client.get("/search/xyz")

            assert response.status_code == 502
            assert response.get_json()["error"] == "Failed to fetch data from upstream provider"

    def test_search_rate_limit_remains_retryable(self, client):
        with patch("app.yf.Search", side_effect=YFRateLimitError()):
            response = client.get("/search/apple")

        assert response.status_code == 429
        assert response.headers["Retry-After"] == str(RATE_LIMIT_RETRY_AFTER_SECONDS)

    def test_cache_header_set(self, client):
        with patch("app.yf.Search") as mock_search:
            mock_search.return_value.quotes = []

            response = client.get("/search/test")

            assert response.headers["Cache-Control"] == f"public, max-age={SEARCH_CACHE_SECONDS}"
