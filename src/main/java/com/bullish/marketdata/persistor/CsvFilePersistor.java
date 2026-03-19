package com.bullish.marketdata.persistor;

import com.bullish.marketdata.model.Candlestick;
import com.bullish.marketdata.model.MarketData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneOffset;

public class CsvFilePersistor implements Persistor {
    private static final Logger log = LoggerFactory.getLogger(CsvFilePersistor.class);
    private final String outputDir;
    private final String interval;

    public CsvFilePersistor(String outputDir, String interval) {
        this.outputDir = outputDir;
        this.interval = interval;
    }

    @Override
    public void accept(MarketData data) {
        if (data instanceof Candlestick candle) {
            persistCandlestick(candle);
        } else {
            log.warn("CsvFilePersistor does not support: {}", data.getClass().getSimpleName());
        }
    }

    private void persistCandlestick(Candlestick candle) {
        try {
            Path dir = Path.of(outputDir);
            Files.createDirectories(dir);

            LocalDate date = candle.openTime().atZone(ZoneOffset.UTC).toLocalDate();
            String filename = String.join("_",
                candle.exchange().name(),
                candle.symbol(),
                interval,
                date.toString()
            ) + ".csv";
            Path file = dir.resolve(filename);

            boolean isNew = !Files.exists(file);

            StringBuilder sb = new StringBuilder();
            if (isNew) {
                sb.append(Candlestick.CSV_HEADER).append("\n");
            }
            sb.append(candle.toCsvLine()).append("\n");

            Files.writeString(file, sb.toString(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Failed to persist candlestick: {}", candle, e);
        }
    }
}
