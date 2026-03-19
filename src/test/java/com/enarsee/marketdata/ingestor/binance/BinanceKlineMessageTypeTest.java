package com.enarsee.marketdata.ingestor.binance;

import com.enarsee.marketdata.model.Candlestick;
import com.enarsee.marketdata.model.Exchange;
import com.enarsee.marketdata.model.MarketData;
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
