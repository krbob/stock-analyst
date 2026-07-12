#!/usr/bin/env bash
set -euo pipefail

base_url="${STOCK_ANALYST_BASE_URL:-http://localhost:8080}"
request_id="live-yahoo-canary-${GITHUB_RUN_ID:-local}"

quote=$(curl --fail --silent --show-error \
  --retry 2 --retry-delay 2 --retry-connrefused \
  --header "X-Request-ID: ${request_id}" \
  "${base_url}/v1/quote/AAPL")

jq --exit-status '
  .symbol == "AAPL" and
  (.lastPrice | type == "number") and
  (.date | type == "string") and
  .provenance.source == "YAHOO_FINANCE" and
  .provenance.unitScale == 1 and
  .provenance.adjustment == "SPLIT_ADJUSTED" and
  (.provenance.status | IN("FRESH", "STALE", "PARTIAL")) and
  (.provenance.retrievedAt | type == "string")
' <<<"${quote}" >/dev/null

history=$(curl --fail --silent --show-error \
  --retry 2 --retry-delay 2 --retry-connrefused \
  "${base_url}/v1/history/AAPL?period=1mo")

jq --exit-status '
  .symbol == "AAPL" and
  (.prices | type == "array" and length > 0) and
  .adjustment == "SPLIT_ADJUSTED" and
  .provenance.source == "YAHOO_FINANCE" and
  (.provenance.coverageTo | type == "string")
' <<<"${history}" >/dev/null

missing_status=$(curl --silent --show-error \
  --output /tmp/stock-analyst-canary-missing.json \
  --write-out '%{http_code}' \
  "${base_url}/v1/quote/CODEX-NOT-A-REAL-SYMBOL-7D3F")
test "${missing_status}" = "404"
jq --exit-status \
  '.errorCode == "SYMBOL_NOT_FOUND" and .retryable == false and (.requestId | type == "string")' \
  /tmp/stock-analyst-canary-missing.json >/dev/null

echo "Live Yahoo canary passed for quote, history, provenance and typed 404."
