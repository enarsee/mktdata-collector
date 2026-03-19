package com.bullish.marketdata.persistor;

/**
 * Creates persistor instances by type string from config. Centralizes the decision
 * of which storage backend to use, so App.java doesn't need to know about concrete
 * persistor classes. Add new cases here as new backends are implemented.
 */
public class PersistorFactory {

    public static Persistor create(String type, String outputDir, String interval) {
        return switch (type.toLowerCase()) {
            case "csv" -> new CsvFilePersistor(outputDir, interval);
            default -> throw new IllegalArgumentException("Unknown persistor type: " + type);
        };
    }
}
