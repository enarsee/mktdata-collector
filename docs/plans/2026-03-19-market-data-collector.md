# Market Data Collector Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Java-based market data collector that subscribes to Binance 1m candlestick WebSocket streams and persists standardized OHLCV data to CSV files.

**Architecture:** Monolithic single-jar with layered packages (model, ingestor, persistor, config). Interfaces define extension points for future exchanges and storage backends. A listener pattern connects ingestors to persistors.

**Tech Stack:** Java 17, Maven, OkHttp (WebSocket), SnakeYAML, SLF4J + Logback, JUnit 5

**Maven alias (used throughout):**
```bash
MVN="/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"
```

---

## Chunk 1: Project Scaffold & Data Model

### Task 1: Maven Project Setup

**Files:**
- Create: `pom.xml`
- Delete: `1903.iml`, `src/Main.java`

- [ ] **Step 1: Create `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.enarsee</groupId>
    <artifactId>market-data-collector</artifactId>
    <version>1.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <!-- WebSocket -->
        <dependency>
            <groupId>com.squareup.okhttp3</groupId>
            <artifactId>okhttp</artifactId>
            <version>4.12.0</version>
        </dependency>

        <!-- JSON parsing -->
        <dependency>
            <groupId>com.google.code.gson</groupId>
            <artifactId>gson</artifactId>
            <version>2.11.0</version>
        </dependency>

        <!-- YAML config -->
        <dependency>
            <groupId>org.yaml</groupId>
            <artifactId>snakeyaml</artifactId>
            <version>2.2</version>
        </dependency>

        <!-- Logging -->
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
            <version>2.0.13</version>
        </dependency>
        <dependency>
            <groupId>ch.qos.logback</groupId>
            <artifactId>logback-classic</artifactId>
            <version>1.5.6</version>
        </dependency>

        <!-- Testing -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.10.2</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-jar-plugin</artifactId>
                <version>3.4.1</version>
                <configuration>
                    <archive>
                        <manifest>
                            <mainClass>com.enarsee.marketdata.App</mainClass>
                        </manifest>
                    </archive>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Create Maven directory structure**

```bash
mkdir -p src/main/java/com/bullish/marketdata/{model,ingestor/binance,persistor,config}
mkdir -p src/main/resources
mkdir -p src/test/java/com/bullish/marketdata/{model,persistor}
```

- [ ] **Step 3: Delete old IntelliJ boilerplate**

Delete `src/Main.java` and `1903.iml` (IntelliJ will regenerate project config from `pom.xml`).

- [ ] **Step 4: Verify project compiles**

```bash
"$MVN" compile
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/ .gitignore
git commit -m "chore: initialize Maven project with dependencies"
```

---

### Task 2: Candlestick Data Model

**Files:**
- Create: `src/main/java/com/bullish/marketdata/model/Exchange.java`
- Create: `src/main/java/com/bullish/marketdata/model/Candlestick.java`
- Test: `src/test/java/com/bullish/marketdata/model/CandlestickTest.java`

- [ ] **Step 1: Write `Exchange` enum**

```java
package com.enarsee.marketdata.model;

public enum Exchange {
    BINANCE
}
```

- [ ] **Step 2: Write failing test for `Candlestick`**

```java
package com.enarsee.marketdata.model;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class CandlestickTest {

    @Test
    void shouldCreateCandlestickWithAllFields() {
        Instant openTime = Instant.parse("2026-03-19T00:00:00Z");
        Instant closeTime = Instant.parse("2026-03-19T00:01:00Z");

        Candlestick candle = new Candlestick(
            Exchange.BINANCE, "BTCUSDT", openTime, closeTime,
            new BigDecimal("67000.50"), new BigDecimal("67100.00"),
            new BigDecimal("66900.00"), new BigDecimal("67050.25"),
            new BigDecimal("123.456")
        );

        assertEquals(Exchange.BINANCE, candle.exchange());
        assertEquals("BTCUSDT", candle.symbol());
        assertEquals(openTime, candle.openTime());
        assertEquals(closeTime, candle.closeTime());
        assertEquals(new BigDecimal("67000.50"), candle.open());
        assertEquals(new BigDecimal("67100.00"), candle.high());
        assertEquals(new BigDecimal("66900.00"), candle.low());
        assertEquals(new BigDecimal("67050.25"), candle.close());
        assertEquals(new BigDecimal("123.456"), candle.volume());
    }

    @Test
    void shouldFormatCsvLine() {
        Instant openTime = Instant.parse("2026-03-19T00:00:00Z");
        Instant closeTime = Instant.parse("2026-03-19T00:01:00Z");

        Candlestick candle = new Candlestick(
            Exchange.BINANCE, "BTCUSDT", openTime, closeTime,
            new BigDecimal("67000.50"), new BigDecimal("67100.00"),
            new BigDecimal("66900.00"), new BigDecimal("67050.25"),
            new BigDecimal("123.456")
        );

        String csv = candle.toCsvLine();
        assertEquals("2026-03-19T00:00:00Z,2026-03-19T00:01:00Z,67000.50,67100.00,66900.00,67050.25,123.456", csv);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

```bash
"$MVN" test -pl . -Dtest="com.enarsee.marketdata.model.CandlestickTest"
```

Expected: FAIL — `Candlestick` class not found.

- [ ] **Step 4: Implement `Candlestick` as a Java record**

```java
package com.enarsee.marketdata.model;

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
) {
    public static final String CSV_HEADER = "open_time,close_time,open,high,low,close,volume";

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

- [ ] **Step 5: Run test to verify it passes**

```bash
"$MVN" test -pl . -Dtest="com.enarsee.marketdata.model.CandlestickTest"
```

Expected: 2 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add src/
git commit -m "feat: add Candlestick record and Exchange enum"
```

---

## Chunk 2: Interfaces, Config & CSV Persistor

### Task 3: Core Interfaces

**Files:**
- Create: `src/main/java/com/bullish/marketdata/ingestor/CandlestickListener.java`
- Create: `src/main/java/com/bullish/marketdata/ingestor/Ingestor.java`
- Create: `src/main/java/com/bullish/marketdata/persistor/Persistor.java`

- [ ] **Step 1: Write `CandlestickListener` interface**

```java
package com.enarsee.marketdata.ingestor;

import com.enarsee.marketdata.model.Candlestick;

public interface CandlestickListener {
    void onCandlestick(Candlestick candle);
}
```

- [ ] **Step 2: Write `Ingestor` interface**

```java
package com.enarsee.marketdata.ingestor;

public interface Ingestor {
    void start();
    void stop();
    void addListener(CandlestickListener listener);
}
```

- [ ] **Step 3: Write `Persistor` interface**

```java
package com.enarsee.marketdata.persistor;

import com.enarsee.marketdata.ingestor.CandlestickListener;

public interface Persistor extends CandlestickListener {
}
```

- [ ] **Step 4: Verify compile**

```bash
"$MVN" compile
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add src/
git commit -m "feat: add Ingestor, CandlestickListener, and Persistor interfaces"
```

---

### Task 4: YAML Config

**Files:**
- Create: `src/main/java/com/bullish/marketdata/config/AppConfig.java`
- Create: `src/main/resources/config.yaml`
- Test: `src/test/java/com/bullish/marketdata/config/AppConfigTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.enarsee.marketdata.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.FileWriter;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {

    @Test
    void shouldLoadConfigFromFile(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("config.yaml");
        try (FileWriter writer = new FileWriter(configFile.toFile())) {
            writer.write("exchange: BINANCE\nsymbol: ETHUSDT\ninterval: 1m\noutputDir: ./data\n");
        }

        AppConfig config = AppConfig.load(configFile.toString());

        assertEquals("BINANCE", config.getExchange());
        assertEquals("ETHUSDT", config.getSymbol());
        assertEquals("1m", config.getInterval());
        assertEquals("./data", config.getOutputDir());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
"$MVN" test -Dtest="com.enarsee.marketdata.config.AppConfigTest"
```

Expected: FAIL — `AppConfig` not found.

- [ ] **Step 3: Implement `AppConfig`**

```java
package com.enarsee.marketdata.config;

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

    public static AppConfig load(String path) throws IOException {
        Yaml yaml = new Yaml();
        try (InputStream in = new FileInputStream(path)) {
            Map<String, String> data = yaml.load(in);
            AppConfig config = new AppConfig();
            config.exchange = data.get("exchange");
            config.symbol = data.get("symbol");
            config.interval = data.get("interval");
            config.outputDir = data.get("outputDir");
            return config;
        }
    }

    public String getExchange() { return exchange; }
    public String getSymbol() { return symbol; }
    public String getInterval() { return interval; }
    public String getOutputDir() { return outputDir; }
}
```

- [ ] **Step 4: Create default `config.yaml`**

```yaml
exchange: BINANCE
symbol: BTCUSDT
interval: 1m
outputDir: ./data
```

- [ ] **Step 5: Run test to verify it passes**

```bash
"$MVN" test -Dtest="com.enarsee.marketdata.config.AppConfigTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/
git commit -m "feat: add YAML config loading"
```

---

### Task 5: CSV File Persistor

**Files:**
- Create: `src/main/java/com/bullish/marketdata/persistor/CsvFilePersistor.java`
- Test: `src/test/java/com/bullish/marketdata/persistor/CsvFilePersistorTest.java`

CSV filename convention: `{Exchange}_{Symbol}_{interval}_{date}.csv`
Example: `BINANCE_BTCUSDT_1m_2026-03-19.csv` — flat under the output directory.

- [ ] **Step 1: Write failing test**

```java
package com.enarsee.marketdata.persistor;

import com.enarsee.marketdata.model.Candlestick;
import com.enarsee.marketdata.model.Exchange;
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

        persistor.onCandlestick(candle);

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

        persistor.onCandlestick(candle1);
        persistor.onCandlestick(candle2);

        Path expectedFile = tempDir.resolve("BINANCE_BTCUSDT_1m_2026-03-19.csv");
        List<String> lines = Files.readAllLines(expectedFile);
        assertEquals(3, lines.size()); // header + 2 data lines
        assertEquals(Candlestick.CSV_HEADER, lines.get(0));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
"$MVN" test -Dtest="com.enarsee.marketdata.persistor.CsvFilePersistorTest"
```

Expected: FAIL — `CsvFilePersistor` not found.

- [ ] **Step 3: Implement `CsvFilePersistor`**

```java
package com.enarsee.marketdata.persistor;

import com.enarsee.marketdata.model.Candlestick;
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
    public void onCandlestick(Candlestick candle) {
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

- [ ] **Step 4: Run test to verify it passes**

```bash
"$MVN" test -Dtest="com.enarsee.marketdata.persistor.CsvFilePersistorTest"
```

Expected: 2 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/
git commit -m "feat: add CsvFilePersistor with daily file rotation"
```

---

## Chunk 3: Binance WebSocket Ingestor & App Wiring

### Task 6: Binance WebSocket Ingestor

**Files:**
- Create: `src/main/java/com/bullish/marketdata/ingestor/binance/BinanceWebSocketIngestor.java`

The Binance kline WebSocket endpoint is: `wss://stream.binance.com:9443/ws/<symbol>@kline_<interval>`

For example: `wss://stream.binance.com:9443/ws/btcusdt@kline_1m`

The WebSocket sends JSON messages. When a kline closes (`k.x == true`), we parse it into a `Candlestick` and notify listeners.

- [ ] **Step 1: Implement `BinanceWebSocketIngestor`**

```java
package com.enarsee.marketdata.ingestor.binance;

import com.enarsee.marketdata.ingestor.CandlestickListener;
import com.enarsee.marketdata.ingestor.Ingestor;
import com.enarsee.marketdata.model.Candlestick;
import com.enarsee.marketdata.model.Exchange;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class BinanceWebSocketIngestor implements Ingestor {
    private static final Logger log = LoggerFactory.getLogger(BinanceWebSocketIngestor.class);
    private static final String BASE_URL = "wss://stream.binance.com:9443/ws/";

    private final String symbol;
    private final String interval;
    private final List<CandlestickListener> listeners = new CopyOnWriteArrayList<>();
    private final OkHttpClient client = new OkHttpClient();
    private WebSocket webSocket;

    public BinanceWebSocketIngestor(String symbol, String interval) {
        this.symbol = symbol.toLowerCase();
        this.interval = interval;
    }

    @Override
    public void addListener(CandlestickListener listener) {
        listeners.add(listener);
    }

    @Override
    public void start() {
        String url = BASE_URL + symbol + "@kline_" + interval;
        log.info("Connecting to Binance WebSocket: {}", url);

        Request request = new Request.Builder().url(url).build();
        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                log.info("WebSocket connected for {}", symbol);
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                try {
                    JsonObject json = JsonParser.parseString(text).getAsJsonObject();
                    JsonObject kline = json.getAsJsonObject("k");

                    boolean isClosed = kline.get("x").getAsBoolean();
                    if (!isClosed) return;

                    Candlestick candle = new Candlestick(
                        Exchange.BINANCE,
                        kline.get("s").getAsString(),
                        Instant.ofEpochMilli(kline.get("t").getAsLong()),
                        Instant.ofEpochMilli(kline.get("T").getAsLong()),
                        new BigDecimal(kline.get("o").getAsString()),
                        new BigDecimal(kline.get("h").getAsString()),
                        new BigDecimal(kline.get("l").getAsString()),
                        new BigDecimal(kline.get("c").getAsString()),
                        new BigDecimal(kline.get("v").getAsString())
                    );

                    log.debug("Closed candle: {} {} O={} H={} L={} C={}",
                        candle.symbol(), candle.openTime(),
                        candle.open(), candle.high(), candle.low(), candle.close());

                    for (CandlestickListener listener : listeners) {
                        listener.onCandlestick(candle);
                    }
                } catch (Exception e) {
                    log.error("Error parsing kline message: {}", text, e);
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                log.error("WebSocket failure, reconnecting in 5s...", t);
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
                start();
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                log.info("WebSocket closing: {} {}", code, reason);
            }
        });
    }

    @Override
    public void stop() {
        if (webSocket != null) {
            webSocket.close(1000, "Shutting down");
            log.info("WebSocket closed for {}", symbol);
        }
        client.dispatcher().executorService().shutdown();
    }
}
```

- [ ] **Step 2: Verify compile**

```bash
"$MVN" compile
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add src/
git commit -m "feat: add BinanceWebSocketIngestor with auto-reconnect"
```

---

### Task 7: Logback Configuration

**Files:**
- Create: `src/main/resources/logback.xml`

- [ ] **Step 1: Create `logback.xml`**

```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <logger name="com.enarsee.marketdata" level="DEBUG"/>

    <root level="INFO">
        <appender-ref ref="STDOUT"/>
    </root>
</configuration>
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/logback.xml
git commit -m "chore: add logback configuration"
```

---

### Task 8: App Wiring & Main Entry Point

**Files:**
- Create: `src/main/java/com/bullish/marketdata/App.java`

- [ ] **Step 1: Implement `App.java`**

```java
package com.enarsee.marketdata;

import com.enarsee.marketdata.config.AppConfig;
import com.enarsee.marketdata.ingestor.Ingestor;
import com.enarsee.marketdata.ingestor.binance.BinanceWebSocketIngestor;
import com.enarsee.marketdata.persistor.CsvFilePersistor;
import com.enarsee.marketdata.persistor.Persistor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App {
    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) throws Exception {
        String configPath = args.length > 0 ? args[0] : "config.yaml";
        AppConfig config = AppConfig.load(configPath);

        log.info("Starting Market Data Collector");
        log.info("Exchange: {}, Symbol: {}, Interval: {}",
            config.getExchange(), config.getSymbol(), config.getInterval());

        Persistor persistor = new CsvFilePersistor(config.getOutputDir(), config.getInterval());

        Ingestor ingestor = new BinanceWebSocketIngestor(
            config.getSymbol(), config.getInterval());
        ingestor.addListener(persistor);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received, stopping...");
            ingestor.stop();
        }));

        ingestor.start();
        log.info("Collector running. Press Ctrl+C to stop.");

        // Keep main thread alive
        Thread.currentThread().join();
    }
}
```

- [ ] **Step 2: Copy `config.yaml` to project root for runtime**

```bash
cp src/main/resources/config.yaml ./config.yaml
```

- [ ] **Step 3: Build and run smoke test**

```bash
"$MVN" package -DskipTests
java -jar target/market-data-collector-1.0-SNAPSHOT.jar
```

Expected: logs showing WebSocket connection to Binance. After ~1 minute, a closed candle should appear and a CSV file created as `./data/BINANCE_BTCUSDT_1m_<date>.csv`. Ctrl+C to stop.

- [ ] **Step 4: Commit**

```bash
git add src/ config.yaml
git commit -m "feat: add App entry point wiring ingestor to persistor"
```

---

## Chunk 4: Update .gitignore & Final Cleanup

### Task 9: Update `.gitignore` and clean up

**Files:**
- Modify: `.gitignore`

- [ ] **Step 1: Add Maven and data directories to `.gitignore`**

Append to `.gitignore`:
```
### Maven ###
target/

### Data output ###
data/
```

- [ ] **Step 2: Run all tests**

```bash
"$MVN" test
```

Expected: All tests PASS.

- [ ] **Step 3: Commit**

```bash
git add .gitignore
git commit -m "chore: update gitignore for Maven and data output"
```