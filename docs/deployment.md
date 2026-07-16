# Deployment

This repository publishes two images that form one provider:

~~~text
ghcr.io/krbob/stock-analyst
ghcr.io/krbob/stock-analyst-backend-yfinance
~~~

The Kotlin image is the consumer-facing API. The adapter image must remain internal.

## Local Compose is not a release definition

[`docker-compose.yml`](../docker-compose.yml) builds local images by default and can
also accept image references through:

- `STOCK_ANALYST_IMAGE`;
- `STOCK_ANALYST_BACKEND_YFINANCE_IMAGE`.

This is useful for CI, local testing and the live canary. It is not the production
release manifest.

The `main` tag moves after successful pushes. Production configuration must use an
immutable digest:

~~~text
ghcr.io/krbob/stock-analyst@sha256:<64 hexadecimal characters>
ghcr.io/krbob/stock-analyst-backend-yfinance@sha256:<64 hexadecimal characters>
~~~

An image configured only as `:main` is not digest-pinned even if Docker currently
resolves it to a specific local image ID.

## Compatibility manifest

The ecosystem hand-off is owned by the Portfolio repository. Its stable documentation
is the
[deployment compatibility guide](https://github.com/krbob/portfolio/blob/main/docs/deployment-compatibility.md);
versioned manifest files live under
[`deployment/compatibility/`](https://github.com/krbob/portfolio/tree/main/deployment/compatibility).

Each manifest records reviewed source commits, contract hashes, image repositories,
digest environment names and rollout stages. A manifest with status `candidate`,
an image with status `unpublished`, or a `null` digest is intentionally not a
production release and cannot supply deployment values.

After all images have been published and verified, create a new manifest version
with real registry digests and validate it using the Portfolio repository's
`scripts/validate-compatibility-manifest.py`. Never place zeroes, invented values or
moving tags in a digest field.

This Stock Analyst repository does not duplicate current release digests in its
README. The versioned compatibility manifest is the release record.

## Contract hand-off

The canonical Stock Analyst contract is:

[`src/main/resources/openapi/stock-analyst-v1.json`](../src/main/resources/openapi/stock-analyst-v1.json)

A release must record its source commit and SHA-256. Consumer repositories can
vendor reviewed copies, but a copy matching an earlier manifest is not evidence that
it matches the current provider branch.

Before deploying a consumer that relies on a changed contract:

1. publish and deploy the matching Stock Analyst provider;
2. require `/readyz` to return 200;
3. fetch `/openapi/v1.json` from that deployed provider;
4. verify the required `/v1` paths and, for a contract change, its reviewed hash;
5. only then deploy the consumer.

This ordering avoids the window in which a new consumer calls behavior not yet
present in the provider.

## Staged rollout

Within the complete ecosystem, use this order:

1. `stock-analyst-backend-yfinance`;
2. `stock-analyst`;
3. provider gates and contract verification;
4. Portfolio API or another server-side consumer;
5. browser frontends.

For the Stock Analyst pair itself:

1. pull the reviewed adapter digest;
2. replace the adapter container;
3. wait for adapter `/health`;
4. pull and replace the reviewed Kotlin digest;
5. wait for Kotlin `/healthz` and `/readyz`;
6. verify the bundled OpenAPI and deterministic smoke calls;
7. inspect bounded logs and metrics before continuing to consumers.

Do not deploy the Kotlin service merely because its process is live. `/readyz` is the
provider gate because it includes adapter reachability.

## Current ecosystem routing convention

The current production reverse proxy routes:

- `https://stock.bobinski.net/` to Stock Analyst UI;
- `https://stock.bobinski.net/api/...` to the Kotlin API after stripping `/api`;
- `/api/metrics` through a separate deny/restricted route rather than the public API
  router.

Thus container-local `GET /v1/quote/AAPL` becomes public
`GET /api/v1/quote/AAPL`. The application itself knows nothing about `/api`; the
prefix belongs to Traefik.

Keep the adapter off the Traefik network. The Kotlin service needs both the private
adapter network and the proxy network.

Use uniquely named Traefik routers, services and middleware. Shared generic names
such as `strip-api` can collide with another stack in the same Traefik instance.

## Post-deployment verification

From the public side:

~~~bash
base_url=https://stock.bobinski.net/api

curl --fail "$base_url/healthz"
curl --fail "$base_url/readyz"
curl --fail "$base_url/openapi/v1.json" |
  jq --exit-status \
    '.openapi == "3.0.3" and
     .paths["/v1/quote/{stock}"] != null and
     .paths["/v1/history/{stock}"] != null'
~~~

Verify public metrics remain inaccessible from an untrusted address:

~~~bash
test "$(curl --silent --output /dev/null --write-out '%{http_code}' \
  "$base_url/metrics")" = "403"
~~~

From the host, execute checks inside the private containers:

~~~bash
docker exec stock-analyst \
  curl --fail http://stock-analyst-backend:8081/health
docker exec stock-analyst-backend-yfinance \
  curl --fail http://127.0.0.1:8081/metrics
docker exec stock-analyst \
  curl --fail http://127.0.0.1:8080/metrics
~~~

The exact DNS service names are deployment-owned. The examples above match the
current production naming; the repository Compose service uses
`stock-analyst-backend-yfinance`.

Finally, perform a bounded live semantic check appropriate for the release. A live
Yahoo failure must be distinguished from an image/contract failure; deterministic CI
evidence remains the reproducible gate.

## Rollback

Retain the previously verified API and adapter digests as one rollback pair. If a
provider gate fails:

1. stop rollout before replacing server-side consumers;
2. restore the previous adapter digest;
3. restore the previous API digest;
4. require `/readyz` and the previous contract hash;
5. record the failed digest and diagnostics.

Rolling back only one half of the pair can reintroduce an adapter-contract mismatch.
Do not use a moving tag to approximate the previous release.

## Candidate and live canary limitation

Pull-request CI builds test images but does not publish them. The manual live canary
can start only an already published tag and applies one tag name to both provider
images. The repository therefore has no self-contained pre-merge candidate-image
publication flow.

See [Development](development.md#yfinance-update-review) for the review procedure.
Do not describe an unpublished PR image as canary-tested.
