import time
from datetime import datetime, timezone
from unittest.mock import MagicMock, PropertyMock, patch

import pandas as pd
import pytest

from app import app, _data_cache, RATE_LIMIT_RETRY_AFTER_SECONDS, SEARCH_CACHE_SECONDS
from yfinance.exceptions import YFPricesMissingError, YFRateLimitError, YFTzMissingError


@pytest.fixture
def client():
    app.config["TESTING"] = True
    with app.test_client() as c:
        yield c


@pytest.fixture(autouse=True)
def clear_data_cache():
    _data_cache.clear()
    yield
    _data_cache.clear()


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


class TestDataCache:
    def test_info_serves_from_cache(self, client, mock_ticker):
        mock_ticker(info={"longName": "Apple Inc."})

        client.get("/info/AAPL")
        client.get("/info/AAPL")

        assert "info:AAPL" in _data_cache

    def test_history_serves_from_cache(self, client):
        with patch("app.yf.Ticker") as mock_class:
            instance = mock_class.return_value
            instance.history.return_value = _sample_history()
            type(instance).dividends = PropertyMock(return_value=pd.Series(dtype=float))

            client.get("/history/AAPL/1y")
            client.get("/history/AAPL/1y")

            assert mock_class.call_count == 1

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

    def test_cache_expires(self, client):
        with patch("app.yf.Ticker") as mock_class:
            instance = mock_class.return_value
            type(instance).info = PropertyMock(return_value={"longName": "Apple Inc."})

            client.get("/info/AAPL")
            assert mock_class.call_count == 1

            # Backdate the cache entry to simulate expiry
            value, _expiry = _data_cache["info:AAPL"]
            _data_cache["info:AAPL"] = (value, time.time() - 1)

            client.get("/info/AAPL")
            assert mock_class.call_count == 2


class TestHealthEndpoint:
    def test_health_returns_ok(self, client):
        response = client.get("/health")

        assert response.status_code == 200
        assert response.get_json() == {"status": "ok"}


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
