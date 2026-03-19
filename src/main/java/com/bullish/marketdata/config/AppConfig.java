package com.bullish.marketdata.config;

import org.yaml.snakeyaml.Yaml;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

public class AppConfig {
    private String exchange;
    private String symbol;
    private String interval;
    private String outputDir;
    private String persistorType;

    public static AppConfig load(String path) throws IOException {
        Yaml yaml = new Yaml();
        try (InputStream in = new FileInputStream(path)) {
            Map<String, String> data = yaml.load(in);
            AppConfig config = new AppConfig();
            config.exchange = data.get("exchange");
            config.symbol = data.get("symbol");
            config.interval = data.get("interval");
            config.outputDir = data.get("outputDir");
            config.persistorType = data.getOrDefault("persistorType", "csv");
            return config;
        }
    }

    public String getExchange() { return exchange; }
    public String getSymbol() { return symbol; }
    public String getInterval() { return interval; }
    public String getOutputDir() { return outputDir; }
    public String getPersistorType() { return persistorType; }
}
