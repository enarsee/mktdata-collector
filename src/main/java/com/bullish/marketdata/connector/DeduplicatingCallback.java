package com.bullish.marketdata.connector;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WebSocketCallback decorator that deduplicates messages by Binance's "E" (eventTime) field.
 *
 * Required because the FailoverWebSocketConnector runs two connections simultaneously
 * in hot-standby mode — both receive the same messages, so without dedup the downstream
 * pipeline would process every candle twice.
 *
 * Uses a bounded LRU cache (LinkedHashMap with removeEldestEntry) to track seen event
 * times. Since the app runs continuously, we can't keep every eventTime forever —
 * that would be a memory leak. The LRU bound is safe because duplicates from the
 * two hot-standby connections arrive within milliseconds of each other, so by the
 * time an entry is evicted the event is long past and will never reappear.
 *
 * Synchronized on the cache since both WS connections deliver messages on separate
 * OkHttp threads.
 */
public class DeduplicatingCallback implements WebSocketCallback {
    private static final Logger log = LoggerFactory.getLogger(DeduplicatingCallback.class);
    private final WebSocketCallback delegate;
    private final Map<Long, Boolean> seen;

    public DeduplicatingCallback(WebSocketCallback delegate, int cacheSize) {
        this.delegate = delegate;
        this.seen = new LinkedHashMap<>(cacheSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
                return size() > cacheSize;
            }
        };
    }

    @Override
    public void onMessage(String message) {
        try {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            if (json.has("E")) {
                long eventTime = json.get("E").getAsLong();
                synchronized (seen) {
                    if (seen.putIfAbsent(eventTime, Boolean.TRUE) != null) {
                        log.trace("Dedup: skipping duplicate eventTime={}", eventTime);
                        return;
                    }
                }
            }
        } catch (Exception e) {
            // If we can't parse eventTime, pass through
        }
        delegate.onMessage(message);
    }

    @Override
    public void onConnected() {
        delegate.onConnected();
    }

    @Override
    public void onDisconnected() {
        delegate.onDisconnected();
    }
}
