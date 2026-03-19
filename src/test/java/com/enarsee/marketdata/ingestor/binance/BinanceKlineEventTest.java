package com.enarsee.marketdata.ingestor.binance;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

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
