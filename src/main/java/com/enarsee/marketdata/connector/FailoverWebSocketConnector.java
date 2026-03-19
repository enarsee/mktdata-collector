package com.enarsee.marketdata.connector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hot-standby failover: runs two WebSocket connections to the same stream simultaneously.
 * Both feed into a shared DeduplicatingCallback, so downstream only sees each message once.
 *
 * If one connection drops, the other continues delivering data with zero gap.
 * The failed connection auto-reconnects via WebSocketConnector's built-in reconnect logic
 * and resumes as the new standby.
 *
 * Trade-off: doubles the number of WebSocket connections, but for a small set of
 * streams this is negligible and provides much stronger data continuity than
 * reconnect-only approaches.
 */
public class FailoverWebSocketConnector {
    private static final Logger log = LoggerFactory.getLogger(FailoverWebSocketConnector.class);
    private static final long DEFAULT_HEARTBEAT_MS = 30_000;
    private static final int DEDUP_CACHE_SIZE = 1000;

    private final WebSocketConnector primary;
    private final WebSocketConnector secondary;

    public FailoverWebSocketConnector(String url, WebSocketCallback callback) {
        DeduplicatingCallback dedup = new DeduplicatingCallback(callback, DEDUP_CACHE_SIZE);
        this.primary = new WebSocketConnector(url, dedup, DEFAULT_HEARTBEAT_MS);
        this.secondary = new WebSocketConnector(url, dedup, DEFAULT_HEARTBEAT_MS);
    }

    public void start() {
        log.info("Starting failover connector (primary + secondary hot standby)");
        primary.connect();
        secondary.connect();
    }

    public void stop() {
        log.info("Stopping failover connector");
        primary.disconnect();
        secondary.disconnect();
    }
}
