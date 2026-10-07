#!/usr/bin/env bash
set -euo pipefail

base_url="${STOCK_ANALYST_BASE_URL:-http://localhost:8080}"
request_id="live-yahoo-canary-${GITHUB_RUN_ID:-local}"

quote=$(curl --fail --silent --show-error \
  --retry 2 --retry-delay 2 --retry-connrefused \
  --header "X-Request-ID: ${request_id}" \
  "${base_url}/v1/quote/AAPL")

if ! jq --exit-status '
  .symbol == "AAPL" and
  (.lastPrice | type == "number") and
  (.date | type == "string") and
  .provenance.source == "YAHOO_FINANCE" and
  .provenance.unitScale == 1 and
  .provenance.adjustment == "SPLIT_ADJUSTED" and
  (.provenance.status | IN("FRESH", "STALE", "PARTIAL")) and
  (.provenance.retrievedAt | type == "string")
' <<<"${quote}" >/dev/null; then
  echo "Live Yahoo canary quote contract assertion failed." >&2
  exit 1
fi

history=$(curl --fail --silent --show-error \
  --retry 2 --retry-delay 2 --retry-connrefused \
  "${base_url}/v1/history/AAPL?period=1mo")

if ! jq --exit-status '
  .symbol == "AAPL" and
  (.prices | type == "array" and length > 0) and
  .adjustment == "split-adjusted" and
  .provenance.source == "YAHOO_FINANCE" and
  (.provenance.coverageTo | type == "string")
' <<<"${history}" >/dev/null; then
  echo "Live Yahoo canary bounded-history contract assertion failed." >&2
  exit 1
fi

max_history=$(curl --fail --silent --show-error \
  --retry 2 --retry-delay 2 --retry-connrefused \
  "${base_url}/v1/history/AAPL?period=max")

if ! jq --exit-status '
  .symbol == "AAPL" and
  .period == "max" and
  .interval == "1mo" and
  (.prices | type == "array" and length > 0) and
  .adjustment == "split-adjusted" and
  .provenance.source == "YAHOO_FINANCE" and
  (.provenance.coverageFrom | type == "string") and
  (.provenance.coverageTo | type == "string")
' <<<"${max_history}" >/dev/null; then
  echo "Live Yahoo canary max-history contract assertion failed." >&2
  exit 1
fi

subunit_quote=$(curl --fail --silent --show-error --retry 2 --retry-delay 2 \
  "${base_url}/v1/quote/VOD.L")
subunit_history=$(curl --fail --silent --show-error --retry 2 --retry-delay 2 \
  "${base_url}/v1/history/VOD.L?period=5d&interval=1d")
if ! jq --exit-status --argjson quote "${subunit_quote}" '
  .currency == "GBP" and $quote.currency == "GBP" and
  (.prices[-1].close / $quote.lastPrice | . > 0.5 and . < 2)
' <<<"${subunit_history}" >/dev/null; then
  echo "Live Yahoo canary found inconsistent spot/history currency units." >&2
  exit 1
fi

minute_history=$(curl --fail --silent --show-error --retry 2 --retry-delay 2 \
  "${base_url}/v1/history/TLT?period=5d&interval=1m&indicators=rsi&dividends=true")
if ! jq --exit-status '
  .interval == "1m" and .period == "5d" and
  (.prices | length > 14) and (.indicators.rsi | length > 0) and
  ([.prices[] | select(.dividend > 0)] | group_by(.date) | all(length == 1))
' <<<"${minute_history}" >/dev/null; then
  echo "Live Yahoo canary minute-history indicator/action assertion failed." >&2
  exit 1
fi

missing_status=$(curl --silent --show-error \
  --output /tmp/stock-analyst-canary-missing.json \
  --write-out '%{http_code}' \
  "${base_url}/v1/quote/CODEX-NOT-REAL-7D3F")
if test "${missing_status}" != "404"; then
  echo "Live Yahoo canary expected missing-symbol status 404, got ${missing_status}." >&2
  exit 1
fi
if ! jq --exit-status \
  '.errorCode == "SYMBOL_NOT_FOUND" and .retryable == false and (.requestId | type == "string")' \
  /tmp/stock-analyst-canary-missing.json >/dev/null; then
  echo "Live Yahoo canary missing-symbol error contract assertion failed." >&2
  exit 1
fi

echo "Live Yahoo canary passed for quote, history, currency units, minute indicators/actions, provenance and typed 404."
