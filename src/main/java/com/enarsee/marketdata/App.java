package com.enarsee.marketdata;

import com.enarsee.marketdata.config.AppConfig;
import com.enarsee.marketdata.connector.FailoverWebSocketConnector;
import com.enarsee.marketdata.ingestor.MarketDataQueue;
import com.enarsee.marketdata.ingestor.MessageHandler;
import com.enarsee.marketdata.ingestor.binance.BinanceKlineMessageType;
import com.enarsee.marketdata.model.DataType;
import com.enarsee.marketdata.persistor.Persistor;
import com.enarsee.marketdata.persistor.PersistorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wires the full data pipeline. The architecture is layered as a callback chain:
 *
 *   Binance WS ──→ FailoverWebSocketConnector (hot standby, dedup)
 *                       ──→ MessageHandler (deserialize + convert via MessageType)
 *                              ──→ MarketDataQueue (async fan-out by DataType)
 *                                     ──→ Persistor (CSV, future: Parquet, DB)
 *
 * To add a new stream type: create a MessageType + Persistor, subscribe to the queue.
 * To add a new exchange: create new MessageType impls + exchange-specific POJOs.
 * To add a new storage backend: implement Persistor, register in PersistorFactory.
 */
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
