package com.enarsee.marketdata.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.FileWriter;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {

    @Test
    void shouldLoadConfigFromFile(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("config.yaml");
        try (FileWriter writer = new FileWriter(configFile.toFile())) {
            writer.write("exchange: BINANCE\nsymbol: ETHUSDT\ninterval: 1m\noutputDir: ./data\n");
        }

        AppConfig config = AppConfig.load(configFile.toString());

        assertEquals("BINANCE", config.getExchange());
        assertEquals("ETHUSDT", config.getSymbol());
        assertEquals("1m", config.getInterval());
        assertEquals("./data", config.getOutputDir());
    }
}
