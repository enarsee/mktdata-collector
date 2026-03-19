# Architecture Refactoring Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor the market data collector into a decoupled, extensible architecture with generic WebSocket transport, typed message handling, queue-based fan-out, persistor factory, and hot-standby failover with dedup.

**Architecture:** Generic WebSocket connector handles transport + heartbeat + failover (primary/secondary hot standby). Message types (kline, future: orderbook, trades) each define a Binance-specific POJO, a subscribe command, and a handler that converts to the common data model. A `BlockingQueue` sits between ingestors and persistors, decoupling threads and enabling fan-out to multiple consumers. A `PersistorFactory` selects persistor type from config.

**Tech Stack:** Java 17, Maven, OkHttp, Gson (`@SerializedName` for typed deserialization), SnakeYAML, SLF4J + Logback, JUnit 5

**Maven alias (used throughout):**
```bash
MVN="/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"
```

---

## File Structure

```
com.bullish.marketdata
├── model/
│   ├── Exchange.java                          # (existing, unchanged)
│   ├── Candlestick.java                       # (modified) implements MarketData
│   ├── DataType.java                          # Enum: KLINE, ORDER_BOOK, TRADE (future)
│   └── MarketData.java                        # Interface: exchange(), symbol(), dataType()
├── connector/
│   ├── WebSocketConnector.java                # Generic WS transport: connect, heartbeat, onMessage callback
│   ├── WebSocketListener.java                 # Callback interface: onMessage, onConnected, onDisconnected
│   └── FailoverWebSocketConnector.java        # Manages primary + secondary hot standby, dedup
├── ingestor/
│   ├── MessageType.java                       # Interface: streamName(), deserialize(), toMarketData()
│   ├── MessageHandler.java                    # Receives raw WS messages, routes to MessageType, pushes to queue
│   ├── MarketDataQueue.java                   # BlockingQueue wrapper with fan-out subscribe(DataType filter)
│   ├── binance/
│   │   ├── BinanceKlineMessage.java           # Binance kline POJO with @SerializedName
│   │   └── BinanceKlineMessageType.java       # MessageType impl: stream name, deser, convert to Candlestick
│   └── Ingestor.java                          # (refactored) Orchestrates connector + handler + queue
├── persistor/
│   ├── Persistor.java                         # (refactored) Interface: consumes MarketData from queue
│   ├── PersistorFactory.java                  # Factory: creates persistor by type from config
│   └── CsvFilePersistor.java                  # (refactored) Implements Persistor, consumes from queue
├── config/
│   └── AppConfig.java                         # (extended) Adds persistorType field
└── App.java                                   # (refactored) Wires connector → handler → queue → persistor
```

---

## Chunk 1: Common Model & Message Type System

### Task 1: MarketData Marker Interface

**Files:**
- Create: `src/main/java/com/bullish/marketdata/model/MarketData.java`

- [ ] **Step 1: Create `DataType` enum**

```java
package com.bullish.marketdata.model;

public enum DataType {
    KLINE,
    ORDER_BOOK,
    TRADE
}
```

- [ ] **Step 2: Create `MarketData` interface**

```java
package com.bullish.marketdata.model;

/**
 * Common interface for all standardized market data types.
 * dataType() enables routing to the correct persistor.
 */
public interface MarketData {
    Exchange exchange();
    String symbol();
    DataType dataType();
}
```

- [ ] **Step 3: Make `Candlestick` implement `MarketData`**

Modify `src/main/java/com/bullish/marketdata/model/Candlestick.java`:

```java
package com.bullish.marketdata.model;

import java.math.BigDecimal;
import java.time.Instant;

public record Candlestick(
    Exchange exchange,
    String symbol,
    Instant openTime,
    Instant closeTime,
    BigDecimal open,
    BigDecimal high,
    BigDecimal low,
    BigDecimal close,
    BigDecimal volume
) implements MarketData {
    public static final String CSV_HEADER = "open_time,close_time,open,high,low,close,volume";

    @Override
    public DataType dataType() {
        return DataType.KLINE;
    }

    public String toCsvLine() {
        return String.join(",",
            openTime.toString(),
            closeTime.toString(),
            open.toPlainString(),
            high.toPlainString(),
            low.toPlainString(),
            close.toPlainString(),
            volume.toPlainString()
        );
    }
}
```

- [ ] **Step 4: Verify existing tests still pass**

```bash
"$MVN" test -q
```

Expected: 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/bullish/marketdata/model/
git commit -m "feat: add DataType enum and MarketData interface, Candlestick implements it"
```

---

### Task 2: Binance Kline POJO with Gson annotations

**Files:**
- Create: `src/main/java/com/bullish/marketdata/ingestor/binance/BinanceKlineEvent.java`
- Create: `src/main/java/com/bullish/marketdata/ingestor/binance/BinanceKline.java`
- Test: `src/test/java/com/bullish/marketdata/ingestor/binance/BinanceKlineEventTest.java`

The Binance kline WebSocket message structure is:
```json
{
  "e": "kline",
  "E": 1672515782136,
  "s": "BTCUSDT",
  "k": {
    "t": 1672515780000, "T": 1672515839999,
    "s": "BTCUSDT", "i": "1m",
    "o": "67000.50", "h": "67100.00", "l": "66900.00", "c": "67050.25",
    "v": "123.456", "x": true
  }
}
```

- [ ] **Step 1: Write the Binance POJO classes**

```java
// BinanceKline.java
package com.bullish.marketdata.ingestor.binance;

import com.google.gson.annotations.SerializedName;

public class BinanceKline {
    @SerializedName("t") public long openTime;
    @SerializedName("T") public long closeTime;
    @SerializedName("s") public String symbol;
    @SerializedName("i") public String interval;
    @SerializedName("o") public String open;
    @SerializedName("h") public String high;
    @SerializedName("l") public String low;
    @SerializedName("c") public String close;
    @SerializedName("v") public String volume;
    @SerializedName("x") public boolean closed;
}
```

```java
// BinanceKlineEvent.java
package com.bullish.marketdata.ingestor.binance;

import com.google.gson.annotations.SerializedName;

public class BinanceKlineEvent {
    @SerializedName("e") public String eventType;
    @SerializedName("E") public long eventTime;
    @SerializedName("s") public String symbol;
    @SerializedName("k") public BinanceKline kline;
}
```

- [ ] **Step 2: Write failing test for deserialization and conversion**

```java
package com.bullish.marketdata.ingestor.binance;

import com.bullish.marketdata.model.Candlestick;
import com.bullish.marketdata.model.Exchange;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class BinanceKlineEventTest {

    private static final String KLINE_JSON = """
        {
          "e": "kline",
          "E": 1672515782136,
          "s": "BTCUSDT",
          "k": {
            "t": 1672515780000,
            "T": 1672515839999,
            "s": "BTCUSDT",
            "i": "1m",
            "o": "67000.50",
            "h": "67100.00",
            "l": "66900.00",
            "c": "67050.25",
            "v": "123.456",
            "x": true
          }
        }
        """;

    @Test
    void shouldDeserializeKlineEvent() {
        Gson gson = new Gson();
        BinanceKlineEvent event = gson.fromJson(KLINE_JSON, BinanceKlineEvent.class);

        assertEquals("kline", event.eventType);
        assertEquals("BTCUSDT", event.symbol);
        assertTrue(event.kline.closed);
        assertEquals("67000.50", event.kline.open);
        assertEquals("67100.00", event.kline.high);
        assertEquals("66900.00", event.kline.low);
        assertEquals("67050.25", event.kline.close);
        assertEquals("123.456", event.kline.volume);
        assertEquals(1672515780000L, event.kline.openTime);
        assertEquals(1672515839999L, event.kline.closeTime);
    }

    @Test
    void shouldNotDeserializeUnclosedKline() {
        String unclosedJson = KLINE_JSON.replace("\"x\": true", "\"x\": false");
        Gson gson = new Gson();
        BinanceKlineEvent event = gson.fromJson(unclosedJson, BinanceKlineEvent.class);

        assertFalse(event.kline.closed);
    }
}
```

- [ ] **Step 3: Run tests to verify they pass**

```bash
"$MVN" test -Dtest="com.bullish.marketdata.ingestor.binance.BinanceKlineEventTest" -q
```

Expected: 2 tests PASS (POJOs + Gson should just work).

- [ ] **Step 4: Commit**

```bash
git add src/
git commit -m "feat: add typed Binance kline POJOs with Gson annotations"
```

---

### Task 3: MessageType Interface & BinanceKlineMessageType

**Files:**
- Create: `src/main/java/com/bullish/marketdata/ingestor/MessageType.java`
- Create: `src/main/java/com/bullish/marketdata/ingestor/binance/BinanceKlineMessageType.java`
- Test: `src/test/java/com/bullish/marketdata/ingestor/binance/BinanceKlineMessageTypeTest.java`

- [ ] **Step 1: Write `MessageType` interface**

```java
package com.bullish.marketdata.ingestor;

import com.bullish.marketdata.model.MarketData;

import java.util.Optional;

/**
 * Defines a type of market data stream.
 * Each implementation knows how to:
 * - build the stream name for subscription
 * - deserialize raw JSON into exchange-specific POJOs
 * - convert to the standardized MarketData model
 * - filter (e.g., only closed candles)
 */
public interface MessageType<T> {
    /** Stream name for WebSocket subscription, e.g. "btcusdt@kline_1m" */
    String streamName();

    /** Deserialize raw JSON into exchange-specific type */
    T deserialize(String json);

    /**
     * Convert exchange-specific type to standardized MarketData.
     * Returns empty if the message should be filtered (e.g., unclosed candle).
     */
    Optional<? extends MarketData> toMarketData(T raw);
}
```

- [ ] **Step 2: Write failing test for `BinanceKlineMessageType`**

```java
package com.bullish.marketdata.ingestor.binance;

import com.bullish.marketdata.model.Candlestick;
import com.bullish.marketdata.model.Exchange;
import com.bullish.marketdata.model.MarketData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class BinanceKlineMessageTypeTest {

    private static final String CLOSED_KLINE = """
        {
          "e": "kline", "E": 1672515782136, "s": "BTCUSDT",
          "k": {
            "t": 1672515780000, "T": 1672515839999,
            "s": "BTCUSDT", "i": "1m",
            "o": "67000.50", "h": "67100.00", "l": "66900.00", "c": "67050.25",
            "v": "123.456", "x": true
          }
        }
        """;

    private static final String UNCLOSED_KLINE = """
        {
          "e": "kline", "E": 1672515782136, "s": "BTCUSDT",
          "k": {
            "t": 1672515780000, "T": 1672515839999,
            "s": "BTCUSDT", "i": "1m",
            "o": "67000.50", "h": "67100.00", "l": "66900.00", "c": "67050.25",
            "v": "123.456", "x": false
          }
        }
        """;

    @Test
    void shouldReturnCorrectStreamName() {
        BinanceKlineMessageType type = new BinanceKlineMessageType("BTCUSDT", "1m");
        assertEquals("btcusdt@kline_1m", type.streamName());
    }

    @Test
    void shouldConvertClosedKlineToCandlestick() {
        BinanceKlineMessageType type = new BinanceKlineMessageType("BTCUSDT", "1m");

        BinanceKlineEvent event = type.deserialize(CLOSED_KLINE);
        Optional<? extends MarketData> result = type.toMarketData(event);

        assertTrue(result.isPresent());
        Candlestick candle = (Candlestick) result.get();
        assertEquals(Exchange.BINANCE, candle.exchange());
        assertEquals("BTCUSDT", candle.symbol());
        assertEquals(new BigDecimal("67000.50"), candle.open());
        assertEquals(new BigDecimal("67100.00"), candle.high());
        assertEquals(new BigDecimal("66900.00"), candle.low());
        assertEquals(new BigDecimal("67050.25"), candle.close());
        assertEquals(new BigDecimal("123.456"), candle.volume());
        assertEquals(Instant.ofEpochMilli(1672515780000L), candle.openTime());
        assertEquals(Instant.ofEpochMilli(1672515839999L), candle.closeTime());
    }

    @Test
    void shouldFilterUnclosedKline() {
        BinanceKlineMessageType type = new BinanceKlineMessageType("BTCUSDT", "1m");

        BinanceKlineEvent event = type.deserialize(UNCLOSED_KLINE);
        Optional<? extends MarketData> result = type.toMarketData(event);

        assertTrue(result.isEmpty());
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

```bash
"$MVN" test -Dtest="com.bullish.marketdata.ingestor.binance.BinanceKlineMessageTypeTest" -q
```

Expected: FAIL — `BinanceKlineMessageType` not found.

- [ ] **Step 4: Implement `BinanceKlineMessageType`**

```java
package com.bullish.marketdata.ingestor.binance;

import com.bullish.marketdata.ingestor.MessageType;
import com.bullish.marketdata.model.Candlestick;
import com.bullish.marketdata.model.Exchange;
import com.google.gson.Gson;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

public class BinanceKlineMessageType implements MessageType<BinanceKlineEvent> {
    private final Gson gson = new Gson();
    private final String symbol;
    private final String interval;

    public BinanceKlineMessageType(String symbol, String interval) {
        this.symbol = symbol;
        this.interval = interval;
    }

    @Override
    public String streamName() {
        return symbol.toLowerCase() + "@kline_" + interval;
    }

    @Override
    public BinanceKlineEvent deserialize(String json) {
        return gson.fromJson(json, BinanceKlineEvent.class);
    }

    @Override
    public Optional<Candlestick> toMarketData(BinanceKlineEvent event) {
        if (!event.kline.closed) {
            return Optional.empty();
        }
        Candlestick candle = new Candlestick(
            Exchange.BINANCE,
            event.kline.symbol,
            Instant.ofEpochMilli(event.kline.openTime),
            Instant.ofEpochMilli(event.kline.closeTime),
            new BigDecimal(event.kline.open),
            new BigDecimal(event.kline.high),
            new BigDecimal(event.kline.low),
            new BigDecimal(event.kline.close),
            new BigDecimal(event.kline.volume)
        );
        return Optional.of(candle);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

```bash
"$MVN" test -Dtest="com.bullish.marketdata.ingestor.binance.BinanceKlineMessageTypeTest" -q
```

Expected: 3 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add src/
git commit -m "feat: add MessageType interface and BinanceKlineMessageType"
```

---

## Chunk 2: Generic WebSocket Connector & Failover

### Task 4: Generic WebSocket Connector

**Files:**
- Create: `src/main/java/com/bullish/marketdata/connector/WebSocketCallback.java`
- Create: `src/main/java/com/bullish/marketdata/connector/WebSocketConnector.java`

- [ ] **Step 1: Create the callback interface**

```java
package com.bullish.marketdata.connector;

/**
 * Callback interface for WebSocket events.
 * Decoupled from any specific message type.
 */
public interface WebSocketCallback {
    void onMessage(String message);
    void onConnected();
    void onDisconnected();
}
```

- [ ] **Step 2: Implement `WebSocketConnector`**

A generic WebSocket transport that handles connection, heartbeat (ping), and reconnection. It knows nothing about message types — just delivers raw strings to the callback.

```java
package com.bullish.marketdata.connector;

import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class WebSocketConnector {
    private static final Logger log = LoggerFactory.getLogger(WebSocketConnector.class);

    private final String url;
    private final WebSocketCallback callback;
    private final OkHttpClient client;
    private final ScheduledExecutorService heartbeatExecutor;
    private final long heartbeatIntervalMs;

    private WebSocket webSocket;
    private ScheduledFuture<?> heartbeatTask;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);

    public WebSocketConnector(String url, WebSocketCallback callback, long heartbeatIntervalMs) {
        this.url = url;
        this.callback = callback;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.client = new OkHttpClient.Builder()
            .pingInterval(heartbeatIntervalMs, TimeUnit.MILLISECONDS)
            .build();
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-heartbeat-" + url.hashCode());
            t.setDaemon(true);
            return t;
        });
    }

    public void connect() {
        running.set(true);
        log.info("Connecting WebSocket: {}", url);
        Request request = new Request.Builder().url(url).build();
        webSocket = client.newWebSocket(request, new okhttp3.WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                log.info("WebSocket connected: {}", url);
                connected.set(true);
                startHeartbeat();
                callback.onConnected();
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                callback.onMessage(text);
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                log.error("WebSocket failure on {}: {}", url, t.getMessage());
                connected.set(false);
                stopHeartbeat();
                callback.onDisconnected();
                if (running.get()) {
                    reconnect();
                }
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                log.info("WebSocket closing {}: {} {}", url, code, reason);
                connected.set(false);
                stopHeartbeat();
                callback.onDisconnected();
            }
        });
    }

    public void disconnect() {
        running.set(false);
        stopHeartbeat();
        if (webSocket != null) {
            webSocket.close(1000, "Shutting down");
        }
        client.dispatcher().executorService().shutdown();
        heartbeatExecutor.shutdown();
        log.info("WebSocket disconnected: {}", url);
    }

    public boolean isConnected() {
        return connected.get();
    }

    private void reconnect() {
        log.info("Reconnecting WebSocket in 5s: {}", url);
        heartbeatExecutor.schedule(this::connect, 5, TimeUnit.SECONDS);
    }

    private void startHeartbeat() {
        heartbeatTask = heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (connected.get() && webSocket != null) {
                log.trace("Sending heartbeat ping to {}", url);
            }
        }, heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
    }
}
```

- [ ] **Step 3: Verify compile**

```bash
"$MVN" compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/bullish/marketdata/connector/
git commit -m "feat: add generic WebSocketConnector with heartbeat and reconnect"
```

---

### Task 5: FailoverWebSocketConnector with Dedup

**Files:**
- Create: `src/main/java/com/bullish/marketdata/connector/FailoverWebSocketConnector.java`
- Test: `src/test/java/com/bullish/marketdata/connector/FailoverWebSocketConnectorTest.java`

- [ ] **Step 1: Write failing test for dedup logic**

We test the dedup logic in isolation since we can't easily test real WebSocket failover in a unit test.

```java
package com.bullish.marketdata.connector;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FailoverWebSocketConnectorTest {

    @Test
    void shouldDeduplicateMessagesFromBothConnections() {
        List<String> received = new ArrayList<>();
        // Simulate the dedup filter used by FailoverWebSocketConnector
        DeduplicatingCallback dedup = new DeduplicatingCallback(
            new WebSocketCallback() {
                @Override public void onMessage(String message) { received.add(message); }
                @Override public void onConnected() {}
                @Override public void onDisconnected() {}
            },
            100 // cache size
        );

        // Same eventTime from both primary and secondary
        String msg = "{\"E\":1672515782136,\"k\":{\"t\":1672515780000}}";
        dedup.onMessage(msg); // first delivery
        dedup.onMessage(msg); // duplicate

        assertEquals(1, received.size());
    }

    @Test
    void shouldPassThroughDistinctMessages() {
        List<String> received = new ArrayList<>();
        DeduplicatingCallback dedup = new DeduplicatingCallback(
            new WebSocketCallback() {
                @Override public void onMessage(String message) { received.add(message); }
                @Override public void onConnected() {}
                @Override public void onDisconnected() {}
            },
            100
        );

        dedup.onMessage("{\"E\":1000}");
        dedup.onMessage("{\"E\":2000}");

        assertEquals(2, received.size());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
"$MVN" test -Dtest="com.bullish.marketdata.connector.FailoverWebSocketConnectorTest" -q
```

Expected: FAIL — `DeduplicatingCallback` not found.

- [ ] **Step 3: Implement `DeduplicatingCallback`**

```java
package com.bullish.marketdata.connector;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wraps a WebSocketCallback and deduplicates messages based on the "E" (eventTime) field.
 * Uses a bounded LRU cache to avoid unbounded memory growth.
 */
public class DeduplicatingCallback implements WebSocketCallback {
    private static final Logger log = LoggerFactory.getLogger(DeduplicatingCallback.class);
    private final WebSocketCallback delegate;
    private final Map<Long, Boolean> seen;

    public DeduplicatingCallback(WebSocketCallback delegate, int cacheSize) {
        this.delegate = delegate;
        this.seen = new LinkedHashMap<>(cacheSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
                return size() > cacheSize;
            }
        };
    }

    @Override
    public void onMessage(String message) {
        try {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            if (json.has("E")) {
                long eventTime = json.get("E").getAsLong();
                synchronized (seen) {
                    if (seen.putIfAbsent(eventTime, Boolean.TRUE) != null) {
                        log.trace("Dedup: skipping duplicate eventTime={}", eventTime);
                        return;
                    }
                }
            }
        } catch (Exception e) {
            // If we can't parse eventTime, pass through
        }
        delegate.onMessage(message);
    }

    @Override
    public void onConnected() {
        delegate.onConnected();
    }

    @Override
    public void onDisconnected() {
        delegate.onDisconnected();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
"$MVN" test -Dtest="com.bullish.marketdata.connector.FailoverWebSocketConnectorTest" -q
```

Expected: 2 tests PASS.

- [ ] **Step 5: Implement `FailoverWebSocketConnector`**

```java
package com.bullish.marketdata.connector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages primary + secondary WebSocket connections in hot standby mode.
 * Both connections stream simultaneously. A DeduplicatingCallback ensures
 * each message is delivered only once to the downstream handler.
 */
public class FailoverWebSocketConnector {
    private static final Logger log = LoggerFactory.getLogger(FailoverWebSocketConnector.class);
    private static final long DEFAULT_HEARTBEAT_MS = 30_000;
    private static final int DEDUP_CACHE_SIZE = 1000;

    private final WebSocketConnector primary;
    private final WebSocketConnector secondary;

    public FailoverWebSocketConnector(String url, WebSocketCallback callback) {
        DeduplicatingCallback dedup = new DeduplicatingCallback(callback, DEDUP_CACHE_SIZE);
        this.primary = new WebSocketConnector(url, dedup, DEFAULT_HEARTBEAT_MS);
        this.secondary = new WebSocketConnector(url, dedup, DEFAULT_HEARTBEAT_MS);
    }

    public void start() {
        log.info("Starting failover connector (primary + secondary hot standby)");
        primary.connect();
        secondary.connect();
    }

    public void stop() {
        log.info("Stopping failover connector");
        primary.disconnect();
        secondary.disconnect();
    }
}
```

- [ ] **Step 6: Commit**

```bash
git add src/
git commit -m "feat: add FailoverWebSocketConnector with dedup hot standby"
```

---

## Chunk 3: Queue, Persistor Factory & Refactored Wiring

### Task 6: MarketDataQueue with Fan-Out

**Files:**
- Create: `src/main/java/com/bullish/marketdata/ingestor/MarketDataQueue.java`
- Test: `src/test/java/com/bullish/marketdata/ingestor/MarketDataQueueTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.bullish.marketdata.ingestor;

import com.bullish.marketdata.model.Candlestick;
import com.bullish.marketdata.model.DataType;
import com.bullish.marketdata.model.Exchange;
import com.bullish.marketdata.model.MarketData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MarketDataQueueTest {

    private Candlestick testCandle() {
        return new Candlestick(
            Exchange.BINANCE, "BTCUSDT",
            Instant.parse("2026-03-19T00:00:00Z"),
            Instant.parse("2026-03-19T00:01:00Z"),
            new BigDecimal("67000"), new BigDecimal("67100"),
            new BigDecimal("66900"), new BigDecimal("67050"),
            new BigDecimal("100")
        );
    }

    @Test
    void shouldFanOutToMultipleConsumers() throws InterruptedException {
        MarketDataQueue queue = new MarketDataQueue();

        List<MarketData> consumer1Received = new ArrayList<>();
        List<MarketData> consumer2Received = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);

        queue.subscribe(DataType.KLINE, data -> { consumer1Received.add(data); latch.countDown(); });
        queue.subscribe(DataType.KLINE, data -> { consumer2Received.add(data); latch.countDown(); });

        queue.start();
        queue.publish(testCandle());

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(1, consumer1Received.size());
        assertEquals(1, consumer2Received.size());

        queue.stop();
    }

    @Test
    void shouldDeliverMultipleMessages() throws InterruptedException {
        MarketDataQueue queue = new MarketDataQueue();

        List<MarketData> received = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);

        queue.subscribe(DataType.KLINE, data -> { received.add(data); latch.countDown(); });

        queue.start();
        queue.publish(testCandle());
        queue.publish(testCandle());
        queue.publish(testCandle());

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(3, received.size());

        queue.stop();
    }

    @Test
    void shouldFilterByDataType() throws InterruptedException {
        MarketDataQueue queue = new MarketDataQueue();

        List<MarketData> klineReceived = new ArrayList<>();
        List<MarketData> orderbookReceived = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        queue.subscribe(DataType.KLINE, data -> { klineReceived.add(data); latch.countDown(); });
        queue.subscribe(DataType.ORDER_BOOK, data -> { orderbookReceived.add(data); });

        queue.start();
        queue.publish(testCandle()); // DataType.KLINE

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        // Small delay to ensure ORDER_BOOK subscriber had a chance to (not) receive
        Thread.sleep(100);
        assertEquals(1, klineReceived.size());
        assertEquals(0, orderbookReceived.size());

        queue.stop();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
"$MVN" test -Dtest="com.bullish.marketdata.ingestor.MarketDataQueueTest" -q
```

Expected: FAIL — `MarketDataQueue` not found.

- [ ] **Step 3: Implement `MarketDataQueue`**

```java
package com.bullish.marketdata.ingestor;

import com.bullish.marketdata.model.DataType;
import com.bullish.marketdata.model.MarketData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Internal queue that decouples the ingestor thread from consumer threads.
 * Supports filtered fan-out: each subscriber registers for a specific DataType.
 */
public class MarketDataQueue {
    private static final Logger log = LoggerFactory.getLogger(MarketDataQueue.class);
    private final BlockingQueue<MarketData> queue = new LinkedBlockingQueue<>();
    private final List<FilteredSubscriber> subscribers = new CopyOnWriteArrayList<>();
    private final ExecutorService dispatcherExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "market-data-dispatcher");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running = false;

    public void subscribe(DataType dataType, Consumer<MarketData> consumer) {
        subscribers.add(new FilteredSubscriber(dataType, consumer));
    }

    public void publish(MarketData data) {
        queue.offer(data);
    }

    public void start() {
        running = true;
        dispatcherExecutor.submit(() -> {
            while (running) {
                try {
                    MarketData data = queue.poll(500, TimeUnit.MILLISECONDS);
                    if (data != null) {
                        for (FilteredSubscriber sub : subscribers) {
                            if (sub.dataType == data.dataType()) {
                                try {
                                    sub.consumer.accept(data);
                                } catch (Exception e) {
                                    log.error("Subscriber error", e);
                                }
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    public void stop() {
        running = false;
        dispatcherExecutor.shutdown();
    }

    private record FilteredSubscriber(DataType dataType, Consumer<MarketData> consumer) {}
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
"$MVN" test -Dtest="com.bullish.marketdata.ingestor.MarketDataQueueTest" -q
```

Expected: 2 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/
git commit -m "feat: add MarketDataQueue with fan-out to multiple consumers"
```

---

### Task 7: MessageHandler — Routes Messages to MessageType and Queue

**Files:**
- Create: `src/main/java/com/bullish/marketdata/ingestor/MessageHandler.java`

- [ ] **Step 1: Implement `MessageHandler`**

```java
package com.bullish.marketdata.ingestor;

import com.bullish.marketdata.connector.WebSocketCallback;
import com.bullish.marketdata.model.MarketData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Receives raw WebSocket messages, deserializes via the configured MessageType,
 * and publishes converted MarketData to the queue.
 */
public class MessageHandler<T> implements WebSocketCallback {
    private static final Logger log = LoggerFactory.getLogger(MessageHandler.class);

    private final MessageType<T> messageType;
    private final MarketDataQueue queue;

    public MessageHandler(MessageType<T> messageType, MarketDataQueue queue) {
        this.messageType = messageType;
        this.queue = queue;
    }

    @Override
    public void onMessage(String message) {
        try {
            T raw = messageType.deserialize(message);
            Optional<? extends MarketData> data = messageType.toMarketData(raw);
            data.ifPresent(md -> {
                log.debug("Publishing market data: {}", md);
                queue.publish(md);
            });
        } catch (Exception e) {
            log.error("Error handling message: {}", message, e);
        }
    }

    @Override
    public void onConnected() {
        log.info("Handler connected for stream: {}", messageType.streamName());
    }

    @Override
    public void onDisconnected() {
        log.warn("Handler disconnected for stream: {}", messageType.streamName());
    }
}
```

- [ ] **Step 2: Verify compile**

```bash
"$MVN" compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/bullish/marketdata/ingestor/MessageHandler.java
git commit -m "feat: add MessageHandler routing WS messages to queue via MessageType"
```

---

### Task 8: Refactor Persistor & PersistorFactory

**Files:**
- Modify: `src/main/java/com/bullish/marketdata/persistor/Persistor.java`
- Modify: `src/main/java/com/bullish/marketdata/persistor/CsvFilePersistor.java`
- Create: `src/main/java/com/bullish/marketdata/persistor/PersistorFactory.java`
- Modify: `src/main/java/com/bullish/marketdata/config/AppConfig.java`
- Modify: `src/test/java/com/bullish/marketdata/persistor/CsvFilePersistorTest.java`

- [ ] **Step 1: Refactor `Persistor` interface**

The persistor now consumes `MarketData` instead of being coupled to `CandlestickListener`.

```java
package com.bullish.marketdata.persistor;

import com.bullish.marketdata.model.MarketData;

import java.util.function.Consumer;

/**
 * Consumes MarketData from the queue.
 */
public interface Persistor extends Consumer<MarketData> {
}
```

- [ ] **Step 2: Refactor `CsvFilePersistor`**

```java
package com.bullish.marketdata.persistor;

import com.bullish.marketdata.model.Candlestick;
import com.bullish.marketdata.model.MarketData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneOffset;

public class CsvFilePersistor implements Persistor {
    private static final Logger log = LoggerFactory.getLogger(CsvFilePersistor.class);
    private final String outputDir;
    private final String interval;

    public CsvFilePersistor(String outputDir, String interval) {
        this.outputDir = outputDir;
        this.interval = interval;
    }

    @Override
    public void accept(MarketData data) {
        if (data instanceof Candlestick candle) {
            persistCandlestick(candle);
        } else {
            log.warn("CsvFilePersistor does not support: {}", data.getClass().getSimpleName());
        }
    }

    private void persistCandlestick(Candlestick candle) {
        try {
            Path dir = Path.of(outputDir);
            Files.createDirectories(dir);

            LocalDate date = candle.openTime().atZone(ZoneOffset.UTC).toLocalDate();
            String filename = String.join("_",
                candle.exchange().name(),
                candle.symbol(),
                interval,
                date.toString()
            ) + ".csv";
            Path file = dir.resolve(filename);

            boolean isNew = !Files.exists(file);

            StringBuilder sb = new StringBuilder();
            if (isNew) {
                sb.append(Candlestick.CSV_HEADER).append("\n");
            }
            sb.append(candle.toCsvLine()).append("\n");

            Files.writeString(file, sb.toString(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Failed to persist candlestick: {}", candle, e);
        }
    }
}
```

- [ ] **Step 3: Update `CsvFilePersistorTest` to use new API**

```java
package com.bullish.marketdata.persistor;

import com.bullish.marketdata.model.Candlestick;
import com.bullish.marketdata.model.Exchange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CsvFilePersistorTest {

    @Test
    void shouldWriteCandlestickToCsvFile(@TempDir Path tempDir) throws IOException {
        CsvFilePersistor persistor = new CsvFilePersistor(tempDir.toString(), "1m");

        Candlestick candle = new Candlestick(
            Exchange.BINANCE, "BTCUSDT",
            Instant.parse("2026-03-19T00:00:00Z"),
            Instant.parse("2026-03-19T00:01:00Z"),
            new BigDecimal("67000.50"), new BigDecimal("67100.00"),
            new BigDecimal("66900.00"), new BigDecimal("67050.25"),
            new BigDecimal("123.456")
        );

        persistor.accept(candle);

        Path expectedFile = tempDir.resolve("BINANCE_BTCUSDT_1m_2026-03-19.csv");
        assertTrue(Files.exists(expectedFile));

        List<String> lines = Files.readAllLines(expectedFile);
        assertEquals(2, lines.size());
        assertEquals(Candlestick.CSV_HEADER, lines.get(0));
        assertEquals(candle.toCsvLine(), lines.get(1));
    }

    @Test
    void shouldAppendToCsvFileWithoutDuplicatingHeader(@TempDir Path tempDir) throws IOException {
        CsvFilePersistor persistor = new CsvFilePersistor(tempDir.toString(), "1m");

        Candlestick candle1 = new Candlestick(
            Exchange.BINANCE, "BTCUSDT",
            Instant.parse("2026-03-19T00:00:00Z"),
            Instant.parse("2026-03-19T00:01:00Z"),
            new BigDecimal("67000"), new BigDecimal("67100"),
            new BigDecimal("66900"), new BigDecimal("67050"),
            new BigDecimal("100")
        );
        Candlestick candle2 = new Candlestick(
            Exchange.BINANCE, "BTCUSDT",
            Instant.parse("2026-03-19T00:01:00Z"),
            Instant.parse("2026-03-19T00:02:00Z"),
            new BigDecimal("67050"), new BigDecimal("67200"),
            new BigDecimal("67000"), new BigDecimal("67150"),
            new BigDecimal("200")
        );

        persistor.accept(candle1);
        persistor.accept(candle2);

        Path expectedFile = tempDir.resolve("BINANCE_BTCUSDT_1m_2026-03-19.csv");
        List<String> lines = Files.readAllLines(expectedFile);
        assertEquals(3, lines.size());
        assertEquals(Candlestick.CSV_HEADER, lines.get(0));
    }
}
```

- [ ] **Step 4: Implement `PersistorFactory`**

```java
package com.bullish.marketdata.persistor;

public class PersistorFactory {

    public static Persistor create(String type, String outputDir, String interval) {
        return switch (type.toLowerCase()) {
            case "csv" -> new CsvFilePersistor(outputDir, interval);
            default -> throw new IllegalArgumentException("Unknown persistor type: " + type);
        };
    }
}
```

- [ ] **Step 5: Add `persistorType` to `AppConfig`**

```java
package com.bullish.marketdata.config;

import org.yaml.snakeyaml.Yaml;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

public class AppConfig {
    private String exchange;
    private String symbol;
    private String interval;
    private String outputDir;
    private String persistorType;

    public static AppConfig load(String path) throws IOException {
        Yaml yaml = new Yaml();
        try (InputStream in = new FileInputStream(path)) {
            Map<String, String> data = yaml.load(in);
            AppConfig config = new AppConfig();
            config.exchange = data.get("exchange");
            config.symbol = data.get("symbol");
            config.interval = data.get("interval");
            config.outputDir = data.get("outputDir");
            config.persistorType = data.getOrDefault("persistorType", "csv");
            return config;
        }
    }

    public String getExchange() { return exchange; }
    public String getSymbol() { return symbol; }
    public String getInterval() { return interval; }
    public String getOutputDir() { return outputDir; }
    public String getPersistorType() { return persistorType; }
}
```

- [ ] **Step 6: Run all tests**

```bash
"$MVN" test -q
```

Expected: All tests PASS.

- [ ] **Step 7: Commit**

```bash
git add src/
git commit -m "feat: refactor Persistor to Consumer<MarketData>, add PersistorFactory"
```

---

### Task 9: Refactor App Wiring & Delete Old Ingestor

**Files:**
- Modify: `src/main/java/com/bullish/marketdata/App.java`
- Delete: `src/main/java/com/bullish/marketdata/ingestor/Ingestor.java`
- Delete: `src/main/java/com/bullish/marketdata/ingestor/CandlestickListener.java`
- Delete: `src/main/java/com/bullish/marketdata/ingestor/binance/BinanceWebSocketIngestor.java`
- Modify: `src/main/resources/config.yaml`
- Modify: `config.yaml`

- [ ] **Step 1: Rewrite `App.java`**

```java
package com.bullish.marketdata;

import com.bullish.marketdata.config.AppConfig;
import com.bullish.marketdata.connector.FailoverWebSocketConnector;
import com.bullish.marketdata.ingestor.MarketDataQueue;
import com.bullish.marketdata.ingestor.MessageHandler;
import com.bullish.marketdata.ingestor.binance.BinanceKlineMessageType;
import com.bullish.marketdata.model.DataType;
import com.bullish.marketdata.persistor.Persistor;
import com.bullish.marketdata.persistor.PersistorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App {
    private static final Logger log = LoggerFactory.getLogger(App.class);
    private static final String BINANCE_WS_BASE = "wss://stream.binance.com:9443/ws/";

    public static void main(String[] args) throws Exception {
        String configPath = args.length > 0 ? args[0] : "config.yaml";
        AppConfig config = AppConfig.load(configPath);

        log.info("Starting Market Data Collector");
        log.info("Exchange: {}, Symbol: {}, Interval: {}, Persistor: {}",
            config.getExchange(), config.getSymbol(), config.getInterval(),
            config.getPersistorType());

        // 1. Message type defines stream + deserialization + conversion
        BinanceKlineMessageType messageType = new BinanceKlineMessageType(
            config.getSymbol(), config.getInterval());

        // 2. Queue decouples ingestor from persistor
        MarketDataQueue queue = new MarketDataQueue();

        // 3. Persistor subscribes to queue, filtered by DataType
        Persistor persistor = PersistorFactory.create(
            config.getPersistorType(), config.getOutputDir(), config.getInterval());
        queue.subscribe(DataType.KLINE, persistor);

        // 4. MessageHandler bridges WS messages → queue
        MessageHandler<?> handler = new MessageHandler<>(messageType, queue);

        // 5. FailoverWebSocketConnector manages primary + secondary
        String wsUrl = BINANCE_WS_BASE + messageType.streamName();
        FailoverWebSocketConnector connector = new FailoverWebSocketConnector(wsUrl, handler);

        // Start
        queue.start();
        connector.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received, stopping...");
            connector.stop();
            queue.stop();
        }));

        log.info("Collector running. Press Ctrl+C to stop.");
        Thread.currentThread().join();
    }
}
```

- [ ] **Step 2: Delete old ingestor files**

Delete:
- `src/main/java/com/bullish/marketdata/ingestor/Ingestor.java`
- `src/main/java/com/bullish/marketdata/ingestor/CandlestickListener.java`
- `src/main/java/com/bullish/marketdata/ingestor/binance/BinanceWebSocketIngestor.java`

- [ ] **Step 3: Update `config.yaml` (both copies)**

```yaml
exchange: BINANCE
symbol: BTCUSDT
interval: 1m
outputDir: ./data
persistorType: csv
```

- [ ] **Step 4: Run all tests**

```bash
"$MVN" test -q
```

Expected: All tests PASS.

- [ ] **Step 5: Smoke test — run the app**

```bash
"$MVN" exec:java -Dexec.mainClass="com.bullish.marketdata.App"
```

Expected: Logs showing two WebSocket connections (primary + secondary) to Binance. After ~1 minute, a closed candle should appear and a CSV file created as `./data/BINANCE_BTCUSDT_1m_<date>.csv`. Ctrl+C to stop.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor: rewire App with FailoverConnector, MessageHandler, Queue, PersistorFactory; remove old ingestor"
```
