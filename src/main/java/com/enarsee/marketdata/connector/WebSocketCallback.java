package com.enarsee.marketdata.connector;

/**
 * Decoupled callback for WebSocket events. This is the core glue interface in the
 * connector layer — it allows the transport (WebSocketConnector) to remain completely
 * agnostic to what's being streamed. Each layer in the pipeline implements this
 * interface and wraps the next:
 *
 *   WebSocketConnector → DeduplicatingCallback → MessageHandler → Queue
 *
 * This decorator pattern lets us compose behaviors (failover, dedup, parsing)
 * without any layer knowing about the others.
 */
public interface WebSocketCallback {
    void onMessage(String message);
    void onConnected();
    void onDisconnected();
}
