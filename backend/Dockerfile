FROM python:3 AS builder

WORKDIR /app

COPY requirements.txt .

RUN pip install --no-cache-dir -r requirements.txt

COPY app.py .

RUN pyinstaller --onefile app.py

FROM debian:stable-slim

WORKDIR /app

RUN useradd -m stock-analyst

USER stock-analyst

COPY --from=builder /app/dist/app stock-analyst-backend

EXPOSE 8081

CMD ["./stock-analyst-backend"]
