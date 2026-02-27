from unittest.mock import PropertyMock, patch

import pandas as pd
import pytest

from app import app


@pytest.fixture
def client():
    app.config["TESTING"] = True
    with app.test_client() as c:
        yield c


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


class TestHistoryEndpoint:
    def test_returns_prices(self, client):
        index = pd.DatetimeIndex([pd.Timestamp("2024-06-15")])
        history_df = pd.DataFrame(
            {"Open": [100.0], "Close": [101.0], "Low": [99.0], "High": [102.0], "Volume": [1000]},
            index=index,
        )
        dividends = pd.Series([0.5], index=index)
        patcher, _ = _mock_ticker(history_df=history_df, dividends=dividends)

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
        index = pd.DatetimeIndex([pd.Timestamp("2024-06-15")])
        history_df = pd.DataFrame(
            {"Open": [100.0], "Close": [101.0], "Low": [99.0], "High": [102.0], "Volume": [1000]},
            index=index,
        )
        patcher, _ = _mock_ticker(history_df=history_df)

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


class TestResponseHeaders:
    def test_cache_control_is_set(self, client):
        patcher, _ = _mock_ticker(info={"longName": "Test"})

        try:
            response = client.get("/info/TEST")
        finally:
            patcher.stop()

        assert response.headers["Cache-Control"] == "public, max-age=60"
