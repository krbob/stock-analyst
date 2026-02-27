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
    pe_ratio: float
    pb_ratio: float
    eps: float
    roe: float
    market_cap: float


def get_history(symbol, period):
    ticker = yf.Ticker(symbol)
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
    info = yf.Ticker(symbol).info
    return BasicInfo(
        name=info.get("longName") or info.get("shortName"),
        pe_ratio=info.get("forwardPE"),
        pb_ratio=info.get("priceToBook"),
        eps=info.get("trailingEps"),
        roe=info.get("returnOnEquity"),
        market_cap=info.get("marketCap"),
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


@app.after_request
def set_cache_header(response):
    response.headers["Cache-Control"] = "public, max-age=60"
    return response


@app.errorhandler(Exception)
def handle_exception(e):
    logger.error("Exception: %s\n%s", e, traceback.format_exc())
    return jsonify({"error": "An internal error occurred"}), 500


@app.route("/history/<symbol>/<period>")
def history_endpoint(symbol, period):
    if period not in VALID_PERIODS:
        return jsonify({"error": f"Invalid period: {period}"}), 400

    return jsonify([asdict(day) for day in get_history(symbol, period)])


@app.route("/info/<symbol>")
def info_endpoint(symbol):
    return jsonify(asdict(get_basic_info(symbol)))


if __name__ == "__main__":
    from waitress import serve

    serve(app, host="0.0.0.0", port=7776)
