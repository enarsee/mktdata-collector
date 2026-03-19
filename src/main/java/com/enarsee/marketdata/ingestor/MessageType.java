package com.enarsee.marketdata.ingestor;

import com.enarsee.marketdata.model.MarketData;

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
