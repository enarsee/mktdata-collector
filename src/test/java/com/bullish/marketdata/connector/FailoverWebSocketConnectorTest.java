package com.bullish.marketdata.connector;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FailoverWebSocketConnectorTest {

    @Test
    void shouldDeduplicateMessagesFromBothConnections() {
        List<String> received = new ArrayList<>();
        DeduplicatingCallback dedup = new DeduplicatingCallback(
            new WebSocketCallback() {
                @Override public void onMessage(String message) { received.add(message); }
                @Override public void onConnected() {}
                @Override public void onDisconnected() {}
            },
            100
        );

        String msg = "{\"E\":1672515782136,\"k\":{\"t\":1672515780000}}";
        dedup.onMessage(msg);
        dedup.onMessage(msg);

        assertEquals(1, received.size());
    }

    @Test
    void shouldPassThroughDistinctMessages() {
        List<String> received = new ArrayList<>();
        DeduplicatingCallback dedup = new DeduplicatingCallback(
            new WebSocketCallback() {
                @Override public void onMessage(String message) { received.add(message); }
                @Override public void onConnected() {}
                @Override public void onDisconnected() {}
            },
            100
        );

        dedup.onMessage("{\"E\":1000}");
        dedup.onMessage("{\"E\":2000}");

        assertEquals(2, received.size());
    }
}
