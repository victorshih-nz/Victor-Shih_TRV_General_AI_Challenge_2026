FROM python:3.12-slim

RUN pip install --no-cache-dir nats-py

WORKDIR /app

COPY __init__.py accounting.py hedger.py /app/hedger/

ENV NATS_URL=nats://nats:4222

CMD ["python", "-m", "hedger.hedger"]