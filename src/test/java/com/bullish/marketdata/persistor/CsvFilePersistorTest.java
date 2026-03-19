package com.bullish.marketdata.persistor;

import com.bullish.marketdata.model.Candlestick;
import com.bullish.marketdata.model.Exchange;
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

        persistor.accept(candle);

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

        persistor.accept(candle1);
        persistor.accept(candle2);

        Path expectedFile = tempDir.resolve("BINANCE_BTCUSDT_1m_2026-03-19.csv");
        List<String> lines = Files.readAllLines(expectedFile);
        assertEquals(3, lines.size());
        assertEquals(Candlestick.CSV_HEADER, lines.get(0));
    }
}
