package com.bullish.marketdata.persistor;

import com.bullish.marketdata.model.MarketData;

import java.util.function.Consumer;

/**
 * Consumes MarketData from the queue. Extends Consumer<MarketData> so persistors
 * can be directly passed to MarketDataQueue.subscribe() without adapter lambdas.
 *
 * Each persistor implementation handles a specific data type (e.g., CsvFilePersistor
 * handles Candlestick/KLINE). The queue's DataType filter ensures each persistor only
 * receives its data type, so persistors don't need cross-type routing logic.
 *
 * To add a new storage backend (e.g., Parquet, database), implement this interface
 * and register it in PersistorFactory.
 */
public interface Persistor extends Consumer<MarketData> {
}
