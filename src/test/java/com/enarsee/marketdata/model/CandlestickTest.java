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
