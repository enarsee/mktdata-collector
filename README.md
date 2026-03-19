# Market Data Collector

Captures real-time market data from cryptocurrency exchanges via WebSocket and persists to local storage for backtesting.

Currently supports **Binance 1m candlestick (kline)** streams with CSV output.

## Architecture

```
Binance WS ──> FailoverWebSocketConnector (hot standby, dedup)
                   ──> MessageHandler (deserialize + convert via MessageType)
                          ──> MarketDataQueue (async fan-out by DataType)
                                 ──> Persistor (CSV)
```

**Key design decisions:**

- **Hot-standby failover** — Two simultaneous WebSocket connections to the same stream. If one drops, the other continues with zero data gap. A deduplicating callback (LRU cache on eventTime) ensures each message is processed only once.
- **Generic WebSocket connector** — Transport layer knows nothing about message content. Handles connection lifecycle, OkHttp ping-based heartbeat, and auto-reconnect.
- **Typed message system** — Each stream type (kline, future: orderbook, trades) defines its own exchange-specific POJO model, subscribe command, and conversion to a standardized common model (`MarketData`).
- **Queue with filtered fan-out** — A `BlockingQueue` decouples the WebSocket thread from persistence. Subscribers register with a `DataType` filter, so each persistor only receives data it handles (e.g., KLINE persistor won't receive ORDER_BOOK data).
- **Persistor factory** — Storage backend is selected via config. Currently CSV, extensible to Parquet, databases, etc.

## Requirements

- Java 17+
- Maven (bundled with IntelliJ, or install separately)

## Configuration

Edit `config.yaml`:

```yaml
exchange: BINANCE
symbol: BTCUSDT
interval: 1m
outputDir: ./data
persistorType: csv
```

## Running

```bash
# Using IntelliJ bundled Maven
MVN="/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"

# Build
"$MVN" package -DskipTests

# Run
"$MVN" exec:java -Dexec.mainClass="com.enarsee.marketdata.App"
```

Press `Ctrl+C` to stop gracefully.

## Output

CSV files are written to the output directory with the naming convention:

```
{Exchange}_{Symbol}_{interval}_{date}.csv
```

Example: `BINANCE_BTCUSDT_1m_2026-03-19.csv`

```csv
open_time,close_time,open,high,low,close,volume
2026-03-19T00:00:00Z,2026-03-19T00:01:00Z,67000.50,67100.00,66900.00,67050.25,123.456
```

## Testing

```bash
"$MVN" test
```

## Extending

| To add... | Create... |
|-----------|-----------|
| New stream type (orderbook, trades) | `MessageType` impl + exchange POJO + `Persistor` impl |
| New exchange | New `MessageType` impls + exchange-specific POJOs |
| New storage backend | `Persistor` impl + register in `PersistorFactory` |
