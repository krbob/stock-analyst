FROM python:3.14-slim@sha256:cea0e6040540fb2b965b6e7fb5ffa00871e632eef63719f0ea54bca189ce14a6

WORKDIR /app

COPY requirements.txt .

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && pip install --no-cache-dir -r requirements.txt \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -m stock-analyst \
    && mkdir -p /home/stock-analyst/.cache/py-yfinance \
    && chown -R stock-analyst:stock-analyst /home/stock-analyst/.cache

COPY app.py bulkhead.py circuit_breaker.py memory_cache.py metrics.py singleflight.py ./

USER stock-analyst

EXPOSE 8081

CMD ["python", "app.py"]
