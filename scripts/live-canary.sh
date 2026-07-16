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

echo "Live Yahoo canary passed for quote, bounded/max history, provenance and typed 404."
