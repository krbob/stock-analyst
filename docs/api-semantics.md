# API semantics

This document covers behavior that is easy to misinterpret from field names alone.
The bundled
[OpenAPI document](../src/main/resources/openapi/stock-analyst-v1.json) remains the
source of truth for paths, parameters, schemas and response codes.

## Versioning and compatibility aliases

All new consumers must call the `/v1` routes. The equivalent unversioned routes still
exist as temporary compatibility aliases, but they are not a second contract and
should not be used by new integrations.

Operational routes such as `/healthz`, `/readyz`, `/metrics` and
`/openapi/v1.json` are intentionally not placed under `/v1`.

## Quote dates and terminal values

The `date` returned by `/v1/quote/{stock}` is the effective market date of
`lastPrice`, not the API server's current calendar date. On weekends and market
holidays it normally remains anchored to the latest applicable market session.

The terminal point for every gain is the same effective `lastPrice`. When FX
conversion is requested, its terminal conversion rate is also shared. Historical
comparison points use the applicable historical FX rate instead of applying today's
rate to the entire series.

If the history fetched for a quote does not yet contain the effective spot session,
the service adds that point only to an immutable calculation snapshot. It does not
mutate the adapter cache.

## Provenance and freshness

Quote, history and latest-indicator responses contain a required `provenance`
object. Consumers should render this metadata as supporting context and must not
infer freshness from the API clock or from the final visible point of a chart.

- `retrievedAt` is when Stock Analyst assembled the response.
- `marketTimestamp` and `marketDate` identify the effective upstream observation.
- `coverageFrom` and `coverageTo` describe the returned market-data range. For a
  history response they exclude indicator warmup bars even though those earlier bars
  participate in the calculation. FX coverage can make the returned range narrower
  than the requested instrument range.
- `currency`, `unitScale` and `adjustment` make the value basis explicit.
- `status` is `FRESH`, `STALE` or `PARTIAL` for current single-instrument
  responses. `ERROR` is reserved by the shared model for future batch semantics.

Quote provenance additionally separates current-price freshness from derived
analytics:

- `priceStatus` reports whether `lastPrice` is fresh, independently of historical
  gain coverage;
- `analyticsStatus` is `COMPLETE`, `PARTIAL` or `UNAVAILABLE`;
- `analyticsLimitations` contains stable response paths for missing calculations,
  for example `gain.fiveYear`.

These quote-only fields are additive and optional so older stored responses remain
readable. New quote responses always populate them. The legacy `status` retains its
aggregate behavior for existing consumers: incomplete analytics still make it
`PARTIAL`, even when `priceStatus` is `FRESH`.

Legacy `PARTIAL` takes precedence when a calculation cannot be fully populated. Otherwise,
freshness is evaluated against the cadence of the returned data:

| Cadence | Maximum expected age |
|---|---:|
| Intraday or daily | 4 days |
| Weekly | 10 days |
| Monthly | 40 days |

For a live response, an upstream market timestamp takes precedence over its calendar
date. For an exact `from`/`to` history window whose `to` date is before today,
freshness is assessed relative to the requested `to`. A complete archival response
therefore does not become stale merely because it is retrieved later.

## History ranges and intervals

Without an explicit interval, the service uses:

- weekly bars for `5y` and `10y`;
- monthly bars for `max`;
- daily bars for the remaining periods.

`from` and `to` form an inclusive exact range. They must be supplied together in
`YYYY-MM-DD` form and `from` must not be later than `to`. The server selects a Yahoo
period large enough to cover the range and then trims the returned prices and
indicators. Consequently:

- `requestedFrom` and `requestedTo` echo the requested range;
- `period` reports the internal fetch period, not necessarily a duration implied by
  the exact range;
- the ordinary `period` parameter still determines the default interval when no
  explicit `interval` is supplied.

When indicators are requested, the service fetches additional bars for warmup and
removes them from the displayed range. For example, an SMA 200 needs up to 200 prior
bars.

Intraday prices and indicator points contain a UTC epoch-second `timestamp` in
addition to the calendar `date`. The adapter retains intraday results for 30 seconds;
this is an internal server-side cache and not a public HTTP cache guarantee.

## Dividends and split adjustment

Every historical price object has a `dividend` field. Daily histories use the
dividend data supplied by yfinance. Weekly and monthly yfinance bars can already
contain aggregated dividends, and Stock Analyst preserves them even when the
`dividends` query parameter is omitted or `false`.

Setting `dividends=true` for weekly or monthly data enables an additional daily-data
fallback. If the aggregate bars contain no dividends, daily payments are summed into
the bar whose `(previousBar.date, currentBar.date]` window contains the payment. With
period-start timestamps this can be the following visible bar rather than the bar
whose label shares the calendar week or month. If the aggregate bars already contain
payments, the fallback does not add them again. The flag therefore requests
completeness for coarse bars; it is not an include/exclude filter.

OHLC, volume and dividends are expressed on the latest split-adjusted share basis.
Prices are not adjusted for dividends. `splitRatio` appears only on a
corporate-action candle and expresses the new-to-old share ratio, for example `10.0`
for a 10-for-1 split.

This basis keeps price gains and technical indicators continuous across stock splits
without silently turning the explicit dividend stream into total-return prices.

## Currency conversion

The optional `currency` parameter is syntactically validated as three letters. The
service does not maintain an independent ISO currency registry. Cross-currency
conversion succeeds only when the instrument exposes a native currency and Yahoo
provides the required FX data. Otherwise the API returns a typed `422` response.

After a successful conversion:

- quote monetary fields such as `lastPrice`, `previousClose`, `eps`, `marketCap`,
  `fiftyTwoWeekHigh` and `fiftyTwoWeekLow` use the effective spot conversion rate;
- gains, dividend yield and dividend growth remain dimensionless, but are recomputed
  using the relevant historical FX rates;
- `peRatio`, `pbRatio`, `roe` and `beta` are dimensionless fundamentals and are not
  converted;
- historical OHLC and dividends use the applicable rate for each date;
- volume is never converted;
- technical indicators are recomputed from the converted price series. RSI remains
  dimensionless; monetary indicator outputs reflect the target-currency series.

If FX history starts later than instrument history, the response is trimmed to the
available overlap. Quote provenance becomes `PARTIAL` only when that overlap leaves
an actual analytic unavailable; `priceStatus` continues to describe the current
converted price. History and indicator responses remain `PARTIAL` when trimming
removes requested or calculation-warmup data. The response `currency` changes to the
requested target only after conversion has succeeded.

The five-year gain uses the nearest available session on or before its anniversary.
Quote calculation fetches the provider's ten-year period because Yahoo exposes no
five-year-plus-buffer period, then retains only a 14-day buffer before the five-year
target. This avoids a rolling-window boundary without treating a genuinely younger
instrument as five years old. Historical FX conversion uses the latest rate on or
before the instrument session only when it is at most four calendar days old. A
missing instrument or FX reference reports `gain.fiveYear` in
`analyticsLimitations`.

For a converted quote, `priceStatus` is the worse freshness status of the instrument
price and the FX rate actually selected for `lastPrice`. A fresh instrument therefore
cannot mask a stale conversion rate.

yfinance's repair pipeline standardizes histories quoted in subunits: GBp, ZAc and
ILA candles and dividends arrive as GBP, ZAR and ILS. Stock Analyst applies the
`0.01` subunit scale only to spot/info price fields, which yfinance can still report
in the exchange subunit.

## Indicators

The accepted keys are `sma50`, `sma200`, `ema50`, `ema200`, `bb`, `rsi` and `macd`.
They represent SMA 50/200, EMA 50/200, Bollinger Bands, RSI and MACD. The current
OpenAPI parameter documents the comma-separated format but does not encode this set
as an enum; this list follows the request validator and calculation code.

`/v1/indicators/{stock}` returns only the latest requested values.
`/v1/history/{stock}?indicators=...` returns series aligned with the requested price
window. Omitting `indicators` on the latest-indicator route requests all supported
indicators; omitting it on the history route skips indicator computation.

## Partial comparison

`/v1/compare` evaluates symbols independently. A symbol-specific unknown symbol,
insufficient-data response or ordinary upstream failure becomes an item with
`error`, while other symbols can still succeed.

An upstream rate limit (`429`) or local/upstream availability rejection (`503`)
aborts the whole comparison. Continuing the fan-out in either case would amplify an
already constrained dependency.

## Search results

`/v1/search/{query}` returns up to 20 quote results supplied by yfinance and caches a
successful lookup internally for five minutes. The query is limited to 50
characters.

The service does not filter by `quoteType`. Results can therefore include equities,
ETFs, indices, options, currencies and other types returned by Yahoo. Consumers that
support only a subset must filter explicitly.

## Errors and request IDs

Market-data errors use the typed error response defined in OpenAPI. A valid incoming
`X-Request-ID` is reused; otherwise the API generates an ID and returns it in both
the response header and error body.

The Kotlin client does not retry HTTP responses from the adapter. It preserves
classified `429` and `503` responses, including `Retry-After`, interprets expected
`404` results, and maps other backend failures to the public upstream-error
contract. Only transport-level I/O failures with no HTTP response are retried.

`/readyz` is operational rather than a market-data call. Its `503` indicates that
the required adapter did not pass the one-second readiness deadline and does not
carry the market-data retry contract.
