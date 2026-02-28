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


@pytest.fixture
def mock_ticker():
    patcher = patch("app.yf.Ticker")
    mock_class = patcher.start()
    instance = mock_class.return_value

    def configure(history_df=None, dividends=None, info=None):
        if history_df is not None:
            instance.history.return_value = history_df
        type(instance).dividends = PropertyMock(
            return_value=dividends if dividends is not None else pd.Series(dtype=float)
        )
        if info is not None:
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

    def test_zero_dividend_when_none_on_date(self, client, mock_ticker):
        mock_ticker(history_df=_sample_history())

        response = client.get("/history/AAPL/1y")

        assert response.status_code == 200
        assert response.get_json()[0]["dividend"] == 0.0

    def test_invalid_period_returns_400(self, client):
        response = client.get("/history/AAPL/invalid")

        assert response.status_code == 400
        assert "Invalid period" in response.get_json()["error"]

    def test_yfinance_error_returns_generic_500(self, client, mock_ticker):
        ticker = mock_ticker()
        ticker.history.side_effect = Exception("API secret details")

        response = client.get("/history/AAPL/1y")

        assert response.status_code == 500
        body = response.get_json()
        assert body["error"] == "An internal error occurred"
        assert "secret" not in str(body)


class TestInfoEndpoint:
    def test_returns_basic_info(self, client, mock_ticker):
        mock_ticker(info={
            "longName": "Apple Inc.",
            "regularMarketPrice": 195.0,
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
        })

        response = client.get("/info/AAPL")

        assert response.status_code == 200
        data = response.get_json()
        assert data["name"] == "Apple Inc."
        assert data["price"] == 195.0
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
        assert data["pe_ratio"] is None
        assert data["eps"] is None
        assert data["recommendation"] is None
        assert data["sector"] is None
        assert data["beta"] is None

    def test_yfinance_error_returns_generic_500(self, client, mock_ticker):
        ticker = mock_ticker()
        type(ticker).info = PropertyMock(side_effect=Exception("Internal details"))

        response = client.get("/info/AAPL")

        assert response.status_code == 500
        body = response.get_json()
        assert body["error"] == "An internal error occurred"
        assert "Internal details" not in str(body)


class TestDividendsEndpoint:
    def test_returns_dividends(self, client, mock_ticker):
        index = pd.DatetimeIndex([pd.Timestamp("2024-01-15"), pd.Timestamp("2024-04-15")])
        dividends = pd.Series([0.24, 0.25], index=index)
        mock_ticker(dividends=dividends)

        response = client.get("/dividends/AAPL")

        assert response.status_code == 200
        data = response.get_json()
        assert len(data) == 2
        assert data[0]["date"] == "2024-01-15"
        assert data[0]["amount"] == 0.24
        assert data[1]["date"] == "2024-04-15"
        assert data[1]["amount"] == 0.25

    def test_returns_empty_for_no_dividends(self, client, mock_ticker):
        mock_ticker(dividends=pd.Series(dtype=float))

        response = client.get("/dividends/AAPL")

        assert response.status_code == 200
        assert response.get_json() == []

    def test_yfinance_error_returns_generic_500(self, client, mock_ticker):
        ticker = mock_ticker()
        type(ticker).dividends = PropertyMock(side_effect=Exception("API secret details"))

        response = client.get("/dividends/AAPL")

        assert response.status_code == 500
        body = response.get_json()
        assert body["error"] == "An internal error occurred"
        assert "secret" not in str(body)

    def test_cache_header_set(self, client, mock_ticker):
        mock_ticker(dividends=pd.Series(dtype=float))

        response = client.get("/dividends/AAPL")

        assert response.headers["Cache-Control"] == "public, max-age=3600"


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


class TestTickerCache:
    def test_reuses_ticker_within_ttl(self, client, mock_ticker):
        mock_ticker(
            history_df=_sample_history(),
            info={"longName": "Apple Inc."},
        )

        client.get("/history/AAPL/1y")
        client.get("/info/AAPL")

        assert "AAPL" in _ticker_cache

    def test_different_symbols_get_different_tickers(self, client, mock_ticker):
        mock_ticker(info={"longName": "Test"})

        client.get("/info/AAPL")
        client.get("/info/MSFT")

        assert "AAPL" in _ticker_cache
        assert "MSFT" in _ticker_cache
