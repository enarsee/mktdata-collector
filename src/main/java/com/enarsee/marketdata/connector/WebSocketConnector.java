package com.enarsee.marketdata.connector;

import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Generic WebSocket transport layer. Handles connection lifecycle, OkHttp ping-based
 * heartbeat for liveness detection, and automatic reconnection on failure.
 *
 * Intentionally knows nothing about message content — it just delivers raw JSON
 * strings to the callback. This separation means the same connector can be reused
 * for any exchange or stream type.
 *
 * The FailoverWebSocketConnector creates two of these in hot-standby mode,
 * both streaming simultaneously for near-zero gap coverage on disconnections.
 */
public class WebSocketConnector {
    private static final Logger log = LoggerFactory.getLogger(WebSocketConnector.class);

    private final String url;
    private final WebSocketCallback callback;
    private final OkHttpClient client;
    private final ScheduledExecutorService heartbeatExecutor;
    private final long heartbeatIntervalMs;

    private WebSocket webSocket;
    private ScheduledFuture<?> heartbeatTask;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);

    public WebSocketConnector(String url, WebSocketCallback callback, long heartbeatIntervalMs) {
        this.url = url;
        this.callback = callback;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.client = new OkHttpClient.Builder()
            .pingInterval(heartbeatIntervalMs, TimeUnit.MILLISECONDS)
            .build();
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-heartbeat-" + url.hashCode());
            t.setDaemon(true);
            return t;
        });
    }

    public void connect() {
        running.set(true);
        log.info("Connecting WebSocket: {}", url);
        Request request = new Request.Builder().url(url).build();
        webSocket = client.newWebSocket(request, new okhttp3.WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                log.info("WebSocket connected: {}", url);
                connected.set(true);
                startHeartbeat();
                callback.onConnected();
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                callback.onMessage(text);
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                log.error("WebSocket failure on {}: {}", url, t.getMessage());
                connected.set(false);
                stopHeartbeat();
                callback.onDisconnected();
                if (running.get()) {
                    reconnect();
                }
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                log.info("WebSocket closing {}: {} {}", url, code, reason);
                connected.set(false);
                stopHeartbeat();
                callback.onDisconnected();
            }
        });
    }

    public void disconnect() {
        running.set(false);
        stopHeartbeat();
        if (webSocket != null) {
            webSocket.close(1000, "Shutting down");
        }
        client.dispatcher().executorService().shutdown();
        heartbeatExecutor.shutdown();
        log.info("WebSocket disconnected: {}", url);
    }

    public boolean isConnected() {
        return connected.get();
    }

    private void reconnect() {
        log.info("Reconnecting WebSocket in 5s: {}", url);
        heartbeatExecutor.schedule(this::connect, 5, TimeUnit.SECONDS);
    }

    private void startHeartbeat() {
        heartbeatTask = heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (connected.get() && webSocket != null) {
                log.trace("Sending heartbeat ping to {}", url);
            }
        }, heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
    }
}
