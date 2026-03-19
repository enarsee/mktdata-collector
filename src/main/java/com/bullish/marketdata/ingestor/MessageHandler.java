package com.bullish.marketdata.ingestor;

import com.bullish.marketdata.connector.WebSocketCallback;
import com.bullish.marketdata.model.MarketData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Bridges the connector layer (raw JSON) to the ingestor layer (typed MarketData).
 * Implements WebSocketCallback so it plugs directly into the connector pipeline.
 *
 * Delegates all format-specific work to the MessageType:
 *   1. deserialize: JSON string → exchange-specific POJO (e.g., BinanceKlineEvent)
 *   2. toMarketData: POJO → standardized MarketData (with filtering, e.g., only closed candles)
 *   3. Publishes the result to the MarketDataQueue for async fan-out
 *
 * To add a new stream type (e.g., order book), create a new MessageType implementation
 * and wire a new MessageHandler — no changes needed here.
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
