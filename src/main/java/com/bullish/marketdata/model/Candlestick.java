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
