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
