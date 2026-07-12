FROM python:3.13-slim

WORKDIR /app

COPY requirements.txt .

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && pip install --no-cache-dir -r requirements.txt \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -m stock-analyst \
    && mkdir -p /home/stock-analyst/.cache/py-yfinance \
    && chown -R stock-analyst:stock-analyst /home/stock-analyst/.cache

COPY app.py bulkhead.py memory_cache.py singleflight.py ./

USER stock-analyst

EXPOSE 8081

CMD ["python", "app.py"]
