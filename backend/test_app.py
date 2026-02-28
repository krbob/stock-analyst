from unittest.mock import PropertyMock, patch

import pandas as pd
import pytest

from app import app, _ticker_cache


@pytest.fixture
def client():
    app.config["TESTING"] = True
    with app.test_client() as c:
        yield c


@pytest.fixture(autouse=True)
def clear_ticker_cache():
    _ticker_cache.clear()
    yield
    _ticker_cache.clear()


def _mock_ticker(history_df=None, dividends=None, info=None):
    patcher = patch("app.yf.Ticker")
    mock_class = patcher.start()
    instance = mock_class.return_value

    if history_df is not None:
        instance.history.return_value = history_df
    type(instance).dividends = PropertyMock(
        return_value=dividends if dividends is not None else pd.Series(dtype=float)
    )
    if info is not None:
        type(instance).info = PropertyMock(return_value=info)

    return patcher, instance


def _sample_history(date="2024-06-15"):
    index = pd.DatetimeIndex([pd.Timestamp(date)])
    return pd.DataFrame(
        {"Open": [100.0], "Close": [101.0], "Low": [99.0], "High": [102.0], "Volume": [1000]},
        index=index,
    )


class TestHistoryEndpoint:
    def test_returns_prices(self, client):
        index = pd.DatetimeIndex([pd.Timestamp("2024-06-15")])
        dividends = pd.Series([0.5], index=index)
        patcher, _ = _mock_ticker(history_df=_sample_history(), dividends=dividends)

        try:
            response = client.get("/history/AAPL/1y")
        finally:
            patcher.stop()

        assert response.status_code == 200
        data = response.get_json()
        assert len(data) == 1
        assert data[0]["date"] == "2024-06-15"
        assert data[0]["close"] == 101.0
        assert data[0]["dividend"] == 0.5

    def test_zero_dividend_when_none_on_date(self, client):
        patcher, _ = _mock_ticker(history_df=_sample_history())

        try:
            response = client.get("/history/AAPL/1y")
        finally:
            patcher.stop()

        assert response.status_code == 200
        assert response.get_json()[0]["dividend"] == 0.0

    def test_invalid_period_returns_400(self, client):
        response = client.get("/history/AAPL/invalid")

        assert response.status_code == 400
        assert "Invalid period" in response.get_json()["error"]

    def test_yfinance_error_returns_generic_500(self, client):
        patcher, ticker = _mock_ticker()
        ticker.history.side_effect = Exception("API secret details")

        try:
            response = client.get("/history/AAPL/1y")
        finally:
            patcher.stop()

        assert response.status_code == 500
        body = response.get_json()
        assert body["error"] == "An internal error occurred"
        assert "secret" not in str(body)


class TestInfoEndpoint:
    def test_returns_basic_info(self, client):
        info = {
            "longName": "Apple Inc.",
            "regularMarketPrice": 195.0,
            "forwardPE": 30.0,
            "priceToBook": 45.0,
            "trailingEps": 6.5,
            "returnOnEquity": 1.5,
            "marketCap": 3_000_000_000,
        }
        patcher, _ = _mock_ticker(info=info)

        try:
            response = client.get("/info/AAPL")
        finally:
            patcher.stop()

        assert response.status_code == 200
        data = response.get_json()
        assert data["name"] == "Apple Inc."
        assert data["price"] == 195.0
        assert data["pe_ratio"] == 30.0
        assert data["market_cap"] == 3_000_000_000

    def test_falls_back_to_short_name(self, client):
        patcher, _ = _mock_ticker(info={"shortName": "AAPL"})

        try:
            response = client.get("/info/AAPL")
        finally:
            patcher.stop()

        assert response.status_code == 200
        assert response.get_json()["name"] == "AAPL"

    def test_missing_fields_return_none(self, client):
        patcher, _ = _mock_ticker(info={"longName": "Test Corp"})

        try:
            response = client.get("/info/TEST")
        finally:
            patcher.stop()

        assert response.status_code == 200
        data = response.get_json()
        assert data["name"] == "Test Corp"
        assert data["price"] is None
        assert data["pe_ratio"] is None
        assert data["eps"] is None

    def test_yfinance_error_returns_generic_500(self, client):
        patcher = patch("app.yf.Ticker")
        mock_class = patcher.start()
        type(mock_class.return_value).info = PropertyMock(
            side_effect=Exception("Internal details")
        )

        try:
            response = client.get("/info/AAPL")
        finally:
            patcher.stop()

        assert response.status_code == 500
        body = response.get_json()
        assert body["error"] == "An internal error occurred"
        assert "Internal details" not in str(body)


class TestCacheHeaders:
    def test_info_cache_5_minutes(self, client):
        patcher, _ = _mock_ticker(info={"longName": "Test"})

        try:
            response = client.get("/info/TEST")
        finally:
            patcher.stop()

        assert response.headers["Cache-Control"] == "public, max-age=300"

    def test_history_5y_cache_24_hours(self, client):
        patcher, _ = _mock_ticker(history_df=_sample_history())

        try:
            response = client.get("/history/AAPL/5y")
        finally:
            patcher.stop()

        assert response.headers["Cache-Control"] == "public, max-age=86400"

    def test_history_1d_cache_2_minutes(self, client):
        patcher, _ = _mock_ticker(history_df=_sample_history())

        try:
            response = client.get("/history/AAPL/1d")
        finally:
            patcher.stop()

        assert response.headers["Cache-Control"] == "public, max-age=120"

    def test_history_1y_cache_4_hours(self, client):
        patcher, _ = _mock_ticker(history_df=_sample_history())

        try:
            response = client.get("/history/AAPL/1y")
        finally:
            patcher.stop()

        assert response.headers["Cache-Control"] == "public, max-age=14400"


class TestTickerCache:
    def test_reuses_ticker_within_ttl(self, client):
        patcher, _ = _mock_ticker(
            history_df=_sample_history(),
            info={"longName": "Apple Inc."},
        )

        try:
            client.get("/history/AAPL/1y")
            client.get("/info/AAPL")
        finally:
            patcher.stop()

        assert "AAPL" in _ticker_cache

    def test_different_symbols_get_different_tickers(self, client):
        patcher, _ = _mock_ticker(info={"longName": "Test"})

        try:
            client.get("/info/AAPL")
            client.get("/info/MSFT")
        finally:
            patcher.stop()

        assert "AAPL" in _ticker_cache
        assert "MSFT" in _ticker_cache
