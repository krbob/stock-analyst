FROM python:3.13-slim@sha256:eb43ff125d8d58d7449dcba7d336c23bcac412f526d861db493b9994d8010280

WORKDIR /app

COPY requirements.lock .

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && pip install --no-cache-dir --require-hashes -r requirements.lock \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -m stock-analyst \
    && mkdir -p /home/stock-analyst/.cache/py-yfinance \
    && chown -R stock-analyst:stock-analyst /home/stock-analyst/.cache

COPY app.py bulkhead.py circuit_breaker.py memory_cache.py metrics.py singleflight.py ./

USER stock-analyst

EXPOSE 8081

CMD ["python", "app.py"]
