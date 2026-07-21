# Development

## Prerequisites

- JDK 25;
- Python 3.13.14, matching [`.python-version`](../.python-version);
- Docker with the Compose plugin for container tests and the simplest full-stack
  setup.

The Gradle wrapper downloads the pinned Gradle distribution and verifies its
checksum. Use `./gradlew` rather than a system Gradle installation.

## Choose a development mode

### Complete stack in Docker

This starts both services and is the least error-prone option:

~~~bash
docker compose up --build --detach --wait
curl --fail http://localhost:8080/readyz
~~~

Source edits require rebuilding the affected image. Stop and remove the local stack
with `docker compose down`.

### Both services natively

Create a POSIX virtual environment and install the complete hash-locked development
set:

~~~bash
python3.13 -m venv .venv
.venv/bin/python -m pip install --require-hashes \
  -r backend-yfinance/requirements-dev.lock
~~~

Start the adapter in one terminal:

~~~bash
cd backend-yfinance
../.venv/bin/python app.py
~~~

Start Ktor from the repository root in another:

~~~bash
./gradlew run
~~~

Ktor defaults to `BACKEND_URL=http://localhost:8081`, which matches the native
adapter. To use a different instance:

~~~bash
BACKEND_URL=http://127.0.0.1:18081 ./gradlew run
~~~

Running `./gradlew run` by itself starts only Ktor. Liveness will pass, but readiness
and market-data calls will fail until an adapter is reachable.

### Kotlin native, adapter in Docker

Start only the adapter and publish its internal port:

~~~bash
docker compose run --rm --build --publish 8081:8081 \
  stock-analyst-backend-yfinance
~~~

Then run `./gradlew run` in a second terminal. This mode is useful when changing
domain or route code without rebuilding the Python image.

## Tests and static analysis

Validate documentation links, canonical OpenAPI paths and documented configuration
without installing third-party packages:

~~~bash
python3 scripts/validate-docs.py
~~~

The same gate runs in CI immediately after checkout.

Run the Kotlin unit, route and contract tests:

~~~bash
./gradlew test
~~~

Run detekt:

~~~bash
./gradlew detekt
~~~

Run the adapter suite from an environment containing `requirements-dev.lock`:

~~~bash
(cd backend-yfinance && ../.venv/bin/python -m pytest test_app.py)
~~~

The Python tests mock Yahoo and are deterministic. Kotlin route tests inject provider
fixtures. Neither command requires internet access after dependencies are installed.

Validate the Compose model and build both runtime images when changing Docker or
runtime dependencies:

~~~bash
docker compose config --quiet
docker compose build
~~~

CI additionally lints both Dockerfiles, scans runtime dependencies and images, and
runs a deterministic container smoke test.

## OpenAPI changes

[`src/main/resources/openapi/stock-analyst-v1.json`](../src/main/resources/openapi/stock-analyst-v1.json)
is the canonical consumer contract. Keep route behavior, domain serialization and
the document in one change.

`ApiVersionContractTest` verifies important route/schema compatibility, but consumer
copies are stored in other repositories and are not synchronized automatically. For
an externally visible contract or semantic change:

1. update the canonical document and tests here;
2. update affected generated clients or reviewed contract copies;
3. update the compatibility manifest's source commit and contract hash;
4. deploy according to [Deployment](deployment.md).

Do not copy large response examples into README files. Tests and OpenAPI should
provide executable contract evidence.

## Gradle dependency locks

Every resolvable Gradle configuration is locked. After an intentional dependency
change:

~~~bash
./gradlew resolveAndLockAll --write-locks
git status --short -- '*gradle.lockfile'
~~~

Review every lockfile change. Do not edit lockfiles manually.

## Python dependency locks

Direct production, development and lock-tool dependencies live in:

- `backend-yfinance/requirements.txt`;
- `backend-yfinance/requirements-dev.txt`;
- `backend-yfinance/requirements-tooling.txt`.

Install the pinned lock generator:

~~~bash
.venv/bin/python -m pip install --require-hashes \
  -r backend-yfinance/requirements-tooling.lock
~~~

Regenerate locks with Python 3.13.14:

~~~bash
cd backend-yfinance

../.venv/bin/pip-compile --generate-hashes --strip-extras \
  --resolver=backtracking \
  --output-file=requirements.lock requirements.txt

../.venv/bin/pip-compile --generate-hashes --strip-extras \
  --resolver=backtracking \
  --output-file=requirements-dev.lock requirements-dev.txt

../.venv/bin/pip-compile --generate-hashes --strip-extras --allow-unsafe \
  --resolver=backtracking \
  --output-file=requirements-tooling.lock requirements-tooling.txt
~~~

Install the regenerated file with `--require-hashes` and run `python -m pip check`
plus the adapter tests before committing it. CI regenerates all three locks and
rejects drift.

## SBOM and reproducible inputs

Generate the aggregate production-runtime CycloneDX 1.6 SBOM:

~~~bash
./gradlew cyclonedxBom
~~~

The ignored output is:

~~~text
build/reports/cyclonedx/stock-analyst.cdx.json
~~~

It excludes test/build dependencies, omits a random serial number and normalizes its
metadata timestamp. CI generates it twice, requires identical bytes, uploads it for
14 days and scans it with pinned Trivy.

Runtime base images use immutable OCI digests, the Python image installs
`requirements.lock` in `--require-hashes` mode, and GitHub Actions are commit-pinned.

## CI and published images

The main workflow:

- runs on pull requests and pushes to `main`;
- runs all deterministic quality and supply-chain gates;
- publishes multi-platform API and adapter images only for a push;
- publishes BuildKit provenance and SBOM attestations.

The image repositories are:

~~~text
ghcr.io/krbob/stock-analyst
ghcr.io/krbob/stock-analyst-backend-yfinance
~~~

Moving tags are useful for development and canaries. Production promotion must use
the resulting immutable digests.

## yfinance update coverage

The repository-wide Renovate policy gives every yfinance release a minimum age of
seven days. Its pull request must pass the complete adapter fixture suite, including
repair, subunit, split and error cases, before it is eligible for the monthly merge
window.

The current pull-request workflow does **not** publish candidate images. The manual
`Live Yahoo canary` accepts an already published tag and uses that same tag for both
the Kotlin and adapter images. Therefore this repository does not currently provide
an automatic pre-merge candidate-image canary.

After merge, the scheduled/manual canary validates the published `main` pair against
live Yahoo, but remains non-blocking because an external outage must not invalidate a
reproducible build. A green Renovate pull request therefore proves the deterministic
contract and fixture coverage, not a live Yahoo request.

## Renovate policy

Renovate creates mature dependency pull requests in the configured Monday window,
with bounded concurrent and hourly creation. Existing branches may be rebased and
retested at any time. Every update type, including major versions, actions, images,
scanners, lockfile maintenance and security alerts, is eligible for squash automerge
only after required CI is green and the branch is current. Renovate itself performs
merges during the first three days of each month; native platform automerge stays
disabled so it cannot bypass that window.

When Renovate changes a direct Python input, regenerate and commit the corresponding
lockfile rather than accepting an incomplete direct-file-only update.
